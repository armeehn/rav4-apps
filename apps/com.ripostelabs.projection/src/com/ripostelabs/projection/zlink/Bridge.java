package com.ripostelabs.projection.zlink;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.ripostelabs.projection.aa.Proto;

import java.io.IOException;
import java.util.Locale;

/**
 * The app side of the daemon's four channels: answers its session machine, feeds its media
 * into the decoders and carries the panel's touches back. One instance lives in
 * {@link com.ripostelabs.projection.ZlinkService} for as long as the unit is up, because the
 * daemon dials in from boot and keeps redialling until someone answers.
 *
 * <pre>
 *   daemon ──1777──▶ control  ──▶ SessionState / MFi / focus ──▶ Screen callbacks
 *          ◀────────           ◀── InitInfo, heartbeat echo, touch, keys
 *   daemon ──1888──▶ video    ──▶ H.264 access units ──▶ VideoSink (MediaCodec on the surface)
 *   daemon ──1666──▶ audio    ──▶ PCM ──▶ AudioSink
 *   daemon ──1999──▶ bluetooth ◀─▶ the phone's RFCOMM bytes, relayed raw (wireless bootstrap)
 * </pre>
 *
 * <p>The audio and video payload layouts are still being learned on the bench: until the first
 * session is captured, both channels log every frame's id, length and head bytes, and video
 * frames that start with an Annex-B start code go to the decoder as they are.
 */
public final class Bridge implements FoxServer.Listener {

    /** What the screen shows; called on the main thread. */
    public interface Screen {
        void onStatus(String line);

        void onSessionState(int state, int linkType);

        void onVideoSize(int width, int height);
    }

    /** Media consumers, on the reader threads. */
    public interface Media {
        void onVideoSize(int width, int height);

        void onVideo(byte[] data, int off, int len, long timestampUs);

        void onAudioFormat(int sampleRate, int channels);

        void onAudio(byte[] data, int off, int len);
    }

    /** Session edges for whoever tells the rest of the unit; main thread. */
    public interface Session {
        void onSession(boolean up, int linkType);

        /** The phone's call picture changed. */
        void onCallState(Messages.CallState state);

        /** The daemon wants the cabin microphone in this format, or no longer. */
        void onMic(Messages.MicStart format);

        void onMicStop();
    }

    /** The wireless bootstrap, on the reader threads: the hotspot and the phone's RFCOMM link. */
    public interface Wireless {
        /** The daemon wants the access point; answer with {@link #apUp} once it is up. */
        void onApInfoRequested();

        /** Bytes the daemon wants written to the phone's RFCOMM socket. */
        void onBtDataToPhone(byte[] data);

        /** The session moved to Wi-Fi; the RFCOMM link is no longer wanted. */
        void onBtRelease();
    }

    private static final String TAG = "Projection";
    private static final int LOG_FIRST_FRAMES = 12;
    private static final int HEAD_BYTES = 32;
    private static final byte[] ANNEX_B = {0, 0, 0, 1};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final FoxServer control = new FoxServer("control", Fox.PORT_CONTROL, this);
    private final FoxServer audio = new FoxServer("audio", Fox.PORT_AUDIO, this);
    private final FoxServer video = new FoxServer("video", Fox.PORT_VIDEO, this);
    private final FoxServer bluetooth = new FoxServer("bluetooth", Fox.PORT_BLUETOOTH, this);
    private final Messages.InitInfo init;
    private volatile Screen screen;
    private volatile Media media;
    private volatile Wireless wireless;
    private volatile Session session;
    private boolean sessionUp;
    private volatile String btLocalMac;
    private volatile String btService;
    private int videoFramesLogged;
    private int audioFramesLogged;
    private int state;
    private long videoFrames;
    private int videoWidth;
    private int videoHeight;
    private int audioRate;
    private int audioChannels;

    public Bridge(Messages.InitInfo init) {
        this.init = init;
    }

    public void setScreen(Screen s) {
        screen = s;
    }

    public void setMedia(Media m) {
        media = m;
    }

    public void setWireless(Wireless w) {
        wireless = w;
    }

    public void setSession(Session s) {
        session = s;
    }

    public void start() throws IOException {
        control.start();
        audio.start();
        video.start();
        bluetooth.start();
        Log.i(TAG, "zlink bridge listening");
    }

    public void stop() {
        control.stop();
        audio.stop();
        video.stop();
        bluetooth.stop();
    }

    public boolean isDaemonUp() {
        return control.isConnected();
    }

    public int state() {
        return state;
    }

    // ---- to the daemon -----------------------------------------------------------------------

    public void touch(int x, int y, boolean down) {
        control.send(Messages.TOUCH, Messages.touch(x, y, down));
    }

    public void key(int keyCode, boolean down) {
        control.send(Messages.KEY, Messages.key(keyCode, down));
    }

    public void night(boolean on) {
        int id = on ? Messages.NIGHT_START : Messages.NIGHT_STOP;
        control.send(id, Messages.idOnly(id));
    }

    public void requestMfiInfo() {
        control.send(Messages.MFI_INFO_REQUEST, Messages.idOnly(Messages.MFI_INFO_REQUEST));
    }

    public void stopSession() {
        control.send(Messages.STOP, Messages.idOnly(Messages.STOP));
    }

    /** One frame of cabin microphone PCM, in the format the daemon asked for. */
    public void mic(int sampleRate, int channels, int bits, byte[] pcm, int len) {
        if (Messages.MIC_DATA == 0) {
            return;
        }
        control.send(Messages.MIC_DATA, Messages.micData(sampleRate, channels, bits, pcm, len));
    }

    // ---- wireless bootstrap ------------------------------------------------------------------

    /** The phone's RFCOMM link is open on the given service; the daemon takes it from here. */
    public void btConnected(String localMac, String serviceHex) {
        btLocalMac = localMac;
        btService = serviceHex;
        bluetooth.send(Messages.BT_INFO, Messages.btInfo(localMac, serviceHex, true));
    }

    /** Bytes the phone sent; an empty body would close the channel, so those are dropped. */
    public void btData(byte[] data, int off, int len) {
        if (len <= 0) {
            return;
        }
        byte[] body = new byte[len];
        System.arraycopy(data, off, body, 0, len);
        bluetooth.send(Messages.BT_DATA, body);
    }

    public void btDisconnected() {
        btService = null;
        bluetooth.send(Messages.BT_DISCONNECTED, Messages.idOnly(Messages.BT_DISCONNECTED));
    }

    public void apUp(String ssid, String passphrase, int band, String iface) {
        control.send(Messages.AP_STATE, Messages.apState(true));
        control.send(Messages.AP_INFO, Messages.apInfo(ssid, passphrase, band, iface));
    }

    public void apDown() {
        control.send(Messages.AP_STATE, Messages.apState(false));
    }

    // ---- from the daemon ---------------------------------------------------------------------

    @Override
    public void onConnected(FoxServer server) {
        status(server.name + " link up");
    }

    @Override
    public void onClosed(FoxServer server) {
        status(server.name + " link down");
        if (server == control) {
            if (sessionUp) {
                sessionUp = false;
                final Session l = session;
                if (l != null) {
                    main.post(() -> l.onSession(false, 0));
                }
            }
            state = 0;
            videoFramesLogged = 0;
            audioFramesLogged = 0;
            videoWidth = 0;
            videoHeight = 0;
            audioRate = 0;
            audioChannels = 0;
        }
    }

    @Override
    public void onFrame(FoxServer server, Fox.Frame f) {
        if (server == control) {
            onControl(f);
        } else if (server == video) {
            onVideoFrame(f);
        } else if (server == audio) {
            onAudioFrame(f);
        } else {
            onBluetooth(f);
        }
    }

    private void onBluetooth(Fox.Frame f) {
        switch (f.id) {
            case Messages.BT_INFO_REQUEST:
                // Answered once a phone link exists; before that there is nothing to say.
                if (btService != null) {
                    bluetooth.send(Messages.BT_INFO, Messages.btInfo(btLocalMac, btService, true));
                }
                return;
            case Messages.BT_DATA:
                Wireless w = wireless;
                if (w != null) {
                    w.onBtDataToPhone(f.payload);
                }
                return;
            case Messages.BT_RELEASE:
                Wireless r = wireless;
                if (r != null) {
                    r.onBtRelease();
                }
                return;
            default:
                Log.d(TAG, "bluetooth id=0x" + Integer.toHexString(f.id) + " len=" + f.payload.length);
        }
    }

    private void onControl(Fox.Frame f) {
        switch (f.id) {
            case Messages.HEARTBEAT:
                // One way: the daemon logs an echo as an unhandled id.
                return;
            case Messages.SESSION_STATE:
                Messages.SessionState s = Messages.sessionState(f.payload);
                onState(s.state, s.linkType);
                return;
            case Messages.DAEMON_VERSION:
                Log.i(TAG, "daemon " + Messages.describe(f.payload));
                return;
            case Messages.MFI_INFO:
                status("mfi " + Messages.describe(f.payload));
                return;
            case Messages.AP_INFO_REQUEST:
                Wireless w = wireless;
                if (w != null) {
                    w.onApInfoRequested();
                }
                return;
            case Messages.MIC_START:
                if (Messages.MIC_START != 0) {
                    final Messages.MicStart ms = Messages.micStart(f.payload);
                    final Session sl = session;
                    if (sl != null) {
                        main.post(() -> sl.onMic(ms));
                    }
                    return;
                }
                break;
            case Messages.CALL_STATE:
                final Messages.CallState c = Messages.callState(f.payload);
                final Session l = session;
                if (l != null) {
                    main.post(() -> l.onCallState(c));
                }
                return;
            default:
                status(String.format(Locale.ROOT, "control 0x%x %s", f.id, Messages.describe(f.payload)));
        }
    }

    /** The daemon's session machine; WAIT_INIT is the cue to describe the head unit. */
    private void onState(int newState, int linkType) {
        state = newState;
        status("session state " + newState + " link " + linkType);
        if (newState == Messages.STATE_WAIT_INIT) {
            control.send(Messages.INIT_INFO, Messages.initInfo(init));
            requestMfiInfo();
        }
        final Screen s = screen;
        if (s != null) {
            main.post(() -> s.onSessionState(newState, linkType));
        }
        boolean up = newState == Messages.STATE_SESSION;
        if (up != sessionUp) {
            sessionUp = up;
            final Session l = session;
            if (l != null) {
                main.post(() -> l.onSession(up, linkType));
            }
        }
    }

    private void onVideoFrame(Fox.Frame f) {
        if (videoFramesLogged < LOG_FIRST_FRAMES) {
            videoFramesLogged++;
            Log.i(TAG, "video id=0x" + Integer.toHexString(f.id) + " len=" + f.payload.length
                    + " head=" + hex(f.payload, HEAD_BYTES));
        }
        Media m = media;
        if (m == null) {
            return;
        }
        if (f.id == Messages.VIDEO_FRAME && f.payload.length > Messages.VIDEO_HEADER_LEN) {
            int w = u32(f.payload, 0);
            int h = u32(f.payload, 4);
            if (w != videoWidth || h != videoHeight) {
                videoWidth = w;
                videoHeight = h;
                m.onVideoSize(w, h);
                final Screen s = screen;
                if (s != null) {
                    main.post(() -> s.onVideoSize(w, h));
                }
            }
            videoFrames++;
            m.onVideo(f.payload, Messages.VIDEO_HEADER_LEN, f.payload.length - Messages.VIDEO_HEADER_LEN,
                    videoFrames * 1_000_000L / init.fps);
            return;
        }
        if (startsWith(f.payload, ANNEX_B)) {
            videoFrames++;
            m.onVideo(f.payload, 0, f.payload.length, videoFrames * 1_000_000L / init.fps);
            return;
        }
        // A small protobuf frame before the stream: width and height are fields 2 and 3 by the
        // convention every other message here follows; proven or corrected by the first capture.
        if (f.payload.length < 64) {
            int[] wh = widthHeight(f.payload);
            if (wh != null) {
                m.onVideoSize(wh[0], wh[1]);
                final Screen s = screen;
                if (s != null) {
                    main.post(() -> s.onVideoSize(wh[0], wh[1]));
                }
            }
        }
    }

    private void onAudioFrame(Fox.Frame f) {
        if (audioFramesLogged < LOG_FIRST_FRAMES) {
            audioFramesLogged++;
            Log.i(TAG, "audio id=0x" + Integer.toHexString(f.id) + " len=" + f.payload.length
                    + " head=" + hex(f.payload, HEAD_BYTES));
        }
        Media m = media;
        if (m == null || f.id != Messages.AUDIO_FRAME || f.payload.length <= Messages.AUDIO_HEADER_LEN) {
            return;
        }
        int rate = u32(f.payload, 0);
        int channels = u32(f.payload, 4);
        if (rate != audioRate || channels != audioChannels) {
            audioRate = rate;
            audioChannels = channels;
            m.onAudioFormat(rate, channels);
        }
        m.onAudio(f.payload, Messages.AUDIO_HEADER_LEN, f.payload.length - Messages.AUDIO_HEADER_LEN);
    }

    private static int[] widthHeight(byte[] payload) {
        int w = 0;
        int h = 0;
        try {
            Proto.Reader r = new Proto.Reader(payload);
            while (r.next()) {
                if (r.wire() != Proto.WIRE_VARINT) {
                    return null;
                }
                long v = r.varint();
                if (r.field() == 2) {
                    w = (int) v;
                } else if (r.field() == 3) {
                    h = (int) v;
                }
            }
        } catch (RuntimeException e) {
            return null;
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        return new int[] {w, h};
    }

    private static int u32(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(String.format(Locale.ROOT, "%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    private void status(final String line) {
        Log.i(TAG, "zlink: " + line);
        final Screen s = screen;
        if (s != null) {
            main.post(() -> s.onStatus(line));
        }
    }
}
