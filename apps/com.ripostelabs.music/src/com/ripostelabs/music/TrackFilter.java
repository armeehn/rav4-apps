package com.ripostelabs.music;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Search and favourites over the track list (RAV4-179).
 *
 * Both work on row indices inside the current view, so they stack on the folder level:
 *
 * <pre>
 *   all tracks ─┐
 *   favourites ─┼─▶ base rows ─▶ search(query) ─▶ the rows the list shows
 *   one folder ─┘
 * </pre>
 *
 * Stock keeps favourites in a list file ({@code FavoriteListUtil}) and searches by name
 * ({@code SearchListUtil}). Ours keys a star by content URI, which survives a rescan, and
 * matches title, artist or album.
 */
final class TrackFilter {

    private TrackFilter() {
    }

    /** The searchable tags of one track and its URI. Missing tags are null. */
    static final class Row {
        final String title;
        final String artist;
        final String album;
        final String uri;

        Row(String title, String artist, String album, String uri) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.uri = uri;
        }
    }

    /**
     * The rows of {@code base} whose tags contain every word of {@code query}, ignoring case
     * and accents. "miles what" finds "So What" by Miles Davis.
     */
    static int[] search(Row[] rows, int[] base, String query) {
        String[] words = fold(query).trim().split("\\s+");
        if (words.length == 1 && words[0].isEmpty()) {
            return base;
        }

        int[] out = new int[base.length];
        int n = 0;
        for (int i : base) {
            if (matches(rows[i], words)) {
                out[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** The rows of {@code base} whose URI is starred, in list order. */
    static int[] starred(Row[] rows, int[] base, Set<String> stars) {
        int[] out = new int[base.length];
        int n = 0;
        for (int i : base) {
            if (stars.contains(rows[i].uri)) {
                out[n++] = i;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** A copy of {@code stars} with {@code uri} flipped. SharedPreferences sets must not be edited in place. */
    static Set<String> toggle(Set<String> stars, String uri) {
        Set<String> next = new HashSet<>(stars);
        if (!next.remove(uri)) {
            next.add(uri);
        }
        return next;
    }

    private static boolean matches(Row r, String[] words) {
        String hay = fold(r.title) + "\n" + fold(r.artist) + "\n" + fold(r.album);
        for (String w : words) {
            if (!hay.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** Lower case without accents: "Équinoxe" reads as "equinoxe". */
    private static String fold(String s) {
        if (s == null) {
            return "";
        }
        String bare = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return bare.toLowerCase(Locale.ROOT);
    }
}
