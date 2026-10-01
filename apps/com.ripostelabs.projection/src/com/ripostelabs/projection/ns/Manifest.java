package com.ripostelabs.projection.ns;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model manifest the estate publishes (road-noise/CONTRACT.md section 5), the fields the
 * car needs from it:
 *
 * <pre>
 *   {"name": "rnnoise", "version": "2026-10-02.1",
 *    "files": [{"path": "2026-10-02.1/weights.bin", "sha256": "9f2c…", "bytes": 1553664}], …}
 * </pre>
 *
 * Written by ns-train/ns_report.py. Parsed by hand: org.json is a stub off the device, where
 * the tests run, and the keys read here appear once each. Anything that does not look exactly
 * right is refused, so a broken server cannot hand the car a bad path or size.
 *
 * A version "YYYY-MM-DD.N" becomes the number YYYYMMDD * 100 + N, which orders like the dates
 * and is what {@link ModelStore} keeps.
 */
public final class Manifest {

    /** A blob is 1.5 MB; anything far past that is not a model of this shape. */
    static final long MAX_SIZE = 8L * 1024 * 1024;
    private static final String NAME = "rnnoise";
    private static final int SHA256_HEX = 64;
    private static final int PER_DAY = 100;
    private static final Pattern VERSION = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})\\.(\\d{1,2})");
    private static final Pattern PATH =
            Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}/[A-Za-z0-9_-][A-Za-z0-9._-]{0,63}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{" + SHA256_HEX + "}");

    /** The version as a number that orders like the dates; see {@link #label}. */
    public final int version;
    /** The weight file, relative to the manifest's folder. */
    public final String path;
    public final String sha256;
    public final long size;

    private Manifest(int version, String path, String sha256, long size) {
        this.version = version;
        this.path = path;
        this.sha256 = sha256;
        this.size = size;
    }

    /** The manifest, or null when any field is missing or out of range. */
    public static Manifest parse(String json) {
        if (json == null) {
            return null;
        }
        String name = string(json, "name");
        String version = string(json, "version");
        String path = string(json, "path");
        String sha = string(json, "sha256");
        Long size = number(json, "bytes");
        if (version == null || path == null || sha == null || size == null) {
            return null;
        }
        if (name != null && !NAME.equals(name)) {
            return null;
        }

        int code = code(version);
        sha = sha.toLowerCase(Locale.ROOT);
        boolean ok = code > 0
                && PATH.matcher(path).matches() && path.startsWith(version + "/")
                && HEX.matcher(sha).matches()
                && size > 0 && size <= MAX_SIZE;
        if (!ok) {
            return null;
        }
        return new Manifest(code, path, sha, size);
    }

    /** "2026-10-02.1" -> 2026100201; 0 when it is not a version. */
    static int code(String version) {
        Matcher m = VERSION.matcher(version);
        if (!m.matches()) {
            return 0;
        }
        int n = Integer.parseInt(m.group(4));
        if (n < 1) {
            return 0;
        }
        int day = Integer.parseInt(m.group(1) + m.group(2) + m.group(3));
        return day * PER_DAY + n;
    }

    /** 2026100201 -> "2026-10-02.1", for the settings screen and the log. */
    public static String label(int code) {
        String day = Integer.toString(code / PER_DAY);
        if (day.length() != 8) {
            return Integer.toString(code);
        }
        return day.substring(0, 4) + "-" + day.substring(4, 6) + "-" + day.substring(6) + "." + code % PER_DAY;
    }

    private static String string(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static Long number(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d{1,18})\\b").matcher(json);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }
}
