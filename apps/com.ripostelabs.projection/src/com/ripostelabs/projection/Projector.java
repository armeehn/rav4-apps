package com.ripostelabs.projection;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.ripostelabs.design.MediaCitizen;
import com.ripostelabs.projection.aa.Ids;
import com.ripostelabs.projection.aa.Messages;
import com.ripostelabs.projection.aa.Session;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Glue between the transport, the protocol and the screen: owns the reader thread, the ping
 * timer, the decoder, the audio tracks and the app's one {@link MediaCitizen}.
 *
 * <pre>
 *   Pipe.read() ----> Session.onBytes() --> Sink callbacks --> VideoSink / AudioSink
 *   (UsbLink or   ^                              |
 *    TcpTransport)+---- Session.Link.write() <---+   (acks, touches, pings)
 * </pre>
 *
 * <p>Everything from the phone runs on the reader thread; the activity is told on the main
 * thread through {@link Screen}.
 */
final class Projector implements Session.Sink {

    /** What the activity shows; called on the main thread. */
    interface Screen {
        void onStatus(String line);

        void onVideoSize(int width, int height);

        void onEnded(String why);
    }

    /** The byte pipe to the phone: USB bulk endpoints (stage 1) or a TCP socket (stage 2). */
    interface Pipe extends Session.Link, Closeable {
        /** Bytes read, or -1 on a timeout with nothing to deliver. */
        int read(byte[] buf) throws IOException;

        @Override
        void close();
    }

    private static final String TAG = "Projection";

    /** The GT6-EAU panel. */
    static final int PANEL_WIDTH = 1920;
    static final int PANEL_HEIGHT = 720;
    /**
     * Android Auto offers 800x480, 1280x720 and 1920x1080; nothing is 1920x720. The first
     * config asks for a 1080p stream with a 360-row bottom margin, so the phone lays its UI out
     * in the top 1920x720 and the panel shows that region 1:1. 720p is the fallback if the
     * phone declines. Whether the phone honours the margin is untested until one is plugged in.
     */
    static final int MARGIN_ROWS = 1080 - PANEL_HEIGHT;
    /** The density the phone lays the UI out at. Unverified; 160 is a common head-unit value. */
    static final int DECLARED_DPI = 160;

    private static final long PING_PERIOD_S = 5;
    private static final int READ_BUFFER = 16384;

    private final Context context;
    private final Screen screen;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VideoSink video = new VideoSink();
    private final AudioSink[] audio = new AudioSink[256];

    private Pipe link;
    private Session session;
    private Thread reader;
    private ScheduledExecutorService pinger;
    private MediaCitizen citizen;
    private volatile boolean running;

    Projector(Context context, Screen screen) {
        this.context = context;
        this.screen = screen;
        audio[Ids.CH_MEDIA_AUDIO] = new AudioSink(Ids.CH_MEDIA_AUDIO);
        audio[Ids.CH_SPEECH_AUDIO] = new AudioSink(Ids.CH_SPEECH_AUDIO);
        audio[Ids.CH_SYSTEM_AUDIO] = new AudioSink(Ids.CH_SYSTEM_AUDIO);
    }

    static Session.Config config() {
        Session.Config c = new Session.Config();
        c.headUnitName = "Riposte Projection";
        c.carModel = "RAV4";
        c.carYear = "2019";
        c.carSerial = "1";
        c.manufacturer = "Riposte Laboratories";
        c.model = "GT6-EAU";
        c.swBuild = "1";
        c.swVersion = "stage1";
        c.videoConfigs.add(new Messages.VideoConfig(Messages.RES_1920x1080, Messages.FPS_30, 0, MARGIN_ROWS, DECLARED_DPI));
        c.videoConfigs.add(new Messages.VideoConfig(Messages.RES_1280x720, Messages.FPS_30, 0, 0, DECLARED_DPI));
        Messages.VideoConfig first = c.videoConfigs.get(0);
        c.touchWidth = first.width();
        c.touchHeight = first.height();
        return c;
    }

    boolean isRunning() {
        return running;
    }

    /** Opens an accessory-mode phone and starts the session. */
    void start(UsbManager manager, UsbDevice device) {
        stop("restart");
        Pipe pipe;
        try {
            pipe = UsbLink.open(manager, device);
        } catch (IOException e) {
            status("usb: " + e.getMessage());
            return;
        }
        begin(pipe, "projection-usb");
    }

    /** A phone that came in over the wireless bootstrap and dialled the TCP port. */
    void start(Socket socket) {
        stop("restart");
        Pipe pipe;
        try {
            pipe = TcpTransport.wrap(socket);
        } catch (IOException e) {
            status("tcp: " + e.getMessage());
            return;
        }
        begin(pipe, "projection-tcp");
    }

    private void begin(Pipe pipe, String threadName) {
        link = pipe;
        citizen = MediaCitizen.attach(context, "projection", new Transport());
        session = new Session(link, this, config());
        running = true;

        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, threadName);
        reader.start();

        pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    session.ping();
                } catch (IOException e) {
                    log("ping: " + e.getMessage());
                }
            }
        }, PING_PERIOD_S, PING_PERIOD_S, TimeUnit.SECONDS);
    }

    void stop(String why) {
        if (!running) {
            return;
        }
        running = false;
        if (pinger != null) {
            pinger.shutdownNow();
            pinger = null;
        }
        if (reader != null) {
            reader.interrupt();
            reader = null;
        }
        video.stop();
        for (AudioSink a : audio) {
            if (a != null) {
                a.stop();
            }
        }
        if (citizen != null) {
            citizen.releaseFocus();
            citizen.release();
            citizen = null;
        }
        if (link != null) {
            link.close();
            link = null;
        }
        final String reason = why;
        main.post(new Runnable() {
            @Override
            public void run() {
                screen.onEnded(reason);
            }
        });
    }

    void setSurface(Surface surface) {
        video.setSurface(surface);
    }

    void touch(int action, int actionIndex, int[] xs, int[] ys, int[] ids) {
        if (session == null) {
            return;
        }
        try {
            session.sendTouch(action, actionIndex, xs, ys, ids);
        } catch (IOException e) {
            log("touch: " + e.getMessage());
        }
    }

    private void readLoop() {
        byte[] buf = new byte[READ_BUFFER];
        try {
            session.start();
            while (running && !Thread.currentThread().isInterrupted()) {
                int n = link.read(buf);
                if (n < 0) {
                    continue;   // timeout; a detach arrives as a broadcast, not here
                }
                session.onBytes(buf, 0, n);
                if (session.state() == Session.State.CLOSED) {
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "session: " + e, e);
            status("session: " + e.getMessage());
        }
        if (running) {
            stop("link closed");
        }
    }

    // ---- Session.Sink (reader thread) ----------------------------------------------------------

    @Override
    public void log(String line) {
        Log.i(TAG, line);
        status(line);
    }

    @Override
    public void onState(Session.State state) {
    }

    @Override
    public void onVideoStart(final Messages.VideoConfig config) {
        video.configure(config.width(), config.height());
        main.post(new Runnable() {
            @Override
            public void run() {
                screen.onVideoSize(config.width(), config.height());
            }
        });
    }

    @Override
    public void onVideo(long timestampUs, byte[] data, int off, int len) {
        video.feed(data, off, len, timestampUs);
    }

    @Override
    public void onVideoStop() {
        video.stop();
    }

    @Override
    public void onAudioStart(int channel, Messages.AudioConfig config) {
        AudioSink sink = audio[channel];
        if (sink != null) {
            sink.start(config);
        }
    }

    @Override
    public void onAudio(int channel, byte[] data, int off, int len) {
        AudioSink sink = audio[channel];
        if (sink != null) {
            sink.write(data, off, len);
        }
    }

    @Override
    public void onAudioStop(int channel) {
        AudioSink sink = audio[channel];
        if (sink != null) {
            sink.stop();
        }
    }

    /** openauto's mapping: RELEASE is a loss, everything else a gain. Focus goes through MediaCitizen. */
    @Override
    public int onAudioFocus(int focusType) {
        if (focusType == Messages.FOCUS_RELEASE) {
            citizen.releaseFocus();
            citizen.setIdle();
            return Messages.FOCUS_STATE_LOSS;
        }
        boolean granted = citizen.takeFocus(MediaCitizen.Focus.MEDIA);
        if (granted) {
            citizen.setMetadata("Android Auto", "", 0);
            citizen.setState(true, 0);
        }
        return granted ? Messages.FOCUS_STATE_GAIN : Messages.FOCUS_STATE_LOSS;
    }

    @Override
    public void onShutdown(String why) {
        stop(why);
    }

    private void status(final String line) {
        main.post(new Runnable() {
            @Override
            public void run() {
                screen.onStatus(line);
            }
        });
    }

    /** The wheel and the launcher's now-playing card. Stage 1 only ducks; keys go nowhere yet. */
    private final class Transport implements MediaCitizen.Transport {
        @Override
        public void onPlay() {
            log("media key: play (not forwarded in stage 1)");
        }

        @Override
        public void onPause() {
            log("media key: pause (not forwarded in stage 1)");
        }

        @Override
        public void onNext() {
            log("media key: next (not forwarded in stage 1)");
        }

        @Override
        public void onPrevious() {
            log("media key: previous (not forwarded in stage 1)");
        }

        @Override
        public void onStop() {
            log("media key: stop (not forwarded in stage 1)");
        }

        @Override
        public void onDuck(boolean duck) {
            float v = MediaCitizen.duckVolume(duck);
            for (AudioSink a : audio) {
                if (a != null) {
                    a.setVolume(v);
                }
            }
        }
    }
}
