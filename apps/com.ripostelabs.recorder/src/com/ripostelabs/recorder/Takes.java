package com.ripostelabs.recorder;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Naming and pairing of the files in the list. A noise reduction take is two files with one
 * base name, e.g. "Highway raw.wav" and "Highway cleaned.wav"; the list shows them as one take
 * that plays raw, then cleaned. Everything else (a voice memo, a lone half) is a take of one.
 */
final class Takes {

    static final String RAW_SUFFIX = " raw.wav";
    static final String CLEANED_SUFFIX = " cleaned.wav";

    /** What a file name may not hold; each becomes "_". */
    private static final String UNSAFE = "[\\\\/:*?\"<>|]";

    private Takes() {
    }

    /** One row in the list: a single file, or a raw/cleaned pair. */
    static final class Take {
        final String name;
        final File first;
        /** The cleaned half of a pair, played after {@link #first}; null for a single file. */
        final File second;

        Take(String name, File first, File second) {
            this.name = name;
            this.first = first;
            this.second = second;
        }
    }

    static String rawName(String base) {
        return base + RAW_SUFFIX;
    }

    static String cleanedName(String base) {
        return base + CLEANED_SUFFIX;
    }

    /** "NR_1 raw.wav" and "NR_1 cleaned.wav" both give "NR_1"; anything else null. */
    static String pairBase(String fileName) {
        for (String suffix : new String[]{RAW_SUFFIX, CLEANED_SUFFIX}) {
            if (fileName.endsWith(suffix) && fileName.length() > suffix.length()) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return null;
    }

    /** Group files, kept in their given order (a pair sits where its first half did). */
    static List<Take> group(List<File> files) {
        Map<String, File[]> pairs = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (File f : files) {
            String base = pairBase(f.getName());
            if (base == null) {
                order.add(f);
                continue;
            }
            File[] halves = pairs.get(base);
            if (halves == null) {
                halves = new File[2];
                pairs.put(base, halves);
                order.add(base);
            }
            halves[f.getName().endsWith(RAW_SUFFIX) ? 0 : 1] = f;
        }

        List<Take> takes = new ArrayList<>();
        for (Object o : order) {
            if (o instanceof File) {
                File f = (File) o;
                takes.add(new Take(stripExtension(f.getName()), f, null));
                continue;
            }
            File[] halves = pairs.get(o);
            if (halves[0] != null && halves[1] != null) {
                takes.add(new Take((String) o, halves[0], halves[1]));
                continue;
            }
            File lone = halves[0] != null ? halves[0] : halves[1];
            takes.add(new Take(stripExtension(lone.getName()), lone, null));
        }
        return takes;
    }

    /**
     * Rename both halves of a pair to a chosen base, numbered if taken. Returns the base used;
     * the old one if the name is empty or a rename fails.
     */
    static String renamePair(File raw, File cleaned, String chosen) {
        String old = pairBase(raw.getName());
        if (chosen == null || chosen.trim().isEmpty()) {
            return old;
        }

        String safe = chosen.trim().replaceAll(UNSAFE, "_");
        File dir = raw.getParentFile();
        String base = safe;
        for (int i = 2; taken(dir, base, raw, cleaned); i++) {
            base = safe + " (" + i + ")";
        }

        File newRaw = new File(dir, rawName(base));
        File newCleaned = new File(dir, cleanedName(base));
        if (!raw.renameTo(newRaw)) {
            return old;
        }
        if (!cleaned.renameTo(newCleaned)) {
            // Keep the pair together: put the raw half back.
            newRaw.renameTo(raw);
            return old;
        }
        return base;
    }

    private static boolean taken(File dir, String base, File raw, File cleaned) {
        File r = new File(dir, rawName(base));
        File c = new File(dir, cleanedName(base));
        return (r.exists() && !r.equals(raw)) || (c.exists() && !c.equals(cleaned));
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
