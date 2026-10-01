package com.ripostelabs.projection.ns;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The car-tuned model store, with a fake engine that opens blobs starting with "GOOD": a
 * download is verified before it is kept, a new model only takes over when a capture starts,
 * and a model that will not load is dropped for the one before it, for good.
 */
public final class ModelStoreTest {

    private static final byte[] GOOD = "GOOD model".getBytes(StandardCharsets.US_ASCII);

    public static void main(String[] args) throws IOException {
        verifyChecksSizeAndHash();
        emptyStoreGivesNoEngine();
        stagedModelWaitsForTheNextSession();
        newerOnlyAndNeverABadOne();
        brokenModelRollsBack();
        brokenFirstModelFallsBackToNone();
        stateSurvivesARestart();
        System.out.println("ok   ModelStoreTest " + Check.count + " checks");
    }

    private static final class Fake implements Engine {
        final String blob;

        Fake(byte[] blob) {
            this.blob = new String(blob, StandardCharsets.US_ASCII);
        }

        @Override
        public void frame(float[] pcm) {
        }

        @Override
        public int delayFrames() {
            return 0;
        }

        @Override
        public void close() {
        }
    }

    private static Engine open(byte[] blob) {
        return new String(blob, StandardCharsets.US_ASCII).startsWith("GOOD") ? new Fake(blob) : null;
    }

    private static byte[] blob(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static Manifest manifest(int version, byte[] blob) {
        return Manifest.parse("{\"version\": " + version + ", \"file\": \"rnnoise-car-" + version
                + ".bin\", \"sha256\": \"" + ModelStore.sha256(blob) + "\", \"size\": " + blob.length + "}");
    }

    private static ModelStore fresh() throws IOException {
        return new ModelStore(Files.createTempDirectory("nsmodel").toFile());
    }

    private static void verifyChecksSizeAndHash() {
        Manifest m = manifest(1, GOOD);
        Check.that(ModelStore.verify(m, GOOD), "the right bytes verify");
        Check.that(!ModelStore.verify(m, blob("GOOD modeX")), "one byte off fails");
        Check.that(!ModelStore.verify(m, blob("GOOD model!")), "a longer file fails");
        Check.that(!ModelStore.verify(m, null), "no bytes fail");
        Check.that(!ModelStore.verify(null, GOOD), "no manifest fails");
    }

    private static void emptyStoreGivesNoEngine() throws IOException {
        ModelStore.Session s = fresh().open(ModelStoreTest::open);
        Check.that(s.engine == null, "no model, no engine: the caller runs Standard");
        Check.eq(0, s.version, "version 0");
    }

    private static void stagedModelWaitsForTheNextSession() throws IOException {
        ModelStore store = fresh();
        Check.that(store.stage(manifest(1, blob("GOOD v1")), blob("GOOD v1")), "v1 staged");
        Check.eq(1, store.open(ModelStoreTest::open).version, "v1 in use from the next session");

        // A download arrives mid-call: nothing changes until the next capture starts.
        Check.that(store.stage(manifest(2, blob("GOOD v2")), blob("GOOD v2")), "v2 staged");
        Check.eq(1, store.activeVersion(), "v1 still active during the call");
        Check.eq(2, store.pendingVersion(), "v2 waiting");

        ModelStore.Session s = store.open(ModelStoreTest::open);
        Check.eq(2, s.version, "v2 at the next session");
        Check.that("GOOD v2".equals(((Fake) s.engine).blob), "with v2's bytes");
        Check.eq(0, store.pendingVersion(), "nothing pending after the swap");
    }

    private static void newerOnlyAndNeverABadOne() throws IOException {
        ModelStore store = fresh();
        store.stage(manifest(2, blob("GOOD v2")), blob("GOOD v2"));
        store.open(ModelStoreTest::open);
        Check.that(!store.wants(manifest(2, blob("GOOD v2"))), "the same version is not wanted");
        Check.that(!store.wants(manifest(1, blob("GOOD v1"))), "an older one is not wanted");
        Check.that(store.wants(manifest(3, blob("GOOD v3"))), "a newer one is");
        Check.that(!store.stage(manifest(3, blob("GOOD v3")), blob("GOOD vX")), "a corrupt download is not kept");
        Check.eq(0, store.pendingVersion(), "and nothing is pending");
    }

    private static void brokenModelRollsBack() throws IOException {
        ModelStore store = fresh();
        store.stage(manifest(1, blob("GOOD v1")), blob("GOOD v1"));
        store.open(ModelStoreTest::open);
        // v2 verifies (the server sent what it meant to) but will not load as a model.
        store.stage(manifest(2, blob("BAD v2")), blob("BAD v2"));

        ModelStore.Session s = store.open(ModelStoreTest::open);
        Check.eq(1, s.version, "v1 back in use");
        Check.that(s.refused.contains(2), "v2 reported as refused");
        Check.eq(1, store.activeVersion(), "v1 is active again");
        Check.that(!store.wants(manifest(2, blob("BAD v2"))), "v2 is never fetched again");
        Check.that(store.wants(manifest(3, blob("GOOD v3"))), "a fixed v3 still is");
        Check.eq(1, store.open(ModelStoreTest::open).version, "and v1 stays in use");
    }

    private static void brokenFirstModelFallsBackToNone() throws IOException {
        ModelStore store = fresh();
        store.stage(manifest(1, blob("BAD v1")), blob("BAD v1"));
        ModelStore.Session s = store.open(ModelStoreTest::open);
        Check.that(s.engine == null, "no usable car model: the caller runs Standard");
        Check.eq(0, store.activeVersion(), "nothing active");
    }

    private static void stateSurvivesARestart() throws IOException {
        File dir = Files.createTempDirectory("nsmodel").toFile();
        ModelStore a = new ModelStore(dir);
        a.stage(manifest(4, blob("GOOD v4")), blob("GOOD v4"));
        a.open(ModelStoreTest::open);
        a.stage(manifest(5, blob("GOOD v5")), blob("GOOD v5"));

        ModelStore b = new ModelStore(dir);
        Check.eq(4, b.activeVersion(), "active survives");
        Check.eq(5, b.pendingVersion(), "pending survives");
        Check.eq(5, b.open(ModelStoreTest::open).version, "and is promoted after a restart");
    }
}
