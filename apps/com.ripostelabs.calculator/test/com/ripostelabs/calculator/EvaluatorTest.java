package com.ripostelabs.calculator;

/**
 * The first test in this repository.
 *
 * <p>Chosen because {@link Evaluator} is 228 lines of hand-written recursive-descent parsing with
 * no Android in it at all, which makes it the one piece of the suite where a wrong answer is both
 * likely and completely checkable from a desk. Everything else here is an Activity, where the only
 * way to find a fault has been to drive somewhere and tap it.
 *
 * <p>No JUnit. The suite has no dependency mechanism, and adding one to run a handful of
 * assertions would be a bigger change than the thing being tested. A failure throws, which is all
 * a test really has to do.
 */
public final class EvaluatorTest {

    private static int checks;

    public static void main(String[] args) {
        precedenceAndAssociativity();
        unaryAndImplicitMultiplication();
        prettyGlyphsAreAccepted();
        postfixOperators();
        trigHonoursDegrees();
        constants();
        asciiXIsNotMultiplication();
        malformedInputIsRejected();
        divisionByZeroIsAnError();

        System.out.println(checks + " assertions passed");
    }

    /** Precedence is where a hand-written parser goes wrong, and silently. */
    private static void precedenceAndAssociativity() {
        eq("2+3×4", 14);            // not 20: multiplication binds tighter
        eq("2×3+4", 10);
        eq("8/4/2", 1);             // left-associative: not 4
        eq("2^3^2", 512);           // right-associative: not 64
        eq("(2+3)×4", 20);
        eq("-2^2", -4);             // the power binds tighter than the unary minus
    }

    private static void unaryAndImplicitMultiplication() {
        eq("-5", -5);
        eq("--5", 5);
        eq("2(3)", 6);              // implicit multiplication
        eq("3--2", 5);
    }

    /** The parser is handed the display string, glyphs and all, not a cleaned-up one. */
    private static void prettyGlyphsAreAccepted() {
        eq("6×7", 42);         // multiplication sign
        eq("9÷3", 3);          // division sign
        eq("−5+2", -3);        // typographic minus
        // The root key appends "√(" and not a bare glyph, so a bracket is part of the token.
        eq("√(16)", 4);
        eq("3²", 9);           // squared
    }

    private static void postfixOperators() {
        eq("5!", 120);
        eq("0!", 1);
        eq("50%", 0.5);
        eq("3!+1", 7);
    }

    /** A calculator in degrees mode that quietly works in radians is wrong by a factor of 57. */
    private static void trigHonoursDegrees() {
        eqMode("sin(90)", 1, true);
        eqMode("cos(0)", 1, true);
        eqMode("sin(0)", 0, false);
        approx("sin(pi/2) in radians", Evaluator.eval("sin(pi/2)", false), 1);
    }

    private static void constants() {
        approx("pi", Evaluator.eval("pi", false), Math.PI);
        approx("e", Evaluator.eval("e", false), Math.E);
        eq("2×pi/pi", 2);
    }

    /**
     * The keypad emits the typographic sign, and ASCII 'x' is not a synonym for it. Pinned
     * because it looks like an oversight worth "fixing": accepting bare 'x' would make it
     * ambiguous with a variable and with the hexadecimal prefix, and nothing on the keypad can
     * produce it. Written down so the next reader does not add it by reflex.
     */
    private static void asciiXIsNotMultiplication() {
        rejects("3x4");
    }

    /**
     * Malformed input must raise, never return a number. A calculator that invents an answer for
     * nonsense is worse than one that says Error, because nothing downstream can tell.
     */
    private static void malformedInputIsRejected() {
        rejects("");
        rejects("   ");
        rejects("2+");
        rejects("(2+3");
        rejects("2 3 4 )");
        rejects("sin");
        rejects(null);
    }

    private static void divisionByZeroIsAnError() {
        rejects("1/0");
        rejects("0/0");
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────────────

    private static void eq(String expr, double expected) {
        approx(expr, Evaluator.eval(expr, true), expected);
    }

    private static void eqMode(String expr, double expected, boolean degrees) {
        approx(expr, Evaluator.eval(expr, degrees), expected);
    }

    private static void approx(String what, double actual, double expected) {
        checks++;
        if (Math.abs(actual - expected) > 1e-9) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }

    /** Anything the parser cannot make sense of has to throw, not guess. */
    private static void rejects(String expr) {
        checks++;
        try {
            double v = Evaluator.eval(expr, true);
            throw new AssertionError("expected a rejection for <" + expr + "> but got " + v);
        } catch (Evaluator.CalcError expected) {
            // correct
        }
    }
}
