package com.ripostelabs.projection.zlink;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The daemon's metadata port. Unlike the Fox channels the daemon is the <em>server</em> here:
 * it listens on 1555 (backlog 1) and whoever dials in gets what the phone says about itself.
 * On the stock unit that was the vendor gps app ({@code com.zxw.lib.ui.zlink.ZLinkSocket});
 * on Riposte OS 0.2 it is this app. Every message is
 *
 * <pre>
 *   u32 message id (LE) | u32 body length (LE) | body
 * </pre>
 *
 * Ids 1..5 are Android Auto (protobuf), 6..10 CarPlay. A CarPlay body is the iAP2 message's
 * parameter list as the phone sent it: each parameter is {@code u16 length | u16 id | data},
 * big-endian, the length counting its own four header bytes, and group parameters nest the
 * same shape. {@link #CP_MEDIA_INFO} is iAP2 NowPlayingUpdate:
 *
 * <pre>
 *   0 MediaItemAttributes   1 title  4 duration ms (u32)  6 album  12 artist   (strings UTF-8 + NUL)
 *   1 PlaybackAttributes    0 status (u8)  1 elapsed ms (u32)
 * </pre>
 *
 * Artwork is not on this port: the item carries only a file-transfer id (26) for a separate
 * iAP2 transfer the daemon keeps to itself.
 */
public final class Metadata {

    private Metadata() {
    }

    public static final int PORT = 1555;
    public static final int HEADER_LEN = 8;
    /** Bigger than any iAP2 message; a header claiming more is a lost sync. */
    public static final int MAX_BODY = 1024 * 1024;

    // ---- message ids ---------------------------------------------------------------------------
    public static final int AA_MEDIA_INFO = 5;
    public static final int CP_PHONE_STATE = 6;
    public static final int CP_COMM_STATE = 7;
    public static final int CP_MEDIA_INFO = 10;

    // ---- iAP2 NowPlayingUpdate parameters ------------------------------------------------------
    private static final int PARAM_HEADER_LEN = 4;
    private static final int GROUP_ITEM = 0;
    private static final int GROUP_PLAYBACK = 1;
    private static final int ITEM_TITLE = 1;
    private static final int ITEM_DURATION_MS = 4;
    private static final int ITEM_ALBUM = 6;
    private static final int ITEM_ARTIST = 12;
    private static final int PLAYBACK_STATUS = 0;
    private static final int PLAYBACK_ELAPSED_MS = 1;

    /** iAP2 PlaybackStatus. */
    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_PLAYING = 1;
    public static final int STATUS_PAUSED = 2;

    /** A number the update did not carry. */
    public static final int UNKNOWN = -1;

    /** One message off the socket. */
    public static final class Frame {
        public final int id;
        public final byte[] body;

        Frame(int id, byte[] body) {
            this.id = id;
            this.body = body;
        }
    }

    /** What the phone is playing. Null or {@link #UNKNOWN} means "not in this update". */
    public static final class NowPlaying {
        public String title;
        public String artist;
        public String album;
        public long durationMs = UNKNOWN;
        public int status = UNKNOWN;
        public long elapsedMs = UNKNOWN;

        public boolean playing() {
            return status == STATUS_PLAYING;
        }

        /** Fold an incremental update in: the phone resends only what changed. */
        public void apply(NowPlaying u) {
            if (u.title != null) {
                title = u.title;
            }
            if (u.artist != null) {
                artist = u.artist;
            }
            if (u.album != null) {
                album = u.album;
            }
            if (u.durationMs != UNKNOWN) {
                durationMs = u.durationMs;
            }
            if (u.status != UNKNOWN) {
                status = u.status;
            }
            if (u.elapsedMs != UNKNOWN) {
                elapsedMs = u.elapsedMs;
            }
        }

        public NowPlaying copy() {
            NowPlaying c = new NowPlaying();
            c.apply(this);
            return c;
        }
    }

    /** The next message, or null at a clean end of stream. */
    public static Frame read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] head = new byte[HEADER_LEN];

        // A close between messages is the daemon going away, not an error.
        int first = d.read();
        if (first < 0) {
            return null;
        }
        head[0] = (byte) first;
        d.readFully(head, 1, HEADER_LEN - 1);

        int id = le32(head, 0);
        int len = le32(head, 4);
        if (len < 0 || len > MAX_BODY) {
            throw new IOException("metadata body length " + len);
        }
        byte[] body = new byte[len];
        d.readFully(body);
        return new Frame(id, body);
    }

    /** Decode one CarPlay NowPlayingUpdate body. Malformed tails are dropped, never thrown. */
    public static NowPlaying nowPlaying(byte[] body) {
        NowPlaying np = new NowPlaying();
        walk(body, 0, body.length, (id, off, len) -> {
            if (id == GROUP_ITEM) {
                walk(body, off, len, (p, o, l) -> item(np, body, p, o, l));
            } else if (id == GROUP_PLAYBACK) {
                walk(body, off, len, (p, o, l) -> playback(np, body, p, o, l));
            }
        });
        return np;
    }

    private interface Visitor {
        void param(int id, int off, int len);
    }

    /** Visit each {@code u16 length | u16 id | data} in [off, off+len); stop at the first bad one. */
    private static void walk(byte[] b, int off, int len, Visitor v) {
        int end = off + len;
        while (off + PARAM_HEADER_LEN <= end) {
            int plen = be16(b, off);
            int id = be16(b, off + 2);

            // Under the header it would never advance; past the end it is a truncation.
            if (plen < PARAM_HEADER_LEN || off + plen > end) {
                return;
            }
            v.param(id, off + PARAM_HEADER_LEN, plen - PARAM_HEADER_LEN);
            off += plen;
        }
    }

    private static void item(NowPlaying np, byte[] b, int id, int off, int len) {
        switch (id) {
            case ITEM_TITLE:
                np.title = text(b, off, len);
                break;
            case ITEM_ALBUM:
                np.album = text(b, off, len);
                break;
            case ITEM_ARTIST:
                np.artist = text(b, off, len);
                break;
            case ITEM_DURATION_MS:
                np.durationMs = u32(b, off, len);
                break;
            default:
                break;
        }
    }

    private static void playback(NowPlaying np, byte[] b, int id, int off, int len) {
        if (id == PLAYBACK_STATUS && len >= 1) {
            np.status = b[off] & 0xff;
            return;
        }
        if (id == PLAYBACK_ELAPSED_MS) {
            np.elapsedMs = u32(b, off, len);
        }
    }

    /** UTF-8 without the NUL iAP2 puts after every string. */
    private static String text(byte[] b, int off, int len) {
        while (len > 0 && b[off + len - 1] == 0) {
            len--;
        }
        return new String(b, off, len, StandardCharsets.UTF_8);
    }

    private static long u32(byte[] b, int off, int len) {
        if (len < 4) {
            return UNKNOWN;
        }
        return ((long) be16(b, off) << 16) | be16(b, off + 2);
    }

    private static int be16(byte[] b, int off) {
        return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }
}
