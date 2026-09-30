package com.ripostelabs.video;

import java.util.List;

/**
 * The folder level of the video list. A wrong answer merges two folders that share a name on
 * different volumes, loses a video whose path is unknown, or calls a USB stick internal.
 */
public final class FoldersTest {

    public static void main(String[] args) {
        // Rows arrive newest first, so one folder's videos are interleaved with another's.
        String[] paths = {
                "/storage/102D-17F0/Music/Jazz/charlie.mp3",
                "/storage/emulated/0/Music/Rock/alpha.mp3",
                "/storage/102D-17F0/Music/Jazz/delta.mp3",
                "/storage/emulated/0/Music/Rock/bravo.mp3",
                "/storage/102D-17F0/Rock/echo.mp3",
                null,
        };
        List<Folders.Folder> f = Folders.group(paths);

        // Sorted by name, then internal before USB: Jazz, Rock (internal), Rock (USB).
        expect(3, f.size());
        expect("Jazz", f.get(0).name);
        expect(Folders.Where.USB, f.get(0).where);
        expectRows(new int[]{0, 2}, f.get(0).rows);

        expect("Rock", f.get(1).name);
        expect(Folders.Where.INTERNAL, f.get(1).where);
        expectRows(new int[]{1, 3}, f.get(1).rows);

        expect("Rock", f.get(2).name);
        expect(Folders.Where.USB, f.get(2).where);
        expectRows(new int[]{4}, f.get(2).rows);

        // A file at the volume root still has a folder: the volume itself.
        List<Folders.Folder> root = Folders.group(new String[]{"/storage/102D-17F0/a.mp3"});
        expect("102D-17F0", root.get(0).name);

        // The legacy /sdcard alias is internal storage too.
        expect(Folders.Where.INTERNAL, Folders.where("/sdcard/Music/a.mp3"));
        expect(Folders.Where.USB, Folders.where("/mnt/media_rw/usb/a.mp3"));

        // Nothing loaded.
        expect(0, Folders.group(new String[0]).size());

        System.out.println("FoldersTest: ok");
    }

    private static void expectRows(int[] want, int[] got) {
        if (!java.util.Arrays.equals(want, got)) {
            throw new AssertionError("want " + java.util.Arrays.toString(want)
                    + ", got " + java.util.Arrays.toString(got));
        }
    }

    private static void expect(Object want, Object got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
