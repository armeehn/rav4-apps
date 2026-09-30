package com.ripostelabs.projection.zlink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The daemon's metadata port: an 8-byte little-endian header, then for CarPlay an iAP2
 * NowPlayingUpdate parameter list. Vectors are built by hand from that layout (the OEM gps
 * app's {@code ZLinkSocket} reader is the only record of it; no capture exists yet).
 */
public final class MetadataTest {

    private static final int GROUP_ITEM = 0;
    private static final int GROUP_PLAYBACK = 1;

    public static void main(String[] args) throws Exception {
        literalVector();
        fullUpdate();
        mergesIncrementalUpdates();
        truncatedParameter();
        shortParameterStops();
        streamReader();
        linkDeliversOverTcp();
        System.out.println(Check.count + " assertions passed");
    }

    /** Title "Hi" alone: {@code 0a000000 0b000000 | 000b 0000 | 0007 0001 48 69 00}. */
    private static void literalVector() throws IOException {
        byte[] wire = Check.hex("0a 00 00 00 0b 00 00 00 00 0b 00 00 00 07 00 01 48 69 00");
        Metadata.Frame f = Metadata.read(new ByteArrayInputStream(wire));
        Check.eq(Metadata.CP_MEDIA_INFO, f.id, "id");
        Check.eq(11, f.body.length, "body length");

        Metadata.NowPlaying np = Metadata.nowPlaying(f.body);
        Check.eq("Hi", np.title, "title, NUL stripped");
        Check.that(np.artist == null, "artist absent");
        Check.eq(Metadata.UNKNOWN, np.durationMs, "duration absent");
        Check.eq(Metadata.UNKNOWN, np.status, "status absent");
    }

    private static void fullUpdate() {
        byte[] item = concat(
                param(1, text("Hey Jude")),
                param(4, u32(431_000)),
                param(6, text("1")),
                param(12, text("The Beatles")));
        byte[] playback = concat(
                param(0, new byte[]{Metadata.STATUS_PLAYING}),
                param(1, u32(12_345)));
        byte[] body = concat(param(GROUP_ITEM, item), param(GROUP_PLAYBACK, playback));

        Metadata.NowPlaying np = Metadata.nowPlaying(body);
        Check.eq("Hey Jude", np.title, "title");
        Check.eq("The Beatles", np.artist, "artist");
        Check.eq("1", np.album, "album");
        Check.eq(431_000, np.durationMs, "duration");
        Check.eq(Metadata.STATUS_PLAYING, np.status, "status");
        Check.eq(12_345, np.elapsedMs, "elapsed");
        Check.that(np.playing(), "playing");
    }

    /** The phone sends the track once, then playback alone; the card must keep the title. */
    private static void mergesIncrementalUpdates() {
        Metadata.NowPlaying state = new Metadata.NowPlaying();
        state.apply(Metadata.nowPlaying(param(GROUP_ITEM, concat(
                param(1, text("Song")), param(12, text("Artist"))))));
        state.apply(Metadata.nowPlaying(param(GROUP_PLAYBACK, concat(
                param(0, new byte[]{Metadata.STATUS_PAUSED}), param(1, u32(5_000))))));

        Check.eq("Song", state.title, "title kept");
        Check.eq("Artist", state.artist, "artist kept");
        Check.eq(5_000, state.elapsedMs, "elapsed taken");
        Check.that(!state.playing(), "paused");
    }

    /** A length running past the body ends the walk; what came before still counts. */
    private static void truncatedParameter() {
        byte[] good = param(1, text("Kept"));
        byte[] bad = Check.hex("00 40 00 0c 41");
        Metadata.NowPlaying np = Metadata.nowPlaying(param(GROUP_ITEM, concat(good, bad)));
        Check.eq("Kept", np.title, "title before the bad parameter");
        Check.that(np.artist == null, "bad artist dropped");
    }

    /** A length under the 4-byte parameter header would never advance; it must stop instead. */
    private static void shortParameterStops() {
        Metadata.NowPlaying np = Metadata.nowPlaying(Check.hex("00 00 00 00 00 00 00 00"));
        Check.that(np.title == null, "nothing decoded");
    }

    private static void streamReader() throws IOException {
        byte[] one = frame(Metadata.CP_PHONE_STATE, Check.hex("00 05 00 00 31"));
        byte[] two = frame(Metadata.CP_MEDIA_INFO, param(GROUP_ITEM, param(1, text("B"))));
        ByteArrayInputStream in = new ByteArrayInputStream(concat(one, two));

        Check.eq(Metadata.CP_PHONE_STATE, Metadata.read(in).id, "first id");
        Check.eq(Metadata.CP_MEDIA_INFO, Metadata.read(in).id, "second id");
        Check.that(Metadata.read(in) == null, "clean end of stream");
    }

    /** The link dials the daemon, skips other ids, and hands over the merged track. */
    private static void linkDeliversOverTcp() throws Exception {
        BlockingQueue<Metadata.NowPlaying> got = new ArrayBlockingQueue<>(4);
        try (ServerSocket daemon = new ServerSocket(0)) {
            MetadataLink link = new MetadataLink("127.0.0.1", daemon.getLocalPort(), new MetadataLink.Listener() {
                @Override
                public void onNowPlaying(Metadata.NowPlaying np) {
                    got.offer(np);
                }

                @Override
                public void onLog(String line) {
                }
            });
            link.start();

            try (Socket s = daemon.accept()) {
                OutputStream out = s.getOutputStream();
                out.write(frame(Metadata.CP_PHONE_STATE, Check.hex("00 05 00 00 31")));
                out.write(frame(Metadata.CP_MEDIA_INFO, param(GROUP_ITEM, param(1, text("Over TCP")))));
                out.flush();

                Metadata.NowPlaying np = got.poll(5, TimeUnit.SECONDS);
                Check.that(np != null, "delivered");
                Check.eq("Over TCP", np.title, "title over the socket");
            } finally {
                link.stop();
            }
        }
    }

    // ---- builders --------------------------------------------------------------------------

    private static byte[] frame(int id, byte[] body) {
        byte[] out = new byte[Metadata.HEADER_LEN + body.length];
        le32(out, 0, id);
        le32(out, 4, body.length);
        System.arraycopy(body, 0, out, Metadata.HEADER_LEN, body.length);
        return out;
    }

    /** iAP2 parameter: u16 length including this header, u16 id, data; big-endian. */
    private static byte[] param(int id, byte[] data) {
        int len = 4 + data.length;
        byte[] out = new byte[len];
        out[0] = (byte) (len >> 8);
        out[1] = (byte) len;
        out[2] = (byte) (id >> 8);
        out[3] = (byte) id;
        System.arraycopy(data, 0, out, 4, data.length);
        return out;
    }

    /** iAP2 UTF-8 strings carry a trailing NUL. */
    private static byte[] text(String s) {
        return concat(s.getBytes(StandardCharsets.UTF_8), new byte[]{0});
    }

    private static byte[] u32(int v) {
        return new byte[]{(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
    }

    private static void le32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }
}
