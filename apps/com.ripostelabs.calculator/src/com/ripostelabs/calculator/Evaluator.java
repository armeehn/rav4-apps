package com.ripostelabs.calculator;

/**
 * Hand-written recursive-descent expression evaluator. No ScriptEngine / JS.
 *
 * Grammar (precedence low -> high):
 *   expr    := term  (('+'|'-') term)*
 *   term    := unary (('*'|'/') unary | implicit-mul unary)*
 *   unary   := ('-'|'+') unary | factor
 *   factor  := postfix ('^' unary)?            // right-associative power
 *   postfix := primary ('!' | '%')*            // factorial, percent
 *   primary := number | 'pi' | 'e'
 *            | '(' expr ')' | func '(' expr ')'
 *   func    := sin | cos | tan | ln | log | sqrt
 *
 * Accepts the "pretty" display string (uses x, /, -, √, π) and normalises it.
 * Trig honours {@code degrees}. Throws {@link CalcError} on any malformed input,
 * division-by-zero, or non-finite result so callers can render "Error".
 */
final class Evaluator {

    static final class CalcError extends RuntimeException {
        CalcError(String m) { super(m); }
    }

    private final String s;
    private int pos;
    private final boolean degrees;

    private Evaluator(String src, boolean degrees) {
        this.s = src;
        this.degrees = degrees;
    }

    /** Evaluate a pretty display expression. Throws CalcError if invalid. */
    static double eval(String pretty, boolean degrees) {
        if (pretty == null) throw new CalcError("empty");
        String norm = normalise(pretty);
        if (norm.isEmpty()) throw new CalcError("empty");
        Evaluator ev = new Evaluator(norm, degrees);
        double v = ev.parseExpr();
        ev.skipWs();
        if (ev.pos != ev.s.length()) throw new CalcError("trailing input");
        if (Double.isNaN(v) || Double.isInfinite(v)) throw new CalcError("non-finite");
        return v;
    }

    /** Map display glyphs to ASCII tokens the parser understands. */
    private static String normalise(String p) {
        StringBuilder b = new StringBuilder(p.length());
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            switch (c) {
                case '×': b.append('*'); break;   // ×
                case '÷': b.append('/'); break;   // ÷
                case '−': b.append('-'); break;   // − (minus sign)
                case '√': b.append("sqrt"); break;// √
                case 'π': b.append("pi"); break;  // π
                case '²': b.append("^2"); break;  // x squared
                default: b.append(c);
            }
        }
        return b.toString();
    }

    // ---- lexing helpers ----
    private void skipWs() {
        while (pos < s.length() && s.charAt(pos) == ' ') pos++;
    }
    private char peek() {
        skipWs();
        return pos < s.length() ? s.charAt(pos) : '\0';
    }
    private void expect(char c) {
        if (peek() != c) throw new CalcError("expected '" + c + "'");
        pos++;
    }

    // ---- grammar ----
    private double parseExpr() {
        double v = parseTerm();
        while (true) {
            char c = peek();
            if (c == '+') { pos++; v += parseTerm(); }
            else if (c == '-') { pos++; v -= parseTerm(); }
            else break;
        }
        return v;
    }

    private double parseTerm() {
        double v = parseUnary();
        while (true) {
            char c = peek();
            if (c == '*') { pos++; v *= parseUnary(); }
            else if (c == '/') {
                pos++;
                double d = parseUnary();
                if (d == 0.0) throw new CalcError("divide by zero");
                v /= d;
            } else if (startsFactor(c)) {
                // implicit multiplication: 2pi, 3(4), 2sin(0)
                v *= parseUnary();
            } else break;
        }
        return v;
    }

    private static boolean startsFactor(char c) {
        return c == '(' || c == '.' || (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private double parseUnary() {
        char c = peek();
        if (c == '-') { pos++; return -parseUnary(); }
        if (c == '+') { pos++; return parseUnary(); }
        return parseFactor();
    }

    private double parseFactor() {
        double base = parsePostfix();
        if (peek() == '^') {
            pos++;
            double exp = parseUnary();      // right-assoc, signed exponent
            double r = Math.pow(base, exp);
            if (Double.isNaN(r) || Double.isInfinite(r)) throw new CalcError("pow");
            return r;
        }
        return base;
    }

    private double parsePostfix() {
        double v = parsePrimary();
        while (true) {
            char c = peek();
            if (c == '!') { pos++; v = factorial(v); }
            else if (c == '%') { pos++; v = v / 100.0; }
            else break;
        }
        return v;
    }

    private double parsePrimary() {
        char c = peek();
        if (c == '(') {
            pos++;
            double v = parseExpr();
            expect(')');
            return v;
        }
        if (c == '.' || (c >= '0' && c <= '9')) return parseNumber();
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return parseIdent();
        throw new CalcError("unexpected '" + c + "'");
    }

    private double parseNumber() {
        skipWs();
        int start = pos;
        boolean dot = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c >= '0' && c <= '9') pos++;
            else if (c == '.' && !dot) { dot = true; pos++; }
            else break;
        }
        String num = s.substring(start, pos);
        if (num.isEmpty() || num.equals(".")) throw new CalcError("bad number");
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            throw new CalcError("bad number");
        }
    }

    private double parseIdent() {
        skipWs();
        int start = pos;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) pos++;
            else break;
        }
        String id = s.substring(start, pos).toLowerCase();
        if (id.equals("pi")) return Math.PI;
        if (id.equals("e")) return Math.E;
        // function: must be followed by ( expr )
        expect('(');
        double arg = parseExpr();
        expect(')');
        switch (id) {
            case "sin": return Math.sin(toRad(arg));
            case "cos": return Math.cos(toRad(arg));
            case "tan": {
                double t = Math.tan(toRad(arg));
                if (Double.isInfinite(t)) throw new CalcError("tan undefined");
                return t;
            }
            case "ln": {
                if (arg <= 0) throw new CalcError("ln domain");
                return Math.log(arg);
            }
            case "log": {
                if (arg <= 0) throw new CalcError("log domain");
                return Math.log10(arg);
            }
            case "sqrt": {
                if (arg < 0) throw new CalcError("sqrt domain");
                return Math.sqrt(arg);
            }
            default: throw new CalcError("unknown func " + id);
        }
    }

    private double toRad(double v) {
        return degrees ? Math.toRadians(v) : v;
    }

    private static double factorial(double v) {
        if (v < 0) throw new CalcError("factorial domain");
        double r = Math.rint(v);
        if (Math.abs(v - r) > 1e-9) throw new CalcError("factorial non-integer");
        if (r > 170) throw new CalcError("factorial overflow");
        double out = 1.0;
        for (int i = 2; i <= (int) r; i++) out *= i;
        return out;
    }
}
