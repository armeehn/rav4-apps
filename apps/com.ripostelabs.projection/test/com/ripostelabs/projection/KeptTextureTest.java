package com.ripostelabs.projection;

/**
 * The CarPlay screen keeps one texture for its whole life. Back from the background, the
 * view offers a fresh, empty one; taking it showed nothing until the phone redrew, up to
 * 10 s on a still screen (car log 2026-10-01 15:29:45). The kept one holds the last picture.
 */
public final class KeptTextureTest {

    private static int count;

    public static void main(String[] args) {
        firstTextureIsAdopted();
        freshTextureAfterStopIsReplaced();
        sameTextureIsLeftAlone();
        startBeforeAnyTextureDoesNothing();
        startWithTheViewEmptyRestores();
        System.out.println(count + " assertions passed");
    }

    private static void firstTextureIsAdopted() {
        KeptTexture<String> kept = new KeptTexture<>();
        eq(KeptTexture.Use.ADOPT, kept.onAvailable("first"), "first texture");
        eq("first", kept.texture(), "kept after adopt");
    }

    private static void freshTextureAfterStopIsReplaced() {
        KeptTexture<String> kept = new KeptTexture<>();
        kept.onAvailable("first");
        eq(KeptTexture.Use.RESTORE, kept.onAvailable("fresh"), "fresh after stop");
        eq("first", kept.texture(), "still the first");
    }

    private static void sameTextureIsLeftAlone() {
        KeptTexture<String> kept = new KeptTexture<>();
        kept.onAvailable("first");
        eq(KeptTexture.Use.KEEP, kept.onAvailable("first"), "same texture");
        eq(KeptTexture.Use.KEEP, kept.onStart("first"), "view still holds it");
    }

    private static void startBeforeAnyTextureDoesNothing() {
        KeptTexture<String> kept = new KeptTexture<>();
        eq(KeptTexture.Use.KEEP, kept.onStart(null), "nothing to restore yet");
    }

    private static void startWithTheViewEmptyRestores() {
        KeptTexture<String> kept = new KeptTexture<>();
        kept.onAvailable("first");
        eq(KeptTexture.Use.RESTORE, kept.onStart(null), "view lost it on stop");
    }

    private static void eq(Object want, Object got, String what) {
        count++;
        if (want == null ? got != null : !want.equals(got)) {
            throw new AssertionError(what + ": want " + want + ", got " + got);
        }
    }
}
