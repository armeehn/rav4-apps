package com.ripostelabs.projection.ns;

/** The model manifest (CONTRACT.md section 5): what it takes, and that anything off is refused. */
public final class ManifestTest {

    private static final String SHA = "9f2c" + "0".repeat(60);
    private static final String GOOD = "{\n \"name\": \"rnnoise\",\n \"version\": \"2026-10-02.3\",\n"
            + " \"published_at\": \"2026-10-02T03:41:00Z\",\n"
            + " \"files\": [{\"path\": \"2026-10-02.3/weights.bin\", \"sha256\": \"" + SHA + "\", \"bytes\": 1553664}],\n"
            + " \"min_app_version\": null,\n \"notes\": \"eval on owner noise\",\n \"parent\": \"2026-10-01.1\",\n"
            + " \"default\": \"standard\",\n \"metrics\": {\"group\": \"owner\", \"clips\": 96}\n}";

    public static void main(String[] args) {
        readsTheFields();
        versionsOrderLikeDates();
        refusesMissingFields();
        refusesBadValues();
        refusesPathsOutOfTheFolder();
        System.out.println("ok   ManifestTest " + Check.count + " checks");
    }

    private static void readsTheFields() {
        Manifest m = Manifest.parse(GOOD);
        Check.that(m != null, "a good manifest parses");
        Check.eq(2026100203, m.version, "version code");
        Check.that("2026-10-02.3/weights.bin".equals(m.path), "path");
        Check.that(SHA.equals(m.sha256), "sha256");
        Check.eq(1553664, m.size, "bytes");
        Check.that(Manifest.parse(GOOD.replace(SHA, SHA.toUpperCase())) != null, "upper-case hex is fine");
        Check.that(Manifest.parse(GOOD.replace(" \"name\": \"rnnoise\",\n", "")) != null, "name is optional");
    }

    private static void versionsOrderLikeDates() {
        Check.that(Manifest.code("2026-10-02.1") > Manifest.code("2026-10-01.9"), "a later day wins");
        Check.that(Manifest.code("2026-10-02.10") > Manifest.code("2026-10-02.9"), "a later run wins");
        Check.that(Manifest.code("2026-12-31.99") > Manifest.code("2026-12-31.98"), "up to 99 a day");
        Check.that("2026-10-02.3".equals(Manifest.label(2026100203)), "label round-trips");
        Check.that("0".equals(Manifest.label(0)), "no version labels as 0");
    }

    private static void refusesMissingFields() {
        Check.that(Manifest.parse(null) == null, "null");
        Check.that(Manifest.parse("") == null, "empty");
        Check.that(Manifest.parse("<html>502 Bad Gateway</html>") == null, "an error page");
        Check.that(Manifest.parse(GOOD.replace("\"sha256\"", "\"sha\"")) == null, "no sha256");
        Check.that(Manifest.parse(GOOD.replace("\"bytes\"", "\"size\"")) == null, "no bytes");
        Check.that(Manifest.parse(GOOD.replace("\"path\"", "\"file\"")) == null, "no path");
    }

    private static void refusesBadValues() {
        Check.that(Manifest.parse(GOOD.replace("\"rnnoise\"", "\"other\"")) == null, "another model's manifest");
        Check.that(Manifest.parse(GOOD.replace("\"2026-10-02.3\"", "\"3\"")) == null, "a bare number version");
        Check.that(Manifest.parse(GOOD.replace("\"2026-10-02.3\"", "\"2026-10-02.0\"")) == null, "run 0");
        Check.that(Manifest.parse(GOOD.replace(SHA, SHA.substring(1))) == null, "short sha256");
        Check.that(Manifest.parse(GOOD.replace(SHA, "zz" + SHA.substring(2))) == null, "non-hex sha256");
        Check.that(Manifest.parse(GOOD.replace("1553664", "0")) == null, "bytes 0");
        Check.that(Manifest.parse(GOOD.replace("1553664", "999999999")) == null, "bytes past the cap");
    }

    private static void refusesPathsOutOfTheFolder() {
        Check.that(Manifest.parse(GOOD.replace("2026-10-02.3/weights.bin", "../etc/x")) == null, "parent path");
        Check.that(Manifest.parse(GOOD.replace("2026-10-02.3/weights.bin", "2026-10-02.3/../../x")) == null, "climbs out");
        Check.that(Manifest.parse(GOOD.replace("2026-10-02.3/weights.bin", "2026-10-02.3/a/b.bin")) == null, "deeper path");
        Check.that(Manifest.parse(GOOD.replace("2026-10-02.3/weights.bin", "2026-10-01.1/weights.bin")) == null,
                "another version's folder");
        Check.that(Manifest.parse(GOOD.replace("2026-10-02.3/weights.bin", "http://evil/x")) == null, "a URL");
    }
}
