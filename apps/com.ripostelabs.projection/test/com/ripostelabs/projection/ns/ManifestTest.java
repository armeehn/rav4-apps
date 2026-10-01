package com.ripostelabs.projection.ns;

/** The model manifest: what it takes, and that anything off is refused rather than guessed. */
public final class ManifestTest {

    private static final String SHA = "9f2c" + "0".repeat(60);
    private static final String GOOD = "{\n \"version\": 3,\n \"file\": \"rnnoise-car-3.bin\",\n"
            + " \"sha256\": \"" + SHA + "\",\n \"size\": 1553664,\n \"parent\": 2,\n"
            + " \"default\": \"standard\",\n \"metrics\": {\"group\": \"public\", \"clips\": 96}\n}";

    public static void main(String[] args) {
        readsTheFields();
        refusesMissingFields();
        refusesBadValues();
        refusesPathsOutOfTheDirectory();
        System.out.println("ok   ManifestTest " + Check.count + " checks");
    }

    private static void readsTheFields() {
        Manifest m = Manifest.parse(GOOD);
        Check.that(m != null, "a good manifest parses");
        Check.eq(3, m.version, "version");
        Check.that("rnnoise-car-3.bin".equals(m.file), "file");
        Check.that(SHA.equals(m.sha256), "sha256");
        Check.eq(1553664, m.size, "size");
        Check.that(Manifest.parse(GOOD.replace(SHA, SHA.toUpperCase())) != null, "upper-case hex is fine");
    }

    private static void refusesMissingFields() {
        Check.that(Manifest.parse(null) == null, "null");
        Check.that(Manifest.parse("") == null, "empty");
        Check.that(Manifest.parse("<html>502 Bad Gateway</html>") == null, "an error page");
        Check.that(Manifest.parse(GOOD.replace("\"sha256\"", "\"sha\"")) == null, "no sha256");
        Check.that(Manifest.parse(GOOD.replace("\"size\"", "\"bytes\"")) == null, "no size");
    }

    private static void refusesBadValues() {
        Check.that(Manifest.parse(GOOD.replace("\"version\": 3", "\"version\": 0")) == null, "version 0");
        Check.that(Manifest.parse(GOOD.replace("\"version\": 3", "\"version\": -3")) == null, "negative version");
        Check.that(Manifest.parse(GOOD.replace(SHA, SHA.substring(1))) == null, "short sha256");
        Check.that(Manifest.parse(GOOD.replace(SHA, "zz" + SHA.substring(2))) == null, "non-hex sha256");
        Check.that(Manifest.parse(GOOD.replace("1553664", "0")) == null, "size 0");
        Check.that(Manifest.parse(GOOD.replace("1553664", "999999999")) == null, "size past the cap");
    }

    private static void refusesPathsOutOfTheDirectory() {
        Check.that(Manifest.parse(GOOD.replace("rnnoise-car-3.bin", "../etc/x")) == null, "parent path");
        Check.that(Manifest.parse(GOOD.replace("rnnoise-car-3.bin", "a/b.bin")) == null, "sub path");
        Check.that(Manifest.parse(GOOD.replace("rnnoise-car-3.bin", ".hidden")) == null, "dot file");
        Check.that(Manifest.parse(GOOD.replace("rnnoise-car-3.bin", "http:x")) == null, "scheme");
    }
}
