package com.ripostelabs.recorder;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Road noise captures for tuning RNNoise on this car: the cabin mic with no processing, 48 kHz
 * mono 16-bit WAV, one tag per take, and a JSON sidecar saying how it was captured.
 *
 * <pre>
 *   RoadNoise/20261001-162005-highway.wav    the samples, as the mic gave them
 *   RoadNoise/20261001-162005-highway.json   tag, source, rate, start, length, speed
 * </pre>
 *
 * The sidecar is written last, after the WAV is closed, so "has a sidecar" means "finished":
 * the minute counter and the updater on zero (os/car-update/road-noise-pull in device-reveng)
 * both skip a WAV without one.
 */
final class RoadNoise {

    /** RNNoise's own rate: training data needs no resampling. */
    static final int RATE = 48000;
    static final String DIR = "RoadNoise";
    static final String WAV = ".wav";
    static final String JSON = ".json";
    /** Minutes of capture asked for, shown as progress. */
    static final int TARGET_MINUTES = 30;

    private static final int SECONDS_PER_MINUTE = 60;

    /** What the cabin sounded like, picked before the take. */
    enum Tag {
        CITY,
        HIGHWAY,
        FAN_HIGH,
        RAIN,
        OTHER;

        /** "fan-high": the form in file names and the sidecar. */
        String slug() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        static Tag parse(String name) {
            for (Tag t : values()) {
                if (t.name().equals(name)) {
                    return t;
                }
            }
            return CITY;
        }
    }

    private RoadNoise() {
    }

    /** "20261001-162005-highway", the start time in {@code zone} (the car's local time). */
    static String baseName(Date start, TimeZone zone, Tag tag) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        f.setTimeZone(zone);
        return f.format(start) + "-" + tag.slug();
    }

    /**
     * The sidecar. {@code speeds} is km/h once a second, or null when the suite has no vehicle
     * speed to read (true today: the CAN snapshot is the launcher's alone).
     */
    static String sidecar(String wavName, Tag tag, Date start, double seconds, int[] speeds) {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));

        StringBuilder speed = new StringBuilder();
        if (speeds == null) {
            speed.append("null");
        } else {
            speed.append('[');
            for (int i = 0; i < speeds.length; i++) {
                speed.append(i == 0 ? "" : ", ").append(speeds[i]);
            }
            speed.append(']');
        }

        return "{\n"
                + "  \"file\": \"" + wavName + "\",\n"
                + "  \"tag\": \"" + tag.slug() + "\",\n"
                + "  \"source\": \"" + PcmCapture.SOURCE_NAME + "\",\n"
                + "  \"processing\": \"none\",\n"
                + "  \"rate_hz\": " + RATE + ",\n"
                + "  \"channels\": 1,\n"
                + "  \"bits\": 16,\n"
                + "  \"started\": \"" + iso.format(start) + "\",\n"
                + "  \"seconds\": " + String.format(Locale.US, "%.2f", seconds) + ",\n"
                + "  \"speed_kmh_per_second\": " + speed + "\n"
                + "}\n";
    }

    /** Seconds of finished takes (a WAV with its sidecar) in {@code dir}; 0 if none. */
    static double totalSeconds(File dir) {
        File[] wavs = dir.listFiles((d, name) -> name.endsWith(WAV));
        if (wavs == null) {
            return 0;
        }
        double total = 0;
        for (File w : wavs) {
            File json = new File(dir, Takes.stripExtension(w.getName()) + JSON);
            if (!json.exists()) {
                continue;
            }
            total += Wav.seconds(w);
        }
        return total;
    }

    static double minutes(double seconds) {
        return seconds / SECONDS_PER_MINUTE;
    }
}
