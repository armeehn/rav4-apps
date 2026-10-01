package com.ripostelabs.projection.ns;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model endpoint's manifest.json, the fields the car needs from it:
 *
 * <pre>
 *   {"version": 3, "file": "rnnoise-car-3.bin", "sha256": "9f2c…", "size": 1553664, …}
 * </pre>
 *
 * Written by ns-train/ns_report.py. Parsed by hand: the manifest is flat where these keys
 * live, and org.json is a stub off the device, where the tests run. Anything that does not
 * look exactly right is refused, so a broken server cannot hand the car a bad path or size.
 */
public final class Manifest {

    /** A blob is 1.5 MB; anything far past that is not a model of this shape. */
    static final long MAX_SIZE = 8L * 1024 * 1024;
    private static final int SHA256_HEX = 64;
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{" + SHA256_HEX + "}");

    public final int version;
    public final String file;
    public final String sha256;
    public final long size;

    private Manifest(int version, String file, String sha256, long size) {
        this.version = version;
        this.file = file;
        this.sha256 = sha256;
        this.size = size;
    }

    /** The manifest, or null when any field is missing or out of range. */
    public static Manifest parse(String json) {
        if (json == null) {
            return null;
        }
        Long version = number(json, "version");
        String file = string(json, "file");
        String sha = string(json, "sha256");
        Long size = number(json, "size");
        if (version == null || file == null || sha == null || size == null) {
            return null;
        }

        sha = sha.toLowerCase(Locale.ROOT);
        boolean ok = version > 0 && version <= Integer.MAX_VALUE
                && FILE.matcher(file).matches() && !file.startsWith(".")
                && HEX.matcher(sha).matches()
                && size > 0 && size <= MAX_SIZE;
        if (!ok) {
            return null;
        }
        return new Manifest(version.intValue(), file, sha, size);
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
