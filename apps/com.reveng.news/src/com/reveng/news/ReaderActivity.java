package com.reveng.news;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * In-app article reader built on the framework {@link WebView} (no AndroidX).
 * Loads the tapped headline's URL; a globe button hands off to an external
 * browser via {@code ACTION_VIEW} when the user prefers it.
 */
public class ReaderActivity extends Activity {

    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";

    private WebView web;
    private ProgressBar progress;
    private TextView urlView;
    private ImageButton reloadBtn;
    private String url;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        web = findViewById(R.id.reader);
        progress = findViewById(R.id.readerProgress);
        urlView = findViewById(R.id.readerUrl);
        reloadBtn = findViewById(R.id.reloadBtn);
        TextView titleView = findViewById(R.id.readerTitle);

        url = getIntent().getStringExtra(EXTRA_URL);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null && !title.isEmpty()) titleView.setText(title);
        if (url != null) urlView.setText(url);

        findViewById(R.id.backBtn).setOnClickListener(v -> finish());
        reloadBtn.setOnClickListener(v -> { if (loading) web.stopLoading(); else web.reload(); });
        findViewById(R.id.openBtn).setOnClickListener(v -> openExternal());

        configureWebView();

        if (url != null && !url.isEmpty()) {
            web.loadUrl(url);
        } else {
            Toast.makeText(this, "No article link", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                view.loadUrl(req.getUrl().toString());   // keep navigation in-app
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                if (u != null && !u.startsWith("about:")) urlView.setText(u);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                loading = p < 100;
                progress.setProgress(p);
                progress.setVisibility(loading ? View.VISIBLE : View.GONE);
                reloadBtn.setImageResource(loading ? R.drawable.ic_stop : R.drawable.ic_refresh);
            }
        });
    }

    private void openExternal() {
        String u = web.getUrl();
        if (u == null || u.isEmpty()) u = url;
        if (u == null || u.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
