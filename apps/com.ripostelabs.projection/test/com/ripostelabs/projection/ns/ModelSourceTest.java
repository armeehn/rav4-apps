package com.ripostelabs.projection.ns;

/** Where models come from: the ingest service over the uplink when enrolled, else the farm. */
public final class ModelSourceTest {

    public static void main(String[] args) {
        enrolledUnitUsesTheUplink();
        notEnrolledUsesTheFarm();
        urls();
        System.out.println("ok   ModelSourceTest " + Check.count + " checks");
    }

    private static void enrolledUnitUsesTheUplink() {
        ModelSource s = ModelSource.from("http://100.64.0.1:8797\n");
        Check.that(s.viaUplink, "through the uplink's SOCKS5 port");
        Check.that("http://100.64.0.1:8797/v1/models/rnnoise/".equals(s.base), "ingest models folder: " + s.base);
        Check.that(ModelSource.from("http://100.64.0.1:8797/").base.equals(s.base), "a trailing slash is the same");
    }

    private static void notEnrolledUsesTheFarm() {
        for (String text : new String[] {null, "", "   \n", "ftp://x", "http://a b", "not a url"}) {
            ModelSource s = ModelSource.from(text);
            Check.that(!s.viaUplink, "direct for " + text);
            Check.that(ModelSource.FARM_BASE.equals(s.base), "launcher.hq for " + text);
        }
    }

    private static void urls() {
        ModelSource s = ModelSource.from("http://100.64.0.1:8797");
        Check.that("http://100.64.0.1:8797/v1/models/rnnoise/manifest.json".equals(s.manifestUrl()), "manifest");
        Manifest m = Manifest.parse("{\"version\": \"2026-10-02.1\", \"files\": [{\"path\": \"2026-10-02.1/weights.bin\","
                + " \"sha256\": \"" + "a".repeat(64) + "\", \"bytes\": 10}]}");
        Check.that("http://100.64.0.1:8797/v1/models/rnnoise/2026-10-02.1/weights.bin".equals(s.blobUrl(m)), "blob");
    }
}
