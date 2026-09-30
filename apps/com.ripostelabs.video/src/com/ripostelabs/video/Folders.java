package com.ripostelabs.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The folder level of the video list: videos grouped by the folder their file sits in, each
 * marked internal or USB. Pure logic so the harness can test it.
 *
 * Stock parity: videoplayer/ui/FilelistFragmentUILandscape.java browsed folders. A copy of the
 * music app's Folders: the two apps share no package, and this stays package-private.
 * The key is the full folder path, so "Rock" on the stick and "Rock" on internal storage stay
 * two folders.
 */
final class Folders {

    /** Internal storage, or anything else mounted (a USB stick, an SD card). */
    enum Where {
        INTERNAL,
        USB,
    }

    static final class Folder {
        final String path;
        final String name;
        final Where where;
        /** Rows of the video list in this folder, in list order. */
        final int[] rows;

        Folder(String path, String name, Where where, int[] rows) {
            this.path = path;
            this.name = name;
            this.where = where;
            this.rows = rows;
        }
    }

    private static final String[] INTERNAL_ROOTS = {"/storage/emulated/", "/sdcard/"};

    private Folders() {
    }

    static Where where(String path) {
        for (String root : INTERNAL_ROOTS) {
            if (path.startsWith(root)) {
                return Where.INTERNAL;
            }
        }
        return Where.USB;
    }

    /** Group rows by folder. A row without a usable path is left out of the folder view. */
    static List<Folder> group(String[] paths) {
        Map<String, List<Integer>> byDir = new LinkedHashMap<>();
        for (int i = 0; i < paths.length; i++) {
            String p = paths[i];
            int slash = p == null ? -1 : p.lastIndexOf('/');
            if (slash <= 0) {
                continue;
            }
            String dir = p.substring(0, slash);
            if (!byDir.containsKey(dir)) {
                byDir.put(dir, new ArrayList<>());
            }
            byDir.get(dir).add(i);
        }

        List<Folder> out = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byDir.entrySet()) {
            String dir = e.getKey();
            int[] rows = new int[e.getValue().size()];
            for (int k = 0; k < rows.length; k++) {
                rows[k] = e.getValue().get(k);
            }
            out.add(new Folder(dir, dir.substring(dir.lastIndexOf('/') + 1), where(dir + "/"), rows));
        }

        Collections.sort(out, (a, b) -> {
            int byName = a.name.compareToIgnoreCase(b.name);
            if (byName != 0) {
                return byName;
            }
            int byWhere = a.where.compareTo(b.where);
            return byWhere != 0 ? byWhere : a.path.compareTo(b.path);
        });
        return out;
    }
}
