package com.ripostelabs.music;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A track's cover: the picture embedded in its tags, else an image file beside it.
 *
 * Stock parity: zxwmediaplaylib/utils/FileBitmapUtils.java:171-172 read the embedded picture the
 * same way. The launcher's now-playing card and media screen draw whatever art the session
 * publishes, so this is also what the home screen shows.
 */
final class CoverArt {

    /** Longest side a cover is decoded at. The card draws it at 64 dp, the media screen larger. */
    static final int TARGET_PX = 512;

    /** Files a folder's cover goes by, most common first. Lookups are case-sensitive on FAT. */
    private static final String[] NAMES = {
            "folder.jpg", "cover.jpg", "Folder.jpg", "Cover.jpg", "AlbumArt.jpg", "front.jpg",
            "folder.png", "cover.png",
    };

    private CoverArt() {
    }

    /** The image files to try beside {@code track}, in order; empty without a folder. */
    static List<String> siblings(String track) {
        List<String> out = new ArrayList<>();
        int slash = track == null ? -1 : track.lastIndexOf('/');
        if (slash <= 0) {
            return out;
        }

        String dir = track.substring(0, slash + 1);
        for (String n : NAMES) {
            out.add(dir + n);
        }
        return out;
    }

    /** Power-of-two downscale so the longer side fits {@code target}; 1 means full size. */
    static int sampleSize(int width, int height, int target) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / sample > target) {
            sample *= 2;
        }
        return sample;
    }

    /** The cover for the track at {@code path}, or null. Blocking: call off the UI thread. */
    static Bitmap load(String path) {
        if (path == null) {
            return null;
        }

        byte[] embedded = null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            embedded = r.getEmbeddedPicture();
        } catch (Exception ignored) {
            // unreadable tags: fall through to the folder image
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        if (embedded != null) {
            return decode(embedded);
        }

        for (String f : siblings(path)) {
            if (new File(f).isFile()) {
                return decodeFile(f);
            }
        }
        return null;
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, TARGET_PX);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static Bitmap decodeFile(String file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file, o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, TARGET_PX);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file, o);
    }
}
