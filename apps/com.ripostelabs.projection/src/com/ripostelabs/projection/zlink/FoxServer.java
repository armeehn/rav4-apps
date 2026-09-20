package com.ripostelabs.projection.zlink;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

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

    final String name;
    private final int port;
    private final Listener listener;
    private ServerSocket server;
    private Socket client;
    private OutputStream out;
    private Thread acceptor;
    private volatile boolean running;

    FoxServer(String name, int port, Listener listener) {
        this.name = name;
        this.port = port;
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

    /** Frames go out whole and in order; a failed write drops the link so the daemon redials. */
    void send(int id, byte[] payload) {
        OutputStream o;
        synchronized (this) {
            o = out;
        }
        if (o == null) {
            return;
        }
        try {
            synchronized (o) {
                o.write(Fox.encode(id, payload));
                o.flush();
            }
        } catch (IOException e) {
            Log.w(TAG, name + ": write failed: " + e.getMessage());
            closeClient();
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, name + ": accept failed: " + e.getMessage());
                }
                return;
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
                out = s.getOutputStream();
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
                    listener.onFrame(this, f);
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
        out = null;
    }
}
