package com.ripostelabs.news;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.ripostelabs.design.Palette;

/**
 * Clean-room standalone News (RSS/Atom reader). No AndroidX, no support libraries
 * -- plain android.* only. Feeds are fetched on background {@link Thread}s via
 * {@link HttpURLConnection} and parsed with the framework {@link XmlPullParser}.
 * A left rail selects the source; tapping a headline opens {@link ReaderActivity}
 * (an in-app WebView). Results are cached in memory per source.
 */
public class NewsActivity extends Activity {

    /** In-code source list: display name + RSS/Atom URL. Index 0 is synthetic ("Top Stories"). */
    private static final String[][] SOURCES = {
            { "BBC News",     "https://feeds.bbci.co.uk/news/rss.xml" },
            { "NPR",          "https://feeds.npr.org/1001/rss.xml" },
            { "Ars Technica", "https://feeds.arstechnica.com/arstechnica/index" },
            { "Hacker News",  "https://hnrss.org/frontpage" },
            { "The Verge",    "https://www.theverge.com/rss/index.xml" },
    };

    private final Handler ui = new Handler(Looper.getMainLooper());

    // In-memory cache: source name -> parsed items.
    private final Map<String, List<Item>> cache = new HashMap<>();
    // Per-source error message (null when the last fetch succeeded).
    private final Map<String, String> errors = new HashMap<>();

    private LinearLayout sourceList, headlineList;
    private TextView feedTitle, feedStatus;
    private ScrollView headlineScroll;
    private ImageView refreshIcon;

    private final List<View> sourceRows = new ArrayList<>();
    private int selected = 0;                 // 0 = Top Stories, else 1..N -> SOURCES[selected-1]
    private int generation = 0;               // bumped on each load to discard stale threads
    private int pending = 0;                  // feeds still in flight for the current generation

    // Palette mirrored from res/values/colors.xml for code-built views.
    private static final int C_TEXT   = Color.parseColor("#FFF2F5FA");
    private static final int C_TEXT2  = Color.parseColor("#FFAAB3C2");
    private static final int C_TEXT3  = Color.parseColor("#FF6B7484");
    private static final int C_ACCENT = Color.parseColor("#FF5B9DFF");

    /** One headline. */
    static final class Item {
        String title, link, source, snippet;
        long time;     // epoch millis, 0 if unknown
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_news);

        sourceList = findViewById(R.id.sourceList);
        headlineList = findViewById(R.id.headlineList);
        headlineScroll = findViewById(R.id.headlineScroll);
        feedTitle = findViewById(R.id.feedTitle);
        feedStatus = findViewById(R.id.feedStatus);
        refreshIcon = findViewById(R.id.refreshIcon);

        findViewById(R.id.refreshBtn).setOnClickListener(v -> refresh());

        buildSourceRows();
        selectSource(0);   // land on Top Stories and load everything
    }

    // ---------------------------------------------------------------- source rail

    private void buildSourceRows() {
        sourceRows.clear();
        sourceList.removeAllViews();
        addSourceRow(0, getString(R.string.top_stories), R.drawable.ic_news);
        for (int i = 0; i < SOURCES.length; i++) {
            addSourceRow(i + 1, SOURCES[i][0], R.drawable.ic_globe);
        }
    }

    private void addSourceRow(final int index, String label, int iconRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(C_ACCENT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        icon.setLayoutParams(ilp);
        row.addView(icon);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(C_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setMaxLines(1);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        tv.setLayoutParams(tlp);
        row.addView(tv);

        row.setOnClickListener(v -> selectSource(index));
        sourceList.addView(row);
        sourceRows.add(row);
    }

    private void highlightSelected() {
        for (int i = 0; i < sourceRows.size(); i++) {
            View row = sourceRows.get(i);
            if (i == selected) {
                row.setBackgroundResource(R.drawable.bg_card);
                ((TextView) ((LinearLayout) row).getChildAt(1)).setTypeface(null, Typeface.BOLD);
            } else {
                row.setBackground(null);
                ((TextView) ((LinearLayout) row).getChildAt(1)).setTypeface(null, Typeface.NORMAL);
            }
        }
    }

    // ---------------------------------------------------------------- selection / load

    private String sourceName(int index) {
        return index == 0 ? getString(R.string.top_stories) : SOURCES[index - 1][0];
    }

    private void selectSource(int index) {
        selected = index;
        highlightSelected();
        feedTitle.setText(sourceName(index));
        headlineScroll.scrollTo(0, 0);

        if (hasData(index)) {
            render();
        } else {
            loadForSelection();
        }
    }

    /** True if we already have cached items for the current selection. */
    private boolean hasData(int index) {
        if (index == 0) {
            for (String[] s : SOURCES) {
                if (cache.containsKey(s[0])) return true;
            }
            return false;
        }
        return cache.containsKey(SOURCES[index - 1][0]);
    }

    private void refresh() {
        // Drop cached copies for the current selection so the fetch is fresh.
        if (selected == 0) {
            cache.clear();
            errors.clear();
        } else {
            cache.remove(SOURCES[selected - 1][0]);
            errors.remove(SOURCES[selected - 1][0]);
        }
        loadForSelection();
    }

    private void loadForSelection() {
        final int gen = ++generation;
        headlineList.removeAllViews();
        showLoading();

        if (selected == 0) {
            pending = SOURCES.length;
            for (String[] s : SOURCES) fetch(s[0], s[1], gen);
        } else {
            pending = 1;
            fetch(SOURCES[selected - 1][0], SOURCES[selected - 1][1], gen);
        }
    }

    /** Fetch + parse one feed on a background thread, then merge on the UI thread. */
    private void fetch(final String name, final String url, final int gen) {
        new Thread(() -> {
            List<Item> items = null;
            String err = null;
            try {
                items = parseFeed(name, url);
                if (items.isEmpty()) err = "empty feed";
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final List<Item> fItems = items;
            final String fErr = err;
            ui.post(() -> {
                if (gen != generation) return;   // a newer load superseded this one
                if (fItems != null && !fItems.isEmpty()) {
                    cache.put(name, fItems);
                    errors.remove(name);
                } else {
                    errors.put(name, fErr);
                }
                pending--;
                render();
            });
        }).start();
    }

    // ---------------------------------------------------------------- rendering

    private void showLoading() {
        feedStatus.setText(R.string.loading);
        headlineList.removeAllViews();
        LinearLayout box = messageBox();
        addMessageText(box, getString(R.string.loading), C_TEXT2, 16, true);
        headlineList.addView(box);
    }

    private void render() {
        List<Item> items = currentItems();

        // Status line.
        if (pending > 0) {
            feedStatus.setText(getString(R.string.loading));
        } else if (items.isEmpty()) {
            feedStatus.setText(currentErrorSummary());
        } else {
            String updated = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            String suffix = "";
            String errs = currentErrorSummary();
            if (errs != null) suffix = "  •  " + errs;
            feedStatus.setText(items.size() + " stories  •  updated " + updated + suffix);
        }

        headlineList.removeAllViews();

        if (items.isEmpty()) {
            if (pending > 0) {
                LinearLayout box = messageBox();
                addMessageText(box, getString(R.string.loading), C_TEXT2, 16, true);
                headlineList.addView(box);
            } else {
                renderEmptyOrError();
            }
            return;
        }

        for (Item it : items) headlineList.addView(makeCard(it));
    }

    /** Items for the current selection: a single feed, or a merged/sorted Top list. */
    private List<Item> currentItems() {
        List<Item> out = new ArrayList<>();
        if (selected == 0) {
            for (String[] s : SOURCES) {
                List<Item> l = cache.get(s[0]);
                if (l != null) out.addAll(l);
            }
            Collections.sort(out, new Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    return Long.compare(b.time, a.time);   // newest first
                }
            });
            if (out.size() > 60) out = new ArrayList<>(out.subList(0, 60));
        } else {
            List<Item> l = cache.get(SOURCES[selected - 1][0]);
            if (l != null) out.addAll(l);
        }
        return out;
    }

    /** Human summary of any per-source errors relevant to the current selection. */
    private String currentErrorSummary() {
        List<String> failed = new ArrayList<>();
        if (selected == 0) {
            for (String[] s : SOURCES) {
                if (errors.containsKey(s[0]) && !cache.containsKey(s[0])) failed.add(s[0]);
            }
        } else {
            String n = SOURCES[selected - 1][0];
            if (errors.containsKey(n) && !cache.containsKey(n)) failed.add(n);
        }
        if (failed.isEmpty()) return null;
        if (failed.size() == 1) return getString(R.string.error) + ": " + failed.get(0);
        return failed.size() + " feeds unavailable";
    }

    private void renderEmptyOrError() {
        LinearLayout box = messageBox();
        String summary = currentErrorSummary();
        if (summary != null) {
            addMessageText(box, "⚠", C_TEXT3, 40, false);
            addMessageText(box, summary, C_TEXT, 18, true);
            addMessageText(box, "Check the connection and tap refresh to try again.",
                    C_TEXT3, 14, false);
        } else {
            addMessageText(box, getString(R.string.empty), C_TEXT, 18, true);
            addMessageText(box, getString(R.string.empty_hint), C_TEXT3, 14, false);
        }
        headlineList.addView(box);
    }

    private LinearLayout messageBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(60), dp(24), dp(60));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        box.setLayoutParams(lp);
        return box;
    }

    private void addMessageText(LinearLayout box, String s, int color, int sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setGravity(Gravity.CENTER);
        if (bold) tv.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        tv.setLayoutParams(lp);
        box.addView(tv);
    }

    /** One headline card: source/time overline-ish caption, H2 title, snippet. */
    private View makeCard(final Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setClickable(true);
        card.setFocusable(true);
        int[] attrs = { android.R.attr.selectableItemBackground };
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        card.setForeground(ta.getDrawable(0));
        ta.recycle();
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);

        // meta: SOURCE  •  relative time
        TextView meta = new TextView(this);
        String rel = relativeTime(it.time);
        meta.setText(rel == null ? it.source.toUpperCase(Locale.US)
                : it.source.toUpperCase(Locale.US) + "   •   " + rel);
        meta.setTextColor(C_ACCENT);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        meta.setLetterSpacing(0.08f);
        meta.setTypeface(null, Typeface.BOLD);
        card.addView(meta);

        // title
        TextView title = new TextView(this);
        title.setText(it.title);
        title.setTextColor(C_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(6);
        title.setLayoutParams(tlp);
        card.addView(title);

        // snippet
        if (it.snippet != null && !it.snippet.isEmpty()) {
            TextView snip = new TextView(this);
            snip.setText(it.snippet);
            snip.setTextColor(C_TEXT2);
            snip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            snip.setMaxLines(2);
            snip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            snip.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(6);
            snip.setLayoutParams(slp);
            card.addView(snip);
        }

        card.setOnClickListener(v -> openArticle(it));
        return card;
    }

    private void openArticle(Item it) {
        if (it.link == null || it.link.isEmpty()) return;
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_URL, it.link);
        i.putExtra(ReaderActivity.EXTRA_TITLE, it.title);
        startActivity(i);
    }

    // ---------------------------------------------------------------- fetch + parse

    private List<Item> parseFeed(String source, String urlStr) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(12000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "riposte-news/1.0");
            conn.setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*");
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            in = conn.getInputStream();
            return parseXml(source, in);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignore) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Stream-parse both RSS (&lt;item&gt;) and Atom (&lt;entry&gt;) with XmlPullParser.
     * Collects title, link, publication time and a short text snippet per entry.
     */
    private List<Item> parseXml(String source, InputStream in) throws Exception {
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser p = f.newPullParser();
        p.setInput(in, null);

        List<Item> items = new ArrayList<>();
        Item cur = null;
        boolean inItem = false;
        String text = null;

        int ev = p.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = p.getName();
                if (tag.equalsIgnoreCase("item") || tag.equalsIgnoreCase("entry")) {
                    inItem = true;
                    cur = new Item();
                    cur.source = source;
                } else if (inItem && cur != null) {
                    if (tag.equalsIgnoreCase("link")) {
                        // Atom: <link href="..." rel="alternate"/>; RSS: text content.
                        String href = p.getAttributeValue(null, "href");
                        String rel = p.getAttributeValue(null, "rel");
                        if (href != null) {
                            if (cur.link == null || rel == null || rel.equalsIgnoreCase("alternate")) {
                                cur.link = href;
                            }
                        }
                    }
                }
                text = null;
            } else if (ev == XmlPullParser.TEXT || ev == XmlPullParser.CDSECT) {
                String t = p.getText();
                if (t != null) text = (text == null) ? t : text + t;
            } else if (ev == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if (tag.equalsIgnoreCase("item") || tag.equalsIgnoreCase("entry")) {
                    if (cur != null && cur.title != null && cur.link != null) items.add(cur);
                    inItem = false;
                    cur = null;
                } else if (inItem && cur != null && text != null) {
                    String val = text.trim();
                    if (tag.equalsIgnoreCase("title")) {
                        if (cur.title == null) cur.title = clean(val);
                    } else if (tag.equalsIgnoreCase("link")) {
                        if (cur.link == null && !val.isEmpty()) cur.link = val;
                    } else if (tag.equalsIgnoreCase("description")
                            || tag.equalsIgnoreCase("summary")
                            || tag.equalsIgnoreCase("content")) {
                        if (cur.snippet == null || cur.snippet.isEmpty()) cur.snippet = snippet(val);
                    } else if (tag.equalsIgnoreCase("pubDate")
                            || tag.equalsIgnoreCase("published")
                            || tag.equalsIgnoreCase("updated")
                            || tag.equalsIgnoreCase("date")) {
                        long t = parseDate(val);
                        if (t > 0) cur.time = t;
                    }
                }
                text = null;
            }
            ev = p.next();
        }
        return items;
    }

    // ---------------------------------------------------------------- text helpers

    /** Strip HTML tags + collapse whitespace, then trim to a card-sized snippet. */
    private static String snippet(String html) {
        String s = clean(html);
        if (s.length() > 240) s = s.substring(0, 240).trim() + "…";
        return s;
    }

    private static String clean(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?s)<[^>]*>", " ");           // drop tags
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
             .replace("&nbsp;", " ").replace("&#8217;", "'").replace("&#8216;", "'")
             .replace("&#8220;", "\"").replace("&#8221;", "\"").replace("&#8230;", "…")
             .replace("&mdash;", "—").replace("&ndash;", "–");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static final String[] DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd HH:mm:ss",
    };

    private static long parseDate(String s) {
        if (s == null || s.isEmpty()) return 0;
        s = s.trim();
        for (String fmt : DATE_FORMATS) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.US);
                return sdf.parse(s).getTime();
            } catch (Exception ignore) { }
        }
        return 0;
    }

    private static String relativeTime(long time) {
        if (time <= 0) return null;
        long diff = System.currentTimeMillis() - time;
        if (diff < 0) diff = 0;
        long min = diff / 60000L;
        if (min < 1) return "just now";
        if (min < 60) return min + "m ago";
        long hr = min / 60;
        if (hr < 24) return hr + "h ago";
        long day = hr / 24;
        if (day < 7) return day + "d ago";
        return new SimpleDateFormat("MMM d", Locale.US).format(new Date(time));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
