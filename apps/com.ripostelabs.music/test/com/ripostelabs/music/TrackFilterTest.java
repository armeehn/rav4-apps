package com.ripostelabs.music;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Search and favourites over the track list. A wrong answer hides a track the driver typed
 * for, shows one they did not star, or loses the list order the rows arrived in.
 */
public final class TrackFilterTest {

    public static void main(String[] args) {
        TrackFilter.Row[] rows = {
                new TrackFilter.Row("Blue in Green", "Miles Davis", "Kind of Blue", "content://m/1"),
                new TrackFilter.Row("So What", "Miles Davis", "Kind of Blue", "content://m/2"),
                new TrackFilter.Row("Naima", "John Coltrane", "Giant Steps", "content://m/3"),
                new TrackFilter.Row("Équinoxe", null, null, "content://m/4"),
        };
        int[] all = {0, 1, 2, 3};

        // An empty query keeps every row, in order.
        expectRows(all, TrackFilter.search(rows, all, ""));
        expectRows(all, TrackFilter.search(rows, all, "   "));

        // Title, artist or album, any case.
        expectRows(new int[]{2}, TrackFilter.search(rows, all, "naima"));
        expectRows(new int[]{0, 1}, TrackFilter.search(rows, all, "MILES"));
        expectRows(new int[]{2}, TrackFilter.search(rows, all, "giant"));

        // Every word must match somewhere in the row.
        expectRows(new int[]{1}, TrackFilter.search(rows, all, "miles what"));
        expectRows(new int[0], TrackFilter.search(rows, all, "miles naima"));

        // Accents do not have to be typed; missing tags do not crash.
        expectRows(new int[]{3}, TrackFilter.search(rows, all, "equinoxe"));

        // Search runs inside the current view (a folder), not the whole library.
        expectRows(new int[]{2}, TrackFilter.search(rows, new int[]{2, 3}, "coltrane"));
        expectRows(new int[0], TrackFilter.search(rows, new int[]{0, 3}, "coltrane"));
        expectRows(new int[]{1}, TrackFilter.search(rows, new int[]{1, 2}, "kind"));

        // Favourites: starred URIs, in list order, skipping stars whose track is gone.
        Set<String> stars = new HashSet<>(Arrays.asList("content://m/3", "content://m/1", "content://m/9"));
        expectRows(new int[]{0, 2}, TrackFilter.starred(rows, all, stars));
        expectRows(new int[0], TrackFilter.starred(rows, all, new HashSet<>()));

        // A star toggles on and off without touching the caller's set.
        Set<String> once = TrackFilter.toggle(stars, "content://m/2");
        expect(true, once.contains("content://m/2"));
        expect(false, stars.contains("content://m/2"));
        expect(false, TrackFilter.toggle(once, "content://m/2").contains("content://m/2"));

        System.out.println("TrackFilterTest: ok");
    }

    private static void expectRows(int[] want, int[] got) {
        if (!Arrays.equals(want, got)) {
            throw new AssertionError("want " + Arrays.toString(want) + ", got " + Arrays.toString(got));
        }
    }

    private static void expect(Object want, Object got) {
        if (!want.equals(got)) {
            throw new AssertionError("want " + want + ", got " + got);
        }
    }
}
