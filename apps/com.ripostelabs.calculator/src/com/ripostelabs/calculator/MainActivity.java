package com.ripostelabs.calculator;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.Locale;
import com.ripostelabs.design.Palette;

/**
 * Clean-room standalone Calculator. Landscape layout: display panel + scrollable
 * history on the left, a 7-column button grid (scientific keys + basic keypad) on
 * the right, built in code. Expression math is handled by {@link Evaluator} — a
 * hand-written recursive-descent parser (no ScriptEngine / JS).
 */
public class MainActivity extends Activity {

    // Button grid: 5 rows x 7 columns. Cols 0-2 scientific, cols 3-6 keypad.
    private static final String[][] KEYS = {
        {"sin", "cos", "tan", "AC",  "⌫", "(",  ")"},
        {"ln",  "log", "√",   "7",   "8", "9",  "÷"},
        {"x²",  "xʸ",  "%",   "4",   "5", "6",  "×"},
        {"π",   "e",   "!",   "1",   "2", "3",  "−"},
        {"ANS", "±",   ".",   "0",   "00","=",  "+"},
    };

    private final StringBuilder buf = new StringBuilder();
    private boolean justEquals = false;   // last action was '='; next value input starts fresh
    private boolean degrees = false;      // trig angle mode; false = radians
    private double lastAns = 0.0;
    private boolean hasPreview = false;

    private TextView exprView, resultView, angleView;
    private LinearLayout historyList;
    private ScrollView historyScroll;

    // resolved palette
    private int cText, cText2, cText3, cAccent, cAccent2, cBg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);
        cText3 = Palette.color(this, R.color.text3);
        cAccent = Palette.color(this, R.color.accent);
        cAccent2 = Palette.color(this, R.color.accent2);
        cBg = Palette.color(this, R.color.bg);

        exprView = findViewById(R.id.expr);
        resultView = findViewById(R.id.result);
        angleView = findViewById(R.id.angleMode);
        historyList = findViewById(R.id.historyList);
        historyScroll = findViewById(R.id.historyScroll);

        angleView.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            degrees = !degrees;
            angleView.setText(degrees ? R.string.deg : R.string.rad);
            updatePreview();
        });

        findViewById(R.id.clearHistory).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            clearHistory();
        });

        buildGrid();
        clearHistory();   // show placeholder
        render();
    }

    // ---------- grid construction ----------
    private void buildGrid() {
        LinearLayout holder = findViewById(R.id.gridHolder);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(7);
        grid.setRowCount(5);
        holder.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int m = dp(5);
        for (int r = 0; r < KEYS.length; r++) {
            for (int c = 0; c < KEYS[r].length; c++) {
                String label = KEYS[r][c];
                Button b = new Button(this);
                b.setText(label);
                b.setAllCaps(false);
                b.setStateListAnimator(null);
                b.setIncludeFontPadding(false);
                styleKey(b, label);

                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                        GridLayout.spec(r, 1, 1f),
                        GridLayout.spec(c, 1, 1f));
                lp.width = 0;
                lp.height = 0;
                lp.setMargins(m, m, m, m);
                b.setLayoutParams(lp);
                b.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    onKey(label);
                });
                grid.addView(b);
            }
        }
    }

    private void styleKey(Button b, String label) {
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        b.setTextColor(cText);
        b.setBackgroundResource(R.drawable.btn_ghost);
        switch (label) {
            case "=":
                b.setBackgroundResource(R.drawable.btn_accent);
                b.setTextColor(cBg);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
                b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
                break;
            case "+": case "−": case "×": case "÷":
                b.setTextColor(cAccent);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
                break;
            case "AC": case "⌫":
                b.setTextColor(cAccent2);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                break;
            case "sin": case "cos": case "tan": case "ln": case "log":
            case "√": case "x²": case "xʸ": case "!": case "%":
            case "π": case "e": case "ANS": case "±":
                b.setTextColor(cText2);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
                break;
            case "(": case ")":
                b.setTextColor(cText3);
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                break;
            default:
                // digits + "." + "00"
                b.setTextColor(cText);
                break;
        }
    }

    // ---------- input handling ----------
    private void onKey(String label) {
        switch (label) {
            case "AC":
                buf.setLength(0);
                justEquals = false;
                hasPreview = false;
                resultView.setText("0");
                resultView.setTextColor(cText3);
                render();
                return;
            case "⌫":
                backspace();
                render();
                updatePreview();
                return;
            case "=":
                doEquals();
                return;
            case "±":
                toggleSign();
                render();
                updatePreview();
                return;
            case "ANS":
                appendValue(plain(lastAns));
                break;
            case "sin": case "cos": case "tan": case "ln": case "log":
                appendValue(label + "(");
                break;
            case "√":
                appendValue("√(");
                break;
            case "π": case "e":
                appendValue(label);
                break;
            case "(":
                appendValue("(");
                break;
            case ")":
                appendValue(")");
                break;
            case "x²":
                appendOp("²");
                break;
            case "xʸ":
                appendOp("^");
                break;
            case "!":
                appendOp("!");
                break;
            case "%":
                appendOp("%");
                break;
            case "+": case "−": case "×": case "÷":
                appendOp(label);
                break;
            default:
                // digits, "00", "."
                appendValue(label);
                break;
        }
        render();
        updatePreview();
    }

    /** Value input (digit, constant, function, paren): a pending '=' starts fresh. */
    private void appendValue(String tok) {
        if (justEquals) { buf.setLength(0); justEquals = false; }
        buf.append(tok);
    }

    /** Operator/postfix input: a pending '=' chains from the last answer. */
    private void appendOp(String tok) {
        if (justEquals) { buf.setLength(0); buf.append(plain(lastAns)); justEquals = false; }
        buf.append(tok);
    }

    private static final String[] FN_TOKENS = {"sin(", "cos(", "tan(", "log(", "ln(", "√("};

    private void backspace() {
        justEquals = false;
        String s = buf.toString();
        for (String fn : FN_TOKENS) {
            if (s.endsWith(fn)) {
                buf.setLength(buf.length() - fn.length());
                return;
            }
        }
        if (buf.length() > 0) buf.setLength(buf.length() - 1);
    }

    /** Toggle the sign of the trailing number literal. */
    private void toggleSign() {
        if (justEquals) { justEquals = false; }  // edit the shown value
        String s = buf.toString();
        int i = s.length();
        while (i > 0) {
            char c = s.charAt(i - 1);
            if ((c >= '0' && c <= '9') || c == '.') i--;
            else break;
        }
        // [i, end) is the trailing number; i is its start
        if (i == s.length()) {
            // no trailing number: just start a negative group
            buf.append("−");
            return;
        }
        if (i > 0 && s.charAt(i - 1) == '−'
                && (i - 1 == 0 || isOpenOrOp(s.charAt(i - 2)))) {
            buf.deleteCharAt(i - 1);   // remove existing unary minus
        } else {
            buf.insert(i, "−");        // add unary minus
        }
    }

    private static boolean isOpenOrOp(char c) {
        return c == '(' || c == '+' || c == '−' || c == '×' || c == '÷' || c == '^';
    }

    private void doEquals() {
        if (buf.length() == 0) return;
        String expr = buf.toString();
        try {
            double v = Evaluator.eval(expr, degrees);
            String rs = format(v);
            addHistory(expr, rs);
            lastAns = v;
            resultView.setText(rs);
            resultView.setTextColor(cText);
            exprView.setText(expr);
            buf.setLength(0);
            buf.append(expr);        // keep for display; input decides fresh/chain
            justEquals = true;
            hasPreview = false;
        } catch (RuntimeException e) {
            resultView.setText("Error");
            resultView.setTextColor(cAccent2);
            hasPreview = false;
        }
    }

    // ---------- rendering ----------
    private void render() {
        exprView.setText(buf.toString());
    }

    private void updatePreview() {
        if (justEquals) return;
        if (buf.length() == 0) {
            resultView.setText("0");
            resultView.setTextColor(cText3);
            hasPreview = false;
            return;
        }
        try {
            double v = Evaluator.eval(buf.toString(), degrees);
            resultView.setText(format(v));
            resultView.setTextColor(cText3);   // dim = live preview
            hasPreview = true;
        } catch (RuntimeException e) {
            // incomplete/invalid expression: keep the last good preview
            if (!hasPreview) {
                resultView.setText("0");
                resultView.setTextColor(cText3);
            }
        }
    }

    // ---------- history ----------
    private void clearHistory() {
        historyList.removeAllViews();
        TextView ph = new TextView(this);
        ph.setText(R.string.no_history);
        ph.setTextColor(cText3);
        ph.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        ph.setPadding(dp(6), dp(10), dp(6), dp(10));
        ph.setTag("placeholder");
        historyList.addView(ph);
    }

    private void addHistory(String expr, String res) {
        if (historyList.getChildCount() > 0
                && "placeholder".equals(historyList.getChildAt(0).getTag())) {
            historyList.removeViewAt(0);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundResource(R.drawable.btn_ghost);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView e = new TextView(this);
        e.setText(expr);
        e.setTextColor(cText3);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        e.setMaxLines(1);
        e.setEllipsize(android.text.TextUtils.TruncateAt.START);
        e.setGravity(Gravity.END);

        TextView r = new TextView(this);
        r.setText("= " + res);
        r.setTextColor(cText);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        r.setMaxLines(1);
        r.setEllipsize(android.text.TextUtils.TruncateAt.START);
        r.setGravity(Gravity.END);

        row.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(r, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final String val = res;
        row.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            appendValue(val);
            render();
            updatePreview();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(4), dp(2), dp(4));
        historyList.addView(row, 0, lp);

        while (historyList.getChildCount() > 40) {
            historyList.removeViewAt(historyList.getChildCount() - 1);
        }
        historyScroll.smoothScrollTo(0, 0);
    }

    // ---------- number formatting ----------
    /** Human-friendly result string (trims zeros, uses sci notation at extremes). */
    private static String format(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "Error";
        if (v == 0.0) return "0";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        double a = Math.abs(v);
        String r;
        if (a < 1e-4 || a >= 1e12) {
            r = String.format(Locale.US, "%.6E", v);
            // tidy mantissa: strip trailing zeros before the E
            int ei = r.indexOf('E');
            String mant = r.substring(0, ei), exp = r.substring(ei);
            if (mant.contains(".")) {
                mant = mant.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
            return mant + exp;
        }
        r = String.format(Locale.US, "%.10f", v);
        if (r.contains(".")) {
            r = r.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return r;
    }

    /** Plain (non-exponent) decimal for insertion back into an expression. */
    private static String plain(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        BigDecimal bd = new BigDecimal(v).round(new java.math.MathContext(12));
        return bd.stripTrailingZeros().toPlainString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
