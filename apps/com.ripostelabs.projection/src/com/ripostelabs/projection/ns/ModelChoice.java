package com.ripostelabs.projection.ns;

/**
 * Which model the mic runs, as a decision a test can check.
 *
 *   picked by a person ──▶ that model, always
 *   automatic          ──▶ the estate's default (car-tuned only after it wins on this car's
 *                          own road noise; standard again on a rollback), standard until known
 *
 * The owner approved the automatic switch on 2026-10-01. A car-tuned choice without a usable
 * downloaded model still runs the standard weights (see {@link Model}).
 */
public final class ModelChoice {

    private ModelChoice() {
    }

    /** The model wanted; {@code manual} null means automatic, {@code estate} null means not known yet. */
    public static Model wanted(Model manual, Model estate) {
        if (manual != null) {
            return manual;
        }
        return estate != null ? estate : Model.STANDARD;
    }

    /** Whether to fetch manifests: automatic needs them to learn the default; picked Standard does not. */
    public static boolean checksForUpdates(Model manual) {
        return manual != Model.STANDARD;
    }
}
