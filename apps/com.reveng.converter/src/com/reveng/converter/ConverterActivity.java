package com.reveng.converter;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import com.reveng.design.Palette;

/**
 * Clean-room standalone unit converter. Pure android.* (no AndroidX).
 * Factor-based categories convert through a base unit; temperature uses
 * offset math (C/F/K). Live conversion as the user types.
 */
public class ConverterActivity extends Activity {

    /** A single category of units. For factor categories, value_base = value * factor. */
    static final class Category {
        final String name;          // display name
        final String sub;           // subtitle / base description
        final String[] units;       // unit labels
        final double[] factors;     // multiplier to the base unit (unused for temperature)
        final boolean temperature;  // true -> C/F/K offset math
        Category(String name, String sub, String[] units, double[] factors, boolean temperature) {
            this.name = name; this.sub = sub; this.units = units;
            this.factors = factors; this.temperature = temperature;
        }
    }

    private final Category[] cats = buildCategories();

    private int catIndex = 0;
    private EditText input;
    private TextView output, catTitle, catSub;
    private Spinner fromUnit, toUnit;
    private LinearLayout catList;
    private ImageButton swapBtn;
    private boolean building = false;

    private static Category[] buildCategories() {
        return new Category[] {
            new Category("Length", "Base: meter",
                new String[]{"Kilometre (km)","Metre (m)","Centimetre (cm)","Millimetre (mm)",
                    "Mile (mi)","Yard (yd)","Foot (ft)","Inch (in)","Nautical mile (nmi)"},
                new double[]{1000, 1, 0.01, 0.001, 1609.344, 0.9144, 0.3048, 0.0254, 1852}, false),

            new Category("Weight / Mass", "Base: kilogram",
                new String[]{"Tonne (t)","Kilogram (kg)","Gram (g)","Milligram (mg)",
                    "Pound (lb)","Ounce (oz)","Stone (st)"},
                new double[]{1000, 1, 0.001, 1e-6, 0.45359237, 0.028349523125, 6.35029318}, false),

            new Category("Temperature", "Celsius / Fahrenheit / Kelvin",
                new String[]{"Celsius (°C)","Fahrenheit (°F)","Kelvin (K)"},
                null, true),

            new Category("Speed", "Base: metre/second",
                new String[]{"Metre/second (m/s)","Kilometre/hour (km/h)","Mile/hour (mph)",
                    "Knot (kn)","Foot/second (ft/s)"},
                new double[]{1, 0.2777777777777778, 0.44704, 0.5144444444444445, 0.3048}, false),

            new Category("Volume", "Base: litre",
                new String[]{"Litre (L)","Millilitre (mL)","Cubic metre (m³)",
                    "Gallon US (gal)","Quart US (qt)","Pint US (pt)","Cup US","Fluid ounce US (fl oz)",
                    "Gallon UK (gal)"},
                new double[]{1, 0.001, 1000, 3.785411784, 0.946352946, 0.473176473,
                    0.2365882365, 0.0295735295625, 4.54609}, false),

            new Category("Area", "Base: square metre",
                new String[]{"Square kilometre (km²)","Square metre (m²)",
                    "Square centimetre (cm²)","Hectare (ha)","Square foot (ft²)",
                    "Acre (ac)","Square mile (mi²)"},
                new double[]{1e6, 1, 1e-4, 10000, 0.09290304, 4046.8564224, 2589988.110336}, false),

            new Category("Time", "Base: second",
                new String[]{"Millisecond (ms)","Second (s)","Minute (min)","Hour (h)",
                    "Day","Week","Year"},
                new double[]{0.001, 1, 60, 3600, 86400, 604800, 31557600}, false),

            new Category("Data storage", "Base: byte",
                new String[]{"Bit","Byte (B)","Kilobyte (KB)","Megabyte (MB)","Gigabyte (GB)",
                    "Terabyte (TB)","Kibibyte (KiB)","Mebibyte (MiB)","Gibibyte (GiB)"},
                new double[]{0.125, 1, 1e3, 1e6, 1e9, 1e12, 1024, 1048576, 1073741824.0}, false),

            new Category("Pressure", "Base: pascal",
                new String[]{"Pascal (Pa)","Kilopascal (kPa)","Bar","PSI","Atmosphere (atm)",
                    "mmHg (torr)"},
                new double[]{1, 1000, 100000, 6894.757293168, 101325, 133.322387415}, false),

            new Category("Energy", "Base: joule",
                new String[]{"Joule (J)","Kilojoule (kJ)","Calorie (cal)","Kilocalorie (kcal)",
                    "Watt-hour (Wh)","Kilowatt-hour (kWh)","BTU","Electronvolt (eV)"},
                new double[]{1, 1000, 4.184, 4184, 3600, 3.6e6, 1055.05585262, 1.602176634e-19}, false),
        };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        input = (EditText) findViewById(R.id.input);
        output = (TextView) findViewById(R.id.output);
        catTitle = (TextView) findViewById(R.id.catTitle);
        catSub = (TextView) findViewById(R.id.catSub);
        fromUnit = (Spinner) findViewById(R.id.fromUnit);
        toUnit = (Spinner) findViewById(R.id.toUnit);
        catList = (LinearLayout) findViewById(R.id.catList);
        swapBtn = (ImageButton) findViewById(R.id.swapBtn);

        buildRail();

        AdapterView.OnItemSelectedListener sel = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { compute(); }
            public void onNothingSelected(AdapterView<?> p) {}
        };
        fromUnit.setOnItemSelectedListener(sel);
        toUnit.setOnItemSelectedListener(sel);

        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) { compute(); }
        });

        swapBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int f = fromUnit.getSelectedItemPosition();
                int t = toUnit.getSelectedItemPosition();
                building = true;
                fromUnit.setSelection(t);
                toUnit.setSelection(f);
                // Seed the input with the current output so the swap keeps the reading.
                String o = output.getText().toString().replace("−", "-");
                if (isNumeric(o)) input.setText(o);
                building = false;
                compute();
            }
        });

        selectCategory(0);
    }

    private void buildRail() {
        catList.removeAllViews();
        int pad = dp(14);
        for (int i = 0; i < cats.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setText(cats[i].name);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setMinHeight(dp(52));
            tv.setGravity(Gravity.CENTER_VERTICAL);
            tv.setPadding(pad, dp(12), pad, dp(12));
            tv.setBackgroundResource(R.drawable.btn_ghost);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { selectCategory(idx); }
            });
            catList.addView(tv);
        }
    }

    private void selectCategory(int idx) {
        catIndex = idx;
        Category c = cats[idx];
        catTitle.setText(c.name);
        catSub.setText(c.sub);

        // Highlight the active rail card.
        for (int i = 0; i < catList.getChildCount(); i++) {
            TextView tv = (TextView) catList.getChildAt(i);
            boolean on = (i == idx);
            tv.setBackgroundResource(on ? R.drawable.btn_ghost : R.drawable.btn_ghost);
            tv.setTextColor(on ? 0xFF5B9DFF : 0xFFF2F5FA);
            tv.getPaint().setFakeBoldText(on);
            tv.setAlpha(on ? 1f : 0.72f);
        }

        building = true;
        ArrayAdapter<String> fa = makeAdapter(c.units);
        ArrayAdapter<String> ta = makeAdapter(c.units);
        fromUnit.setAdapter(fa);
        toUnit.setAdapter(ta);
        fromUnit.setSelection(0);
        toUnit.setSelection(c.units.length > 1 ? 1 : 0);
        if (input.getText().length() == 0) input.setText("1");
        building = false;
        compute();
    }

    private ArrayAdapter<String> makeAdapter(String[] items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, R.layout.spinner_item, items);
        a.setDropDownViewResource(R.layout.spinner_dropdown);
        return a;
    }

    private void compute() {
        if (building) return;
        Category c = cats[catIndex];
        int fi = fromUnit.getSelectedItemPosition();
        int ti = toUnit.getSelectedItemPosition();
        if (fi < 0 || ti < 0) return;

        String raw = input.getText().toString().trim();
        if (raw.length() == 0 || raw.equals("-") || raw.equals(".") || raw.equals("-.")) {
            output.setText("");
            return;
        }
        double x;
        try { x = Double.parseDouble(raw); }
        catch (NumberFormatException ex) { output.setText("—"); return; }

        double result;
        if (c.temperature) {
            result = convertTemp(x, fi, ti);
        } else {
            double base = x * c.factors[fi];
            result = base / c.factors[ti];
        }
        output.setText(format(result));
    }

    /** Temperature via Celsius pivot. 0=C, 1=F, 2=K. */
    private double convertTemp(double v, int from, int to) {
        double celsius;
        switch (from) {
            case 1: celsius = (v - 32.0) * 5.0 / 9.0; break; // F
            case 2: celsius = v - 273.15; break;             // K
            default: celsius = v;                            // C
        }
        switch (to) {
            case 1: return celsius * 9.0 / 5.0 + 32.0;
            case 2: return celsius + 273.15;
            default: return celsius;
        }
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.length() == 0) return false;
        try { Double.parseDouble(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    /** Format sensibly: trim trailing zeros, scientific notation for extremes. */
    private static String format(double v) {
        if (Double.isNaN(v)) return "—";
        if (Double.isInfinite(v)) return v > 0 ? "∞" : "-∞";
        if (v == 0.0) return "0";
        double a = Math.abs(v);
        String out;
        if (a >= 1e12 || a < 1e-6) {
            // Scientific with up to 6 significant digits.
            BigDecimal bd = new BigDecimal(v, new MathContext(6));
            out = bd.round(new MathContext(6)).toString();
            if (out.indexOf('E') < 0) {
                // Force scientific form.
                out = String.format("%.5E", v);
            }
            out = cleanSci(out);
        } else {
            BigDecimal bd = new BigDecimal(v).round(new MathContext(10));
            // Limit to 6 decimal places then strip zeros.
            bd = bd.setScale(Math.max(0, 6), RoundingMode.HALF_UP);
            out = bd.stripTrailingZeros().toPlainString();
        }
        return out;
    }

    private static String cleanSci(String s) {
        // Normalise "1.230000E5" -> "1.23E5".
        int e = s.indexOf('E');
        if (e < 0) return s;
        String mant = s.substring(0, e);
        String exp = s.substring(e);
        if (mant.indexOf('.') >= 0) {
            int end = mant.length();
            while (end > 0 && mant.charAt(end - 1) == '0') end--;
            if (end > 0 && mant.charAt(end - 1) == '.') end--;
            mant = mant.substring(0, end);
        }
        return mant + exp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
