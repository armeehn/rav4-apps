package com.ripostelabs.recorder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * Road noise captures, the file half: names the updater on zero can sort and pull, the sidecar
 * that says how each file was captured, and the minutes counted toward the target.
 */
public final class RoadNoiseTest {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private static int count;

    public static void main(String[] args) throws IOException {
        tagsHaveFileSafeSlugs();
        baseNameIsStampThenTag();
        sidecarDescribesTheCapture();
        sidecarWithSpeeds();
        minutesCountOnlyFinishedTakes();
        System.out.println(count + " assertions passed");
    }

    private static void tagsHaveFileSafeSlugs() {
        eq("city", RoadNoise.Tag.CITY.slug(), "city");
        eq("highway", RoadNoise.Tag.HIGHWAY.slug(), "highway");
        eq("fan-high", RoadNoise.Tag.FAN_HIGH.slug(), "fan high");
        eq("rain", RoadNoise.Tag.RAIN.slug(), "rain");
        eq("other", RoadNoise.Tag.OTHER.slug(), "other");
        eq(RoadNoise.Tag.HIGHWAY, RoadNoise.Tag.parse("HIGHWAY"), "parse a stored tag");
        eq(RoadNoise.Tag.CITY, RoadNoise.Tag.parse("nonsense"), "unknown is the default");
    }

    private static void baseNameIsStampThenTag() {
        eq("20261001-162005-fan-high", RoadNoise.baseName(at(2026, 10, 1, 16, 20, 5), UTC, RoadNoise.Tag.FAN_HIGH),
                "base name, in the zone given");
    }

    private static void sidecarDescribesTheCapture() {
        String json = RoadNoise.sidecar("20261001-162005-highway.wav", RoadNoise.Tag.HIGHWAY,
                at(2026, 10, 1, 16, 20, 5), 61.25, null);
        that(json.contains("\"file\": \"20261001-162005-highway.wav\""), "file: " + json);
        that(json.contains("\"tag\": \"highway\""), "tag");
        that(json.contains("\"source\": \"VOICE_COMMUNICATION\""), "source");
        that(json.contains("\"processing\": \"none\""), "no processing");
        that(json.contains("\"rate_hz\": 48000"), "rate");
        that(json.contains("\"channels\": 1"), "mono");
        that(json.contains("\"bits\": 16"), "bits");
        that(json.contains("\"started\": \"2026-10-01T16:20:05Z\""), "start time, UTC: " + json);
        that(json.contains("\"seconds\": 61.25"), "seconds");
        that(json.contains("\"speed_kmh_per_second\": null"), "no speed source");
        that(json.trim().startsWith("{") && json.trim().endsWith("}"), "an object");
    }

    private static void sidecarWithSpeeds() {
        String json = RoadNoise.sidecar("a.wav", RoadNoise.Tag.CITY, at(2026, 1, 1, 0, 0, 0), 2,
                new int[]{48, 51});
        that(json.contains("\"speed_kmh_per_second\": [48, 51]"), "speeds: " + json);
    }

    private static void minutesCountOnlyFinishedTakes() throws IOException {
        File dir = Files.createTempDirectory("road").toFile();
        wav(new File(dir, "a-city.wav"), 30);
        touch(new File(dir, "a-city.json"));
        wav(new File(dir, "b-rain.wav"), 90);
        touch(new File(dir, "b-rain.json"));
        // Still being written: no sidecar yet, so not counted (and not pulled by zero).
        wav(new File(dir, "c-other.wav"), 600);

        that(Math.abs(RoadNoise.totalSeconds(dir) - 120) < 1e-6, "two minutes, got " + RoadNoise.totalSeconds(dir));
        that(RoadNoise.totalSeconds(new File(dir, "missing")) == 0, "no folder, no minutes");
    }

    // ---- helpers ----

    private static Date at(int y, int mo, int d, int h, int mi, int s) {
        Calendar c = Calendar.getInstance(UTC);
        c.clear();
        c.set(y, mo - 1, d, h, mi, s);
        return c.getTime();
    }

    private static void wav(File f, int seconds) throws IOException {
        Wav.Sink sink = Wav.Sink.open(f, RoadNoise.RATE);
        int bytes = RoadNoise.RATE * Wav.BYTES_PER_SAMPLE;
        for (int i = 0; i < seconds; i++) {
            sink.write(new byte[bytes], bytes);
        }
        sink.close();
    }

    private static void touch(File f) throws IOException {
        Files.write(f.toPath(), new byte[]{'{', '}'});
    }

    private static void that(boolean ok, String what) {
        count++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    private static void eq(Object expected, Object actual, String what) {
        that(expected.equals(actual), what + ": expected " + expected + ", got " + actual);
    }
}
