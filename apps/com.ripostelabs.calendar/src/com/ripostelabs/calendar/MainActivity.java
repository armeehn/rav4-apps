package com.ripostelabs.calendar;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.CalendarContract.Instances;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import com.ripostelabs.design.Palette;
import com.ripostelabs.design.PermissionGate;

/**
 * Clean-room standalone Calendar app for the Toyota RAV4 GT6 head unit
 * (Android 13, 1920x720 landscape). A month grid built from java.util.Calendar
 * sits on the left; a per-day agenda panel sits on the right. Events are read
 * from CalendarContract.Instances via ContentResolver under a runtime
 * READ_CALENDAR grant. The whole UI is composed programmatically with the
 * shared design system (palette, styles, drawables). Pure android.*, no AndroidX.
 */
public class MainActivity extends Activity {
    private static final int MIN_TAP_DP = 48;   // the panel's minimum tap target, as in the launcher


    /** READ + WRITE_CALENDAR (one permission group, one dialog), through the suite's one gate. */
    private PermissionGate gate;
    private static final int CELLS = 42; // 6 weeks x 7 days
    private static final long HOUR_MS = 60L * 60L * 1000L;
    private static final int LAST_HOUR = 23;

    // The fallback calendar for a unit with no synced account. LOCAL rows are kept by the
    // provider itself and never synced, so a CalDAV account added later sits beside it.
    private static final String LOCAL_ACCOUNT = "Riposte";
    private static final String LOCAL_NAME = "riposte_local";

    /** One calendar event instance. */
    private static final class Event {
        final long eventId;
        final String title;
        final long begin, end;
        final String location;
        final int color;
        final boolean allDay;
        Event(long eventId, String title, long begin, long end,
              String location, int color, boolean allDay) {
            this.eventId = eventId; this.title = title; this.begin = begin;
            this.end = end; this.location = location; this.color = color;
            this.allDay = allDay;
        }
    }

    // Palette (resolved in onCreate).
    private int cBg, cAccent, cAccentDim, cSurface, cSurface2, cStroke, cText, cText2, cText3;

    // State.
    private final Calendar shown = Calendar.getInstance();    // any day in the shown month
    private final Calendar selected = Calendar.getInstance();  // the selected day
    private final Calendar today = Calendar.getInstance();     // now
    // Events on the visible grid, keyed by yyyymmdd -> list.
    private final HashMap<Integer, ArrayList<Event>> byDay = new HashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Thread worker;

    // Views.
    private TextView monthLabel;
    private LinearLayout gridBox;      // holds 6 week rows
    private LinearLayout weekdayRow;
    private TextView agendaDate, agendaCount;
    private LinearLayout agendaList;   // scrollable event rows
    private View agendaEmpty;
    private ImageView agendaEmptyIcon;
    private TextView agendaEmptyTitle, agendaEmptyHint, grantBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);

        cBg = Palette.color(this, R.color.bg);
        cAccent = Palette.color(this, R.color.accent);
        cAccentDim = Palette.color(this, R.color.accent_dim);
        cSurface = Palette.color(this, R.color.surface);
        cSurface2 = Palette.color(this, R.color.surface2);
        cStroke = Palette.color(this, R.color.stroke);
        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);
        cText3 = Palette.color(this, R.color.text3);

        setContentView(buildRoot());

        gate = PermissionGate.of(this, new String[]{Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR}, grantBtn,
                new PermissionGate.Listener() {
                    @Override public void onGranted() { reload(); }
                    @Override public void onDenied() { reload(); }
                });
        if (!gate.granted()) {
            gate.request();
        }
        renderGrid();
        renderAgenda();
        reload();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (!gate.onResult(req, p, r)) {
            super.onRequestPermissionsResult(req, p, r);
        }
    }

    // ---- UI construction -------------------------------------------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView styled(int style) {
        TextView t = new TextView(this);
        t.setTextAppearance(style);
        return t;
    }

    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(cBg);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(18);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.addView(buildMonthPanel(), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.7f));
        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(dp(16), 1));
        content.addView(buildAgendaPanel(), lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));

        // Floating add button.
        ImageButton fab = new ImageButton(this);
        fab.setId(0x7f0f0001);
        fab.setImageResource(R.drawable.ic_add);
        fab.setBackgroundResource(R.drawable.btn_fab);
        fab.setColorFilter(Palette.onAccent(this));
        fab.setScaleType(ImageView.ScaleType.CENTER);
        fab.setContentDescription(getString(R.string.new_event));
        fab.setElevation(dp(6));
        fab.setOnClickListener(v -> insertEvent());
        int fabSz = dp(60);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(fabSz, fabSz);
        flp.gravity = Gravity.BOTTOM | Gravity.END;
        int m = dp(24);
        flp.setMargins(0, 0, m, m);
        root.addView(fab, flp);

        return root;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    private View buildMonthPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        // Header: prev | Month Year + weekday hint | Today | next
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton prev = iconButton(R.drawable.ic_back, R.string.prev_month);
        prev.setOnClickListener(v -> stepMonth(-1));

        monthLabel = styled(R.style.H1);
        monthLabel.setTextSize(26);
        monthLabel.setPadding(dp(14), 0, dp(14), 0);

        TextView todayBtn = styled(R.style.Caption);
        todayBtn.setText(R.string.today);
        todayBtn.setTextColor(cAccent);
        todayBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        todayBtn.setGravity(Gravity.CENTER);
        todayBtn.setPadding(dp(16), dp(8), dp(16), dp(8));
        todayBtn.setMinHeight(dp(MIN_TAP_DP));
        todayBtn.setMinWidth(dp(MIN_TAP_DP));
        todayBtn.setBackground(pill(cAccentDim, dp(18)));
        todayBtn.setOnClickListener(v -> goToday());

        ImageButton next = iconButton(R.drawable.ic_forward, R.string.next_month);
        next.setOnClickListener(v -> stepMonth(1));

        header.addView(prev);
        header.addView(monthLabel, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(todayBtn);
        header.addView(space(dp(10)));
        header.addView(next);
        col.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Weekday header row.
        weekdayRow = new LinearLayout(this);
        weekdayRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(14);
        wlp.bottomMargin = dp(6);
        col.addView(weekdayRow, wlp);

        // The month grid fills remaining height.
        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(gridBox, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return col;
    }

    private View buildAgendaPanel() {
        FrameLayout card = new FrameLayout(this);
        card.setBackgroundResource(R.drawable.bg_card);
        int p = dp(20);
        card.setPadding(p, p, p, p);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView kicker = styled(R.style.Overline);
        kicker.setText(R.string.app_name);
        col.addView(kicker);

        agendaDate = styled(R.style.H2);
        agendaDate.setTextSize(20);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        col.addView(agendaDate, dlp);

        agendaCount = styled(R.style.Caption);
        agendaCount.setTextColor(cAccent);
        col.addView(agendaCount);

        // Scrollable list of event rows.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = dp(14);
        col.addView(scroll, slp);

        agendaList = new LinearLayout(this);
        agendaList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(agendaList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Empty / permission overlay, centered in the card.
        agendaEmpty = buildEmptyBox();
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.gravity = Gravity.CENTER;
        card.addView(agendaEmpty, elp);

        return card;
    }

    private View buildEmptyBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        int pad = dp(24);
        box.setPadding(pad, pad, pad, pad);

        FrameLayout iconWrap = new FrameLayout(this);
        int c = dp(84);
        iconWrap.setBackground(pill(cSurface2, c / 2));
        agendaEmptyIcon = new ImageView(this);
        agendaEmptyIcon.setImageResource(R.drawable.ic_calendar);
        agendaEmptyIcon.setColorFilter(cText3);
        int is = dp(38);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(is, is);
        ilp.gravity = Gravity.CENTER;
        iconWrap.addView(agendaEmptyIcon, ilp);
        box.addView(iconWrap, new LinearLayout.LayoutParams(c, c));

        agendaEmptyTitle = styled(R.style.H2);
        agendaEmptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(18);
        box.addView(agendaEmptyTitle, tlp);

        agendaEmptyHint = styled(R.style.Caption);
        agendaEmptyHint.setGravity(Gravity.CENTER);
        agendaEmptyHint.setMaxWidth(dp(300));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(6);
        box.addView(agendaEmptyHint, hlp);

        grantBtn = styled(R.style.Body);
        grantBtn.setText(R.string.grant);
        grantBtn.setTextColor(Palette.onAccent(this));
        grantBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        grantBtn.setGravity(Gravity.CENTER);
        grantBtn.setPadding(dp(24), dp(12), dp(24), dp(12));
        grantBtn.setBackground(pill(cAccent, dp(22)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(20);
        box.addView(grantBtn, glp);

        box.setVisibility(View.GONE);
        return box;
    }

    private ImageButton iconButton(int icon, int desc) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setBackgroundResource(R.drawable.btn_icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setContentDescription(getString(desc));
        int s = dp(48);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    private View space(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private GradientDrawable pill(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // ---- Rendering -------------------------------------------------------

    private void renderGrid() {
        monthLabel.setText(monthTitle(shown));

        // Weekday header, honouring the locale's first day of week.
        weekdayRow.removeAllViews();
        int firstDow = Calendar.getInstance().getFirstDayOfWeek();
        String[] shortDays = new DateFormatSymbols(Locale.getDefault()).getShortWeekdays();
        for (int i = 0; i < 7; i++) {
            int dow = ((firstDow - 1 + i) % 7) + 1; // 1..7 (SUN..SAT)
            TextView h = styled(R.style.Overline);
            h.setGravity(Gravity.CENTER);
            String lbl = shortDays[dow];
            h.setText(lbl.length() > 3 ? lbl.substring(0, 3) : lbl);
            weekdayRow.addView(h, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        // Walk from the first visible cell (padded back to firstDow).
        Calendar cur = (Calendar) shown.clone();
        cur.set(Calendar.DAY_OF_MONTH, 1);
        zeroTime(cur);
        int shift = (cur.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7;
        cur.add(Calendar.DAY_OF_MONTH, -shift);

        gridBox.removeAllViews();
        int shownMonth = shown.get(Calendar.MONTH);
        for (int week = 0; week < 6; week++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int d = 0; d < 7; d++) {
                boolean inMonth = cur.get(Calendar.MONTH) == shownMonth;
                boolean isToday = sameDay(cur, today);
                boolean isSel = sameDay(cur, selected);
                int key = ymd(cur);
                boolean hasEvents = byDay.containsKey(key) && !byDay.get(key).isEmpty();
                row.addView(buildCell((Calendar) cur.clone(), inMonth, isToday, isSel, hasEvents),
                        lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                cur.add(Calendar.DAY_OF_MONTH, 1);
            }
            gridBox.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private View buildCell(final Calendar day, boolean inMonth, boolean isToday,
                           boolean isSel, boolean hasEvents) {
        FrameLayout cell = new FrameLayout(this);
        int mg = dp(3);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cell.setLayoutParams(clp);

        // Background: selected day gets an accent_dim fill; today gets an accent ring.
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (isSel) {
            bg.setColor(cAccentDim);
        } else {
            bg.setColor(Color.TRANSPARENT);
        }
        if (isToday) {
            bg.setStroke(dp(2), cAccent);
        }
        cell.setBackground(bg);
        cell.setForeground(getResources().getDrawable(R.drawable.btn_icon, getTheme()));

        // vertical inner content
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_HORIZONTAL);
        int ip = dp(8);
        inner.setPadding(0, ip, 0, ip);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        cell.addView(inner, ilp);

        TextView num = new TextView(this);
        num.setText(String.valueOf(day.get(Calendar.DAY_OF_MONTH)));
        num.setTextSize(18);
        num.setGravity(Gravity.CENTER);
        if (isToday) {
            num.setTextColor(cAccent);
            num.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else if (!inMonth) {
            num.setTextColor(cText3);
        } else {
            num.setTextColor(cText);
        }
        inner.addView(num, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Event dot(s).
        FrameLayout dotWrap = new FrameLayout(this);
        LinearLayout.LayoutParams dwlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        dwlp.topMargin = dp(4);
        if (hasEvents) {
            View dot = new View(this);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(isSel ? cText : cAccent);
            dot.setBackground(dg);
            int ds = dp(7);
            FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(ds, ds);
            dlp.gravity = Gravity.CENTER;
            dotWrap.addView(dot, dlp);
        }
        inner.addView(dotWrap, dwlp);

        cell.setClickable(true);
        cell.setOnClickListener(v -> {
            selected.setTimeInMillis(day.getTimeInMillis());
            renderGrid();
            renderAgenda();
        });
        return cell;
    }

    private void renderAgenda() {
        agendaDate.setText(agendaTitle(selected));

        if (!gate.granted()) {
            agendaList.setVisibility(View.GONE);
            agendaCount.setText("");
            showEmpty(true, R.drawable.ic_calendar, R.string.need_permission_title,
                    R.string.need_permission_hint, true);
            return;
        }

        ArrayList<Event> events = eventsForSelected();
        Collections.sort(events, new Comparator<Event>() {
            @Override public int compare(Event a, Event b) {
                return Long.compare(a.begin, b.begin);
            }
        });

        if (events.isEmpty()) {
            agendaList.setVisibility(View.GONE);
            agendaCount.setText("");
            boolean anyThisMonth = !byDay.isEmpty();
            showEmpty(true, R.drawable.ic_calendar,
                    anyThisMonth ? R.string.agenda_none_title : R.string.empty_title,
                    anyThisMonth ? R.string.agenda_none_hint : R.string.empty_hint, false);
            return;
        }

        showEmpty(false, 0, 0, 0, false);
        agendaList.setVisibility(View.VISIBLE);
        agendaCount.setText(events.size() == 1
                ? getString(R.string.events_count_one)
                : getString(R.string.events_count, events.size()));

        agendaList.removeAllViews();
        for (Event e : events) {
            agendaList.addView(buildEventRow(e));
        }
    }

    private View buildEventRow(final Event e) {
        LinearLayout rowWrap = new LinearLayout(this);
        rowWrap.setOrientation(LinearLayout.HORIZONTAL);
        rowWrap.setGravity(Gravity.CENTER_VERTICAL);
        rowWrap.setBackgroundResource(R.drawable.bg_card);
        rowWrap.setForeground(getResources().getDrawable(R.drawable.btn_icon, getTheme()));
        int p = dp(14);
        rowWrap.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(10);
        rowWrap.setLayoutParams(rlp);

        // Colored bar for the calendar color.
        View bar = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(e.color != 0 ? forceOpaque(e.color) : cAccent);
        bd.setCornerRadius(dp(3));
        bar.setBackground(bd);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(5), dp(42));
        blp.rightMargin = dp(14);
        rowWrap.addView(bar, blp);

        // Time chip.
        TextView chip = styled(R.style.Caption);
        chip.setTextColor(cAccent);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setGravity(Gravity.CENTER);
        chip.setText(e.allDay ? getString(R.string.all_day) : timeStr(e.begin));
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackground(pill(cAccentDim, dp(12)));
        chip.setMinWidth(dp(72));
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cclp.rightMargin = dp(14);
        rowWrap.addView(chip, cclp);

        // Title + location.
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = styled(R.style.Body);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setText(e.title == null || e.title.trim().isEmpty() ? "(No title)" : e.title);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);

        TextView sub = styled(R.style.Caption);
        if (e.location != null && !e.location.trim().isEmpty()) {
            sub.setText(e.location);
        } else if (!e.allDay) {
            sub.setText(timeStr(e.begin) + "  –  " + timeStr(e.end));
        } else {
            sub.setText(R.string.all_day);
        }
        sub.setMaxLines(1);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);

        rowWrap.addView(texts, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        rowWrap.setClickable(true);
        rowWrap.setOnClickListener(v -> viewEvent(e));
        return rowWrap;
    }

    private void showEmpty(boolean show, int icon, int title, int hint, boolean showGrant) {
        if (!show) {
            agendaEmpty.setVisibility(View.GONE);
            return;
        }
        agendaEmpty.setVisibility(View.VISIBLE);
        agendaEmptyIcon.setImageResource(icon);
        agendaEmptyTitle.setText(title);
        agendaEmptyHint.setText(hint);
        grantBtn.setVisibility(showGrant ? View.VISIBLE : View.GONE);
    }

    // ---- Data loading ----------------------------------------------------

    private void reload() {
        byDay.clear();
        if (!gate.granted()) {
            renderGrid();
            renderAgenda();
            return;
        }

        // Range = the visible 6-week grid.
        final Calendar start = (Calendar) shown.clone();
        start.set(Calendar.DAY_OF_MONTH, 1);
        zeroTime(start);
        int firstDow = Calendar.getInstance().getFirstDayOfWeek();
        int shift = (start.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7;
        start.add(Calendar.DAY_OF_MONTH, -shift);
        final long begin = start.getTimeInMillis();
        final Calendar endCal = (Calendar) start.clone();
        endCal.add(Calendar.DAY_OF_MONTH, CELLS);
        final long end = endCal.getTimeInMillis();

        if (worker != null) worker.interrupt();
        worker = new Thread(() -> {
            final HashMap<Integer, ArrayList<Event>> map = queryInstances(begin, end);
            if (Thread.currentThread().isInterrupted()) return;
            ui.post(() -> {
                byDay.clear();
                byDay.putAll(map);
                renderGrid();
                renderAgenda();
            });
        });
        worker.start();
    }

    private HashMap<Integer, ArrayList<Event>> queryInstances(long begin, long end) {
        HashMap<Integer, ArrayList<Event>> map = new HashMap<>();
        String[] proj = {
                Instances.EVENT_ID,      // 0
                Instances.TITLE,         // 1
                Instances.BEGIN,         // 2
                Instances.END,           // 3
                Instances.EVENT_LOCATION,// 4
                Instances.CALENDAR_COLOR,// 5
                Instances.ALL_DAY,       // 6
        };
        Cursor c = null;
        try {
            ContentResolver cr = getContentResolver();
            c = Instances.query(cr, proj, begin, end);
            if (c != null) {
                Calendar tmp = Calendar.getInstance();
                while (c.moveToNext()) {
                    long id = c.getLong(0);
                    String title = c.getString(1);
                    long b = c.getLong(2);
                    long e = c.getLong(3);
                    String loc = c.getString(4);
                    int color = c.isNull(5) ? 0 : c.getInt(5);
                    boolean allDay = c.getInt(6) != 0;
                    Event ev = new Event(id, title, b, e, loc, color, allDay);
                    tmp.setTimeInMillis(b);
                    int key = ymd(tmp);
                    ArrayList<Event> list = map.get(key);
                    if (list == null) { list = new ArrayList<>(); map.put(key, list); }
                    list.add(ev);
                }
            }
        } catch (Exception ignored) {
            // Head unit may have no calendar provider; treat as empty.
        } finally {
            if (c != null) c.close();
        }
        return map;
    }

    private ArrayList<Event> eventsForSelected() {
        ArrayList<Event> out = new ArrayList<>();
        Calendar s = (Calendar) selected.clone();
        zeroTime(s);
        long dayStart = s.getTimeInMillis();
        s.add(Calendar.DAY_OF_MONTH, 1);
        long dayEnd = s.getTimeInMillis();
        // Include any event overlapping this day.
        for (ArrayList<Event> list : byDay.values()) {
            for (Event e : list) {
                if (e.begin < dayEnd && e.end > dayStart) {
                    out.add(e);
                } else if (e.begin >= dayStart && e.begin < dayEnd) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    // ---- Navigation & intents -------------------------------------------

    private void stepMonth(int delta) {
        shown.add(Calendar.MONTH, delta);
        // Keep the selection sensible: snap to the same month.
        selected.setTimeInMillis(shown.getTimeInMillis());
        if (sameMonth(shown, today)) {
            selected.setTimeInMillis(today.getTimeInMillis());
        } else {
            selected.set(Calendar.DAY_OF_MONTH, 1);
        }
        reload();
    }

    private void goToday() {
        today.setTimeInMillis(System.currentTimeMillis());
        shown.setTimeInMillis(today.getTimeInMillis());
        selected.setTimeInMillis(today.getTimeInMillis());
        reload();
    }

    /**
     * Adds an event in-app. Handing ACTION_INSERT to the system Calendar crashed it on Riposte
     * OS 0.2: its manifest names event.EditEventActivity, which its dex does not contain.
     */
    private void insertEvent() {
        if (!gate.granted()) {
            gate.request();
            return;
        }

        // Default slot: the next whole hour on the selected day, one hour long.
        final Calendar begin = (Calendar) selected.clone();
        Calendar now = Calendar.getInstance();
        begin.set(Calendar.HOUR_OF_DAY, Math.min(now.get(Calendar.HOUR_OF_DAY) + 1, LAST_HOUR));
        begin.set(Calendar.MINUTE, 0);
        begin.set(Calendar.SECOND, 0);
        begin.set(Calendar.MILLISECOND, 0);
        final Calendar end = (Calendar) begin.clone();
        end.setTimeInMillis(begin.getTimeInMillis() + HOUR_MS);

        final EditText title = new EditText(this);
        title.setHint(R.string.event_title_hint);
        title.setSingleLine(true);
        final TextView from = timeButton();
        final TextView to = timeButton();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, dp(8));
        box.addView(title);
        box.addView(from);
        box.addView(to);

        // Moving the start keeps the duration; the end is picked on its own.
        from.setOnClickListener(v -> pickTime(begin, () -> {
            end.setTimeInMillis(begin.getTimeInMillis() + HOUR_MS);
            from.setText(getString(R.string.event_starts, timeStr(begin.getTimeInMillis())));
            to.setText(getString(R.string.event_ends, timeStr(end.getTimeInMillis())));
        }));
        to.setOnClickListener(v -> pickTime(end, () ->
                to.setText(getString(R.string.event_ends, timeStr(end.getTimeInMillis())))));
        from.setText(getString(R.string.event_starts, timeStr(begin.getTimeInMillis())));
        to.setText(getString(R.string.event_ends, timeStr(end.getTimeInMillis())));

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_event)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> saveEvent(
                        title.getText().toString().trim(),
                        begin.getTimeInMillis(), end.getTimeInMillis()))
                .show();
    }

    private TextView timeButton() {
        TextView t = styled(R.style.Body);
        t.setMinHeight(dp(MIN_TAP_DP));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private void pickTime(final Calendar c, final Runnable after) {
        new TimePickerDialog(this, (tp, h, m) -> {
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            after.run();
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                DateFormat.is24HourFormat(this)).show();
    }

    /** Writes off the UI thread, then reloads. Never throws: a failure is a toast. */
    private void saveEvent(final String title, final long begin, final long endIn) {
        final long end = endIn > begin ? endIn : begin + HOUR_MS;
        new Thread(() -> {
            boolean ok = false;
            try {
                long cal = writableCalendar();
                if (cal != CalendarPick.NONE) {
                    ContentValues v = new ContentValues();
                    v.put(Events.CALENDAR_ID, cal);
                    v.put(Events.TITLE, title.isEmpty() ? getString(R.string.new_event) : title);
                    v.put(Events.DTSTART, begin);
                    v.put(Events.DTEND, end);
                    v.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
                    ok = getContentResolver().insert(Events.CONTENT_URI, v) != null;
                }
            } catch (RuntimeException e) {
                ok = false;  // SecurityException or a missing provider: report, do not crash
            }
            final boolean saved = ok;
            ui.post(() -> {
                Toast.makeText(this, saved ? R.string.event_saved : R.string.event_failed,
                        Toast.LENGTH_SHORT).show();
                reload();
            });
        }).start();
    }

    /** The best writable calendar, creating the local one when the unit has none. */
    private long writableCalendar() {
        String[] proj = {Calendars._ID, Calendars.CALENDAR_ACCESS_LEVEL, Calendars.VISIBLE,
                Calendars.ACCOUNT_TYPE, Calendars.IS_PRIMARY};
        List<CalendarPick.Candidate> all = new ArrayList<>();
        try (Cursor c = getContentResolver().query(Calendars.CONTENT_URI, proj, null, null, null)) {
            while (c != null && c.moveToNext()) {
                boolean local = CalendarContract.ACCOUNT_TYPE_LOCAL.equals(c.getString(3));
                all.add(new CalendarPick.Candidate(c.getLong(0), c.getInt(1),
                        c.getInt(2) != 0, local, !c.isNull(4) && c.getInt(4) != 0));
            }
        }

        long id = CalendarPick.choose(all);
        if (id != CalendarPick.NONE) {
            return id;
        }
        return createLocalCalendar();
    }

    // Only a sync adapter may insert a calendar; a LOCAL account may act as its own.
    private long createLocalCalendar() {
        Uri uri = Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
                .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        ContentValues v = new ContentValues();
        v.put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT);
        v.put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        v.put(Calendars.NAME, LOCAL_NAME);
        v.put(Calendars.CALENDAR_DISPLAY_NAME, getString(R.string.local_calendar));
        v.put(Calendars.CALENDAR_COLOR, forceOpaque(cAccent));
        v.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER);
        v.put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT);
        v.put(Calendars.VISIBLE, 1);
        v.put(Calendars.SYNC_EVENTS, 1);
        v.put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().getID());

        Uri made = getContentResolver().insert(uri, v);
        return made == null ? CalendarPick.NONE : ContentUris.parseId(made);
    }

    /** In-app details: the system Calendar's EventInfoActivity crashes on Riposte OS 0.2 too. */
    private void viewEvent(final Event e) {
        String when = e.allDay ? getString(R.string.all_day)
                : timeStr(e.begin) + " - " + timeStr(e.end);
        String where = e.location == null || e.location.isEmpty()
                ? getString(R.string.no_location) : e.location;
        new AlertDialog.Builder(this)
                .setTitle(e.title == null ? "" : e.title)
                .setMessage(when + "\n" + where)
                .setNegativeButton(R.string.delete, (d, w) -> deleteEvent(e.eventId))
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void deleteEvent(final long eventId) {
        new Thread(() -> {
            try {
                Uri uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId);
                getContentResolver().delete(uri, null, null);
            } catch (RuntimeException ignored) {
                // Read-only calendar or revoked grant: the row simply stays.
            }
            ui.post(this::reload);
        }).start();
    }

    // ---- Date helpers ----------------------------------------------------

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static boolean sameMonth(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.MONTH) == b.get(Calendar.MONTH);
    }

    private static int ymd(Calendar c) {
        return c.get(Calendar.YEAR) * 10000 + c.get(Calendar.MONTH) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private static int forceOpaque(int color) {
        return 0xFF000000 | (color & 0x00FFFFFF);
    }

    private String monthTitle(Calendar c) {
        return DateFormat.format("MMMM yyyy", c).toString();
    }

    private String agendaTitle(Calendar c) {
        return DateFormat.format("EEE, MMM d", c).toString();
    }

    private String timeStr(long millis) {
        return DateFormat.getTimeFormat(this).format(new Date(millis));
    }
}
