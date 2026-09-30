package com.ripostelabs.music;

import java.util.List;

/**
 * Where a track's cover comes from when it has no embedded picture, and how far a large
 * picture is scaled down before it is decoded. A wrong answer shows no art for a folder that
 * has a cover.jpg, or decodes a 3000 px scan at full size on a head unit with little memory.
 */
public final class CoverArtTest {

    public static void main(String[] args) {
        // A track on a stick: the files beside it, in the order stock and most rippers use.
        List<String> c = CoverArt.siblings("/storage/102D-17F0/Music/Jazz/charlie.mp3");
        expect("/storage/102D-17F0/Music/Jazz/folder.jpg", c.get(0));
        expect("/storage/102D-17F0/Music/Jazz/cover.jpg", c.get(1));
        expectTrue(c.contains("/storage/102D-17F0/Music/Jazz/Folder.jpg"));
        expectTrue(c.contains("/storage/102D-17F0/Music/Jazz/AlbumArt.jpg"));

        // No usable folder: nothing to look for.
        expectTrue(CoverArt.siblings(null).isEmpty());
        expectTrue(CoverArt.siblings("charlie.mp3").isEmpty());

        // Scale: halve until the picture fits the target on its longer side, never below 1.
        expect(1, CoverArt.sampleSize(300, 300, 512));
        expect(1, CoverArt.sampleSize(512, 400, 512));
        expect(2, CoverArt.sampleSize(1000, 1000, 512));
        expect(8, CoverArt.sampleSize(3000, 2000, 512));
        expect(1, CoverArt.sampleSize(0, 0, 512));

        System.out.println("CoverArtTest: ok");
    }

    private static void expectTrue(boolean got) {
        if (!got) {
            throw new AssertionError("want true");
        }
    }

    private static void expect(Object want, Object got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
