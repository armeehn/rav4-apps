package com.ripostelabs.clock;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the shared design-system UI in code. */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** TextView created against one of the design-system text styles. */
    static TextView styled(Context c, int styleRes) {
        return new TextView(c, null, 0, styleRes);
    }

    static TextView text(Context c, int styleRes, CharSequence s) {
        TextView t = styled(c, styleRes);
        t.setText(s);
        return t;
    }

    /** Circular icon button using the shared ripple mask, tinted from the palette. */
    static ImageButton iconButton(Context c, int iconRes, int tint, int sizeDp) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(iconRes);
        b.setBackgroundResource(R.drawable.btn_icon);
        b.setColorFilter(tint);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, sizeDp * 0.26f);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    /** Accent-filled circular action button (FAB style). */
    static ImageButton fab(Context c, int iconRes, int sizeDp) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(iconRes);
        b.setBackgroundResource(R.drawable.btn_fab);
        b.setColorFilter(0xFFFFFFFF);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, sizeDp * 0.28f);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    /** A rounded card container (bg_card) with padding. */
    static LinearLayout card(Context c, int orientation, int padDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        l.setBackgroundResource(R.drawable.bg_card);
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** A pill button with accent or ghost background and centered text. */
    static TextView pill(Context c, CharSequence label, boolean accent, int textColor) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", 0));
        t.setTextColor(textColor);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(accent ? R.drawable.btn_accent : R.drawable.btn_ghost);
        int ph = dp(c, 22), pv = dp(c, 13);
        t.setPadding(ph, pv, ph, pv);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static LinearLayout.LayoutParams row(int h) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static GradientDrawable roundedFill(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }
}
