package com.reveng.browser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Clean-room standalone Browser built on the framework {@link WebView}. No
 * AndroidX, no support libraries -- plain android.* only. Presents a home page of
 * quick-link cards, a navigation toolbar, and an address bar that treats non-URL
 * input as a Google search. The last visited URL is persisted in SharedPreferences.
 */
public class BrowserActivity extends Activity {

    private static final String PREFS = "browser_prefs";
    private static final String KEY_LAST_URL = "last_url";

    private WebView web;
    private EditText address;
    private ProgressBar progress;
    private ScrollView homeView;
    private ImageButton refreshBtn;

    private boolean loading = false;

    // Quick links shown on the home page: label + URL.
    private static final String[][] QUICK_LINKS = {
            { "Google",    "https://www.google.com" },
            { "YouTube",   "https://m.youtube.com" },
            { "Maps",      "https://maps.google.com" },
            { "Wikipedia", "https://en.wikipedia.org" },
            { "Weather",   "https://weather.com" },
            { "News",      "https://news.google.com" },
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.webview);
        address = findViewById(R.id.addressInput);
        progress = findViewById(R.id.progress);
        homeView = findViewById(R.id.homeView);
        refreshBtn = findViewById(R.id.refreshBtn);

        configureWebView();
        buildQuickLinks();

        findViewById(R.id.backBtn).setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        findViewById(R.id.forwardBtn).setOnClickListener(v -> { if (web.canGoForward()) web.goForward(); });
        refreshBtn.setOnClickListener(v -> { if (loading) web.stopLoading(); else web.reload(); });
        findViewById(R.id.homeBtn).setOnClickListener(v -> showHome());

        address.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitAddress();
                return true;
            }
            return false;
        });

        // Restore last session, or land on the home page.
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String last = sp.getString(KEY_LAST_URL, null);
        if (last != null && !last.isEmpty()) {
            hideHome();
            web.loadUrl(last);
        } else {
            showHome();
        }
    }

    // ---------------------------------------------------------------- WebView

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Modern desktop-ish UA so sites serve their full experience.
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                view.loadUrl(req.getUrl().toString());  // keep navigation in-app
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                setAddressUnlessFocused(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                setAddressUnlessFocused(url);
                saveLastUrl(url);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                loading = newProgress < 100;
                progress.setProgress(newProgress);
                progress.setVisibility(loading ? View.VISIBLE : View.GONE);
                refreshBtn.setImageResource(loading ? R.drawable.ic_stop : R.drawable.ic_refresh);
            }
        });
    }

    private void setAddressUnlessFocused(String url) {
        if (url != null && !url.startsWith("about:") && !address.hasFocus()) {
            address.setText(url);
        }
    }

    // ---------------------------------------------------------------- home page

    private void buildQuickLinks() {
        GridLayout grid = findViewById(R.id.quickGrid);
        for (String[] link : QUICK_LINKS) {
            grid.addView(makeCard(link[0], link[1]));
        }
    }

    /** Build one quick-link card: globe icon over a label, tappable to load the URL. */
    private View makeCard(final String label, final String url) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClickable(true);
        card.setFocusable(true);
        // Ripple foreground for touch feedback over the rounded card.
        int[] attrs = { android.R.attr.selectableItemBackgroundBorderless };
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        card.setForeground(ta.getDrawable(0));
        ta.recycle();
        card.setPadding(dp(16), dp(22), dp(16), dp(22));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_globe);
        icon.setColorFilter(Color.parseColor("#FF5B9DFF"));  // @color/accent
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(40), dp(40));
        icon.setLayoutParams(ilp);
        card.addView(icon);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#FFF2F5FA"));      // @color/text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(12);
        tv.setLayoutParams(tlp);
        card.addView(tv);

        GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
        glp.width = dp(168);
        glp.height = dp(150);
        glp.setMargins(dp(10), dp(10), dp(10), dp(10));
        card.setLayoutParams(glp);

        card.setOnClickListener(v -> navigate(url));
        return card;
    }

    private void showHome() {
        homeView.setVisibility(View.VISIBLE);
        web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.GONE);
        address.setText("");
        hideKeyboard();
    }

    private void hideHome() {
        homeView.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- navigation

    private void submitAddress() {
        String raw = address.getText().toString().trim();
        if (raw.isEmpty()) return;
        hideKeyboard();
        address.clearFocus();
        navigate(normalizeUrl(raw));
    }

    private void navigate(String url) {
        hideHome();
        web.loadUrl(url);
    }

    /** Turn user input into a URL: keep real URLs, otherwise Google-search it. */
    private static String normalizeUrl(String input) {
        String s = input.trim();
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("about:")) {
            return s;
        }
        // Looks like a domain (has a dot, no spaces) -> treat as URL, add scheme.
        if (!s.contains(" ") && s.contains(".") && !s.startsWith(".") && !s.endsWith(".")) {
            return "https://" + s;
        }
        // Otherwise a search query.
        try {
            return "https://www.google.com/search?q="
                    + java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + s.replace(" ", "+");
        }
    }

    private void saveLastUrl(String url) {
        if (url == null || url.isEmpty() || url.startsWith("about:")) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_URL, url).apply();
    }

    // ---------------------------------------------------------------- misc

    @Override
    public void onBackPressed() {
        if (homeView.getVisibility() != View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else if (homeView.getVisibility() != View.VISIBLE) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
