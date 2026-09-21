package com.ripostelabs.projection.zlink;

import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One localhost server the daemon dials: accepts a connection, hands every frame to the
 * listener on the reader thread, and writes frames back in order. The daemon reconnects by
 * itself every ten seconds, so a dropped link needs nothing from us but a fresh accept.
 */
final class FoxServer {

    interface Listener {
        void onConnected(FoxServer server);

        void onFrame(FoxServer server, Fox.Frame frame);

        void onClosed(FoxServer server);
    }

    private static final String TAG = "Projection";
    private static final int BACKLOG = 1;
    private static final int READ_BUF = 64 * 1024;
    private static final String LOOPBACK_V4 = "127.0.0.1";
    private static final long ACCEPT_RETRY_MS = 500;

    final String name;
    private final int port;
    private final int threadPriority;
    private final Listener listener;
    /** Bounded: a daemon that stops reading a channel drops our oldest frame, not the process. */
    private static final int OUTBOX_LIMIT = 256;
    private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>(OUTBOX_LIMIT);
    private ServerSocket server;
    private volatile Socket client;
    private Thread acceptor;
    private Thread writer;
    private volatile boolean running;

    /**
     * @param threadPriority android.os.Process.THREAD_PRIORITY_* for the channel's reader and
     *                       writer threads. The daemon's frames must not queue behind the launcher
     *                       or a background sync; the video and audio channels run urgent.
     */
    FoxServer(String name, int port, int threadPriority, Listener listener) {
        this.name = name;
        this.port = port;
        this.threadPriority = threadPriority;
        this.listener = listener;
    }

    synchronized void start() throws IOException {
        if (running) {
            return;
        }
        // The daemon dials 127.0.0.1; the platform's loopback default is ::1, which never sees it.
        server = new ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_V4));
        server.setReuseAddress(true);
        running = true;
        acceptor = new Thread(this::acceptLoop, "fox-" + name);
        acceptor.start();
    }

    synchronized void stop() {
        running = false;
        closeClient();
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // shutting down
        }
        server = null;
    }

    boolean isConnected() {
        return client != null;
    }

    /**
     * Frames go out whole and in order from the writer thread, so a caller on the main thread
     * (a hotspot callback, a touch) never touches the socket. Nothing queued survives a link
     * drop: the daemon redials and starts its session over.
     */
    void send(int id, byte[] payload) {
        if (client == null) {
            return;
        }
        byte[] frame = Fox.encode(id, payload);
        while (!outbox.offer(frame)) {
            outbox.poll();
        }
    }

    private void writeLoop(Socket s) {
        Process.setThreadPriority(threadPriority);
        OutputStream o;
        try {
            o = s.getOutputStream();
            while (true) {
                byte[] frame = outbox.take();
                o.write(frame);
                o.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, name + ": write failed: " + e.getMessage());
            closeClient();
        } catch (InterruptedException ignored) {
            // link closed under us
        }
    }

    private void acceptLoop() {
        Process.setThreadPriority(threadPriority);
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (!running || server.isClosed()) {
                    return;
                }
                // A transient accept error (fd pressure, a reset) is not the end of the channel.
                Log.w(TAG, name + ": accept failed: " + e.getMessage());
                try {
                    Thread.sleep(ACCEPT_RETRY_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            serve(s);
        }
    }

    /** One client at a time: the daemon has a single connection per channel. */
    private void serve(Socket s) {
        InputStream in;
        try {
            s.setTcpNoDelay(true);
            in = s.getInputStream();
            synchronized (this) {
                closeClient();
                client = s;
                outbox.clear();
                writer = new Thread(() -> writeLoop(s), "fox-" + name + "-out");
                writer.start();
            }
        } catch (IOException e) {
            Log.w(TAG, name + ": client setup failed: " + e.getMessage());
            return;
        }
        listener.onConnected(this);

        Fox.Parser parser = new Fox.Parser();
        byte[] buf = new byte[READ_BUF];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                parser.feed(buf, 0, n);
                Fox.Frame f;
                while ((f = parser.next()) != null) {
                    // One malformed payload is the daemon's bug, not a reason to lose the
                    // session: the listener's parser throws on it, the link stays up.
                    try {
                        listener.onFrame(this, f);
                    } catch (RuntimeException e) {
                        Log.w(TAG, name + ": frame 0x" + Integer.toHexString(f.id) + " rejected: " + e);
                    }
                }
            }
        } catch (IOException e) {
            if (running) {
                Log.i(TAG, name + ": link dropped: " + e.getMessage());
            }
        }
        closeClient();
        listener.onClosed(this);
    }

    private synchronized void closeClient() {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException ignored) {
            // already gone
        }
        client = null;
        if (writer != null) {
            writer.interrupt();
            writer = null;
        }
        outbox.clear();
    }
}
