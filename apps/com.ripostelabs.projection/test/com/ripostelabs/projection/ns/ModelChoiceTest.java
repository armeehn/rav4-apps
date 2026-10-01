package com.ripostelabs.projection.ns;

/**
 * Which model the mic runs: a person's explicit pick always wins; without one, the estate's
 * gate decides (owner-approved 2026-10-01), and nothing at all means the shipped model.
 */
public final class ModelChoiceTest {

    public static void main(String[] args) {
        manualAlwaysWins();
        automaticFollowsTheEstate();
        nothingKnownMeansStandard();
        updatesRunUnlessStandardWasPicked();
        manifestCarriesTheDefault();
        System.out.println("ok   ModelChoiceTest " + Check.count + " checks");
    }

    private static void manualAlwaysWins() {
        Check.that(ModelChoice.wanted(Model.STANDARD, Model.CAR_TUNED) == Model.STANDARD,
                "picked Standard stays Standard when the estate says car-tuned");
        Check.that(ModelChoice.wanted(Model.CAR_TUNED, Model.STANDARD) == Model.CAR_TUNED,
                "picked Car-tuned stays Car-tuned when the estate rolls back");
    }

    private static void automaticFollowsTheEstate() {
        Check.that(ModelChoice.wanted(null, Model.CAR_TUNED) == Model.CAR_TUNED, "gate pass switches over");
        Check.that(ModelChoice.wanted(null, Model.STANDARD) == Model.STANDARD, "rollback switches back");
    }

    private static void nothingKnownMeansStandard() {
        Check.that(ModelChoice.wanted(null, null) == Model.STANDARD, "no pick, no manifest yet");
    }

    private static void updatesRunUnlessStandardWasPicked() {
        Check.that(ModelChoice.checksForUpdates(null), "automatic checks, to learn the default");
        Check.that(ModelChoice.checksForUpdates(Model.CAR_TUNED), "picked Car-tuned checks");
        Check.that(!ModelChoice.checksForUpdates(Model.STANDARD), "picked Standard spends no data");
    }

    private static void manifestCarriesTheDefault() {
        String sha = "9f2c" + "0".repeat(60);
        String base = "{\"name\": \"rnnoise\", \"version\": \"2026-10-02.3\", "
                + "\"files\": [{\"path\": \"2026-10-02.3/weights.bin\", \"sha256\": \"" + sha + "\", \"bytes\": 1553664}]";
        Check.that(Manifest.parse(base + ", \"default\": \"car-tuned\"}").defaultModel == Model.CAR_TUNED,
                "default car-tuned");
        Check.that(Manifest.parse(base + ", \"default\": \"standard\"}").defaultModel == Model.STANDARD,
                "default standard");
        Check.that(Manifest.parse(base + "}").defaultModel == Model.STANDARD, "no default means standard");
        Check.that(Manifest.parse(base + ", \"default\": \"turbo\"}").defaultModel == Model.STANDARD,
                "an unknown default is standard, not a refusal");
    }
}
