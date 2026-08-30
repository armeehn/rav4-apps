package com.reveng.currency;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Clean-room standalone Currency converter. Live exchange rates come from the
 * free, no-API-key open.er-api.com service (USD base). Networking runs on a
 * background {@link Thread}; UI updates post back through a {@link Handler}.
 * Rates + timestamp are cached in {@link SharedPreferences} so the app keeps
 * working offline with the last-known values.
 */
public class CurrencyActivity extends Activity {

    private static final String API = "https://open.er-api.com/v6/latest/USD";
    private static final String PREFS = "currency";
    private static final String K_RATES = "rates_json";
    private static final String K_UPDATED = "updated_utc";

    // ~22 major currencies (code, name, symbol). USD first so it is the default "from".
    private static final String[][] CURRENCIES = {
            { "USD", "US Dollar",            "$"   },
            { "EUR", "Euro",                 "€" },
            { "GBP", "British Pound",        "£" },
            { "JPY", "Japanese Yen",         "¥" },
            { "CNY", "Chinese Yuan",         "¥" },
            { "CAD", "Canadian Dollar",      "$"   },
            { "AUD", "Australian Dollar",    "$"   },
            { "CHF", "Swiss Franc",          "Fr"  },
            { "INR", "Indian Rupee",         "₹" },
            { "MXN", "Mexican Peso",         "$"   },
            { "BRL", "Brazilian Real",       "R$"  },
            { "KRW", "South Korean Won",     "₩" },
            { "SGD", "Singapore Dollar",     "$"   },
            { "HKD", "Hong Kong Dollar",     "$"   },
            { "SEK", "Swedish Krona",        "kr"  },
            { "NOK", "Norwegian Krone",      "kr"  },
            { "NZD", "New Zealand Dollar",   "$"   },
            { "ZAR", "South African Rand",   "R"   },
            { "RUB", "Russian Ruble",        "₽" },
            { "TRY", "Turkish Lira",         "₺" },
            { "AED", "UAE Dirham",           "د.إ" },
            { "THB", "Thai Baht",            "฿" },
    };

    // Design-system palette (mirrors res/values/colors.xml) for code-built cards.
    private static final int C_TEXT   = Color.parseColor("#FFF2F5FA");
    private static final int C_TEXT2  = Color.parseColor("#FFAAB3C2");
    private static final int C_TEXT3  = Color.parseColor("#FF6B7484");
    private static final int C_ACCENT = Color.parseColor("#FF5B9DFF");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Double> rates = new HashMap<>();
    private String updatedUtc = "";

    private EditText amount;
    private Spinner fromSpinner, toSpinner;
    private TextView result, rateLine, updated, status;
    private LinearLayout ratesList;
    private boolean ready = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        amount = findViewById(R.id.amount);
        fromSpinner = findViewById(R.id.fromSpinner);
        toSpinner = findViewById(R.id.toSpinner);
        result = findViewById(R.id.result);
        rateLine = findViewById(R.id.rateLine);
        updated = findViewById(R.id.updated);
        status = findViewById(R.id.status);
        ratesList = findViewById(R.id.ratesList);

        String[] labels = new String[CURRENCIES.length];
        for (int i = 0; i < CURRENCIES.length; i++) {
            labels[i] = CURRENCIES[i][0] + "  ·  " + CURRENCIES[i][2];
        }
        fromSpinner.setAdapter(new SpinnerAdapter(labels));
        toSpinner.setAdapter(new SpinnerAdapter(labels));
        fromSpinner.setSelection(0);   // USD
        toSpinner.setSelection(1);     // EUR

        AdapterView.OnItemSelectedListener sel = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { recompute(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        };
        fromSpinner.setOnItemSelectedListener(sel);
        toSpinner.setOnItemSelectedListener(sel);

        amount.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { recompute(); }
        });
        amount.setText("1");

        ((ImageButton) findViewById(R.id.swapBtn)).setOnClickListener(v -> {
            int f = fromSpinner.getSelectedItemPosition();
            int t = toSpinner.getSelectedItemPosition();
            fromSpinner.setSelection(t);
            toSpinner.setSelection(f);
            recompute();
        });
        ((ImageButton) findViewById(R.id.refreshBtn)).setOnClickListener(v -> fetchRates());

        loadCache();       // populate from last-known rates so the UI is never empty
        ready = true;
        recompute();
        fetchRates();      // then refresh from the network
    }

    // ---------------------------------------------------------------- cache

    private void loadCache() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = sp.getString(K_RATES, null);
        updatedUtc = sp.getString(K_UPDATED, "");
        if (json != null) {
            try {
                parseRates(new JSONObject(json));
            } catch (Exception ignore) { }
        }
        renderRates();
        renderUpdated(updatedUtc.isEmpty() ? getString(R.string.source) : "Rates from " + updatedUtc);
    }

    private void saveCache(JSONObject ratesObj) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_RATES, ratesObj.toString())
                .putString(K_UPDATED, updatedUtc)
                .apply();
    }

    private void parseRates(JSONObject ratesObj) {
        rates.clear();
        Iterator<String> keys = ratesObj.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            rates.put(k, ratesObj.optDouble(k, Double.NaN));
        }
        rates.put("USD", 1.0);
    }

    // ---------------------------------------------------------------- network

    private void fetchRates() {
        status.setText(R.string.updating);
        new Thread(() -> {
            try {
                JSONObject root = new JSONObject(httpGet(API));
                if (!"success".equals(root.optString("result"))) {
                    throw new Exception("API error");
                }
                final JSONObject r = root.getJSONObject("rates");
                final String when = root.optString("time_last_update_utc", "");
                ui.post(() -> {
                    parseRates(r);
                    updatedUtc = shorten(when);
                    saveCache(r);
                    status.setText("");
                    renderRates();
                    renderUpdated("Updated " + updatedUtc);
                    recompute();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (rates.isEmpty()) {
                        status.setText("Offline — no cached rates");
                        renderUpdated("Connect to load rates");
                    } else {
                        status.setText("Offline");
                        renderUpdated("Cached rates from " + (updatedUtc.isEmpty() ? "earlier" : updatedUtc));
                    }
                });
            }
        }).start();
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "reveng-currency/1.0");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------------------------------------------------------------- convert

    private void recompute() {
        if (!ready) return;
        String from = CURRENCIES[fromSpinner.getSelectedItemPosition()][0];
        String to = CURRENCIES[toSpinner.getSelectedItemPosition()][0];
        String toSym = CURRENCIES[toSpinner.getSelectedItemPosition()][2];

        double amt = parseAmount(amount.getText().toString());
        Double rf = rates.get(from), rt = rates.get(to);

        if (rf == null || rt == null || rf.isNaN() || rt.isNaN() || rf == 0) {
            result.setText("—");
            rateLine.setText("Rate unavailable for " + from + "/" + to);
            return;
        }
        // USD-base conversion: amount / rate_from * rate_to
        double converted = amt / rf * rt;
        double unit = 1.0 / rf * rt;   // 1 <from> in <to>

        result.setText(toSym + " " + money(converted));
        rateLine.setText("1 " + from + " = " + trim(unit) + " " + to);
    }

    private static double parseAmount(String s) {
        if (s == null) return 0;
        s = s.trim().replace(",", "");
        if (s.isEmpty()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    // ---------------------------------------------------------------- render

    private void renderRates() {
        ratesList.removeAllViews();
        for (String[] c : CURRENCIES) {
            if ("USD".equals(c[0])) continue;   // base
            Double v = rates.get(c[0]);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);
            row.setBackgroundResource(R.drawable.bg_card);
            row.setPadding(dp(18), dp(14), dp(18), dp(14));

            // symbol badge
            TextView sym = new TextView(this);
            sym.setText(c[2]);
            sym.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            sym.setTextColor(C_ACCENT);
            sym.setTypeface(sym.getTypeface(), Typeface.BOLD);
            sym.setGravity(Gravity.CENTER);
            sym.setWidth(dp(40));
            row.addView(sym);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.setMarginStart(dp(6));
            col.setLayoutParams(clp);
            TextView code = new TextView(this);
            code.setText(c[0]);
            code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            code.setTextColor(C_TEXT);
            code.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            TextView name = new TextView(this);
            name.setText(c[1]);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            name.setTextColor(C_TEXT3);
            col.addView(code);
            col.addView(name);
            row.addView(col);

            TextView val = new TextView(this);
            val.setText(v == null || v.isNaN() ? "—" : trim(v));
            val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            val.setTextColor(C_TEXT);
            val.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            val.setGravity(Gravity.END);
            row.addView(val);

            ratesList.addView(row);
        }
    }

    private void renderUpdated(String s) {
        updated.setText(s + "  ·  " + getString(R.string.source));
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat SMALL = new DecimalFormat("#,##0.0000");

    /** Format a converted amount with thousands separators. */
    private static String money(double v) {
        if (Double.isNaN(v)) return "—";
        if (Math.abs(v) != 0 && Math.abs(v) < 0.01) return SMALL.format(v);
        return MONEY.format(v);
    }

    /** Compact rate: more decimals for tiny values, fewer for large. */
    private static String trim(double v) {
        if (Double.isNaN(v)) return "—";
        double a = Math.abs(v);
        if (a >= 100) return new DecimalFormat("#,##0.00").format(v);
        if (a >= 1)   return new DecimalFormat("#,##0.000").format(v);
        return new DecimalFormat("0.0000").format(v);
    }

    /** open.er-api returns e.g. "Wed, 27 Aug 2026 00:02:31 +0000"; drop the trailing zone. */
    private static String shorten(String utc) {
        if (utc == null) return "";
        int plus = utc.indexOf(" +");
        String s = plus > 0 ? utc.substring(0, plus) : utc;
        return s.replace(" 00:00:00", "").trim();
    }

    // ---------------------------------------------------------------- spinner

    /** ArrayAdapter that paints spinner text in the design-system palette. */
    private class SpinnerAdapter extends ArrayAdapter<String> {
        SpinnerAdapter(String[] items) {
            super(CurrencyActivity.this, android.R.layout.simple_spinner_item, items);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            tv.setTextColor(C_TEXT);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            return tv;
        }

        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
            tv.setTextColor(C_TEXT);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tv.setPadding(dp(16), dp(14), dp(16), dp(14));
            return tv;
        }
    }
}
