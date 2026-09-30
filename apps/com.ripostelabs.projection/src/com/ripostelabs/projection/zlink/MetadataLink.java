package com.ripostelabs.projection.zlink;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;

/**
 * The client side of the daemon's metadata port ({@link Metadata}). Dials, reads messages,
 * folds each CarPlay NowPlayingUpdate into the running track and hands a copy to the listener
 * on its own thread. Redials while started: the daemon only listens while it runs, and it
 * takes one client at a time.
 *
 * <p>Plain {@code java.net}, no Android, so a test can put a {@code ServerSocket} behind it.
 */
public final class MetadataLink {

    public interface Listener {
        /** The merged track after an update. Called on the link's thread. */
        void onNowPlaying(Metadata.NowPlaying np);

        void onLog(String line);
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final long REDIAL_MS = 3000;
    /** Ids other than media info are logged this many times each, then only counted. */
    private static final int LOG_OTHER_IDS = 3;

    private final String host;
    private final int port;
    private final Listener listener;
    private final int[] otherLogged = new int[Metadata.CP_MEDIA_INFO + 1];
    private volatile boolean running;
    private volatile Socket socket;
    private Thread thread;

    public MetadataLink(String host, int port, Listener listener) {
        this.host = host;
        this.port = port;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "zlink-metadata");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        closeSocket();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void loop() {
        while (running) {
            try {
                session();
            } catch (IOException e) {
                listener.onLog("metadata: " + e.getMessage());
            } finally {
                closeSocket();
            }
            if (!pause()) {
                return;
            }
        }
    }

    /** One connection: a fresh track, then every message until the daemon closes. */
    private void session() throws IOException {
        Socket s = new Socket();
        socket = s;
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        listener.onLog("metadata: connected to " + port);

        Metadata.NowPlaying track = new Metadata.NowPlaying();
        InputStream in = new BufferedInputStream(s.getInputStream());
        while (running) {
            Metadata.Frame f = Metadata.read(in);
            if (f == null) {
                listener.onLog("metadata: daemon closed");
                return;
            }
            if (f.id != Metadata.CP_MEDIA_INFO) {
                logOther(f);
                continue;
            }
            track.apply(Metadata.nowPlaying(f.body));
            listener.onNowPlaying(track.copy());
        }
    }

    /** Phone, call and route messages are not used yet; the first few go to the log for the next item. */
    private void logOther(Metadata.Frame f) {
        if (f.id < 0 || f.id >= otherLogged.length) {
            return;
        }
        if (otherLogged[f.id]++ >= LOG_OTHER_IDS) {
            return;
        }
        listener.onLog(String.format(Locale.ROOT, "metadata: id %d, %d bytes", f.id, f.body.length));
    }

    /** Wait before redialling; false when stopped meanwhile. */
    private boolean pause() {
        try {
            Thread.sleep(REDIAL_MS);
        } catch (InterruptedException e) {
            return false;
        }
        return running;
    }

    private void closeSocket() {
        Socket s = socket;
        socket = null;
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // Closing is best effort; the next dial opens a new one.
        }
    }
}
