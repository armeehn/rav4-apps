package com.reveng.tasks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;

/**
 * Clean-room standalone Tasks (to-do / checklist) app. A single list of tasks;
 * each has text and a done flag. Tapping the circular check toggles done; done
 * items get a strikethrough, are dimmed, and sink to the bottom. Add via the
 * field + FAB, edit on long-press, delete with the trash icon.
 *
 * The whole list is persisted as one line-serialized file in app-private
 * internal storage (getFilesDir), so no permissions are required. Pure
 * android.* framework, no AndroidX.
 */
public class MainActivity extends Activity {

    private static final String FILE = "tasks.txt";

    /** A single to-do item. */
    private static final class Task {
        String text;
        boolean done;
        Task(String text, boolean done) { this.text = text; this.done = done; }
    }

    // Master list in creation order. The visible ordering (undone first, done
    // sunk to the bottom) is derived in buildVisible().
    private final ArrayList<Task> all = new ArrayList<>();
    private final ArrayList<Task> visible = new ArrayList<>();

    private ListView listView;
    private TaskAdapter adapter;
    private EditText input;
    private ImageButton btnFab;
    private ProgressBar progress;
    private TextView count;
    private View emptyBox;
    private ImageView emptyIcon;
    private TextView emptyTitle, emptyHint;

    // resolved palette
    private int cAccent, cSurface, cStroke, cText, cText2, cText3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cAccent = getColor(R.color.accent);
        cSurface = getColor(R.color.surface);
        cStroke = getColor(R.color.stroke);
        cText = getColor(R.color.text);
        cText2 = getColor(R.color.text2);
        cText3 = getColor(R.color.text3);

        listView = findViewById(R.id.list);
        input = findViewById(R.id.input);
        btnFab = findViewById(R.id.btn_fab);
        progress = findViewById(R.id.progress);
        count = findViewById(R.id.count);
        emptyBox = findViewById(R.id.empty);
        emptyIcon = findViewById(R.id.empty_icon);
        emptyTitle = findViewById(R.id.empty_title);
        emptyHint = findViewById(R.id.empty_hint);

        adapter = new TaskAdapter();
        listView.setAdapter(adapter);

        btnFab.setOnClickListener(v -> addFromInput());
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) { addFromInput(); return true; }
            return false;
        });

        load();
        refresh();
    }

    // ---------------- storage ----------------

    private void load() {
        all.clear();
        File f = new File(getFilesDir(), FILE);
        if (!f.exists()) return;
        String data = readFile(f);
        for (String line : data.split("\n", -1)) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            boolean done = "1".equals(line.substring(0, tab));
            String text = line.substring(tab + 1);
            if (!text.trim().isEmpty()) all.add(new Task(text, done));
        }
    }

    private String readFile(File f) {
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int n, off = 0; while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n; }
            finally { in.close(); }
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Task t : all) {
            sb.append(t.done ? '1' : '0').append('\t').append(sanitize(t.text)).append('\n');
        }
        try {
            FileOutputStream out = openFileOutput(FILE, Context.MODE_PRIVATE);
            try { out.write(sb.toString().getBytes("UTF-8")); }
            finally { out.close(); }
        } catch (Exception e) {
            Toast.makeText(this, "Could not save tasks", Toast.LENGTH_SHORT).show();
        }
    }

    /** Tasks are single-line; strip control chars that would corrupt the format. */
    private static String sanitize(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }

    // ---------------- actions ----------------

    private void addFromInput() {
        String text = sanitize(input.getText().toString());
        if (text.isEmpty()) return;
        all.add(new Task(text, false));
        input.setText("");
        save();
        refresh();
        // keep focus so several tasks can be added in a row
        input.requestFocus();
    }

    private void toggle(Task t) {
        t.done = !t.done;
        save();
        refresh();
    }

    private void editTask(final Task t) {
        final EditText field = new EditText(this);
        field.setText(t.text);
        field.setSelection(field.getText().length());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int pad = dp(20);
        field.setPadding(pad, dp(8), pad, dp(8));
        new AlertDialog.Builder(this)
                .setTitle(R.string.edit_title)
                .setView(field)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (d, w) -> {
                    String text = sanitize(field.getText().toString());
                    if (!text.isEmpty()) { t.text = text; save(); refresh(); }
                })
                .show();
    }

    private void delete(Task t) {
        all.remove(t);
        save();
        refresh();
    }

    // ---------------- view state ----------------

    private void buildVisible() {
        visible.clear();
        for (Task t : all) if (!t.done) visible.add(t); // undone first, in order
        for (Task t : all) if (t.done) visible.add(t);   // done sink to bottom
    }

    private void refresh() {
        buildVisible();
        adapter.notifyDataSetChanged();

        int total = all.size();
        int done = 0;
        for (Task t : all) if (t.done) done++;

        if (total == 0) {
            count.setText(R.string.progress_none);
        } else {
            count.setText(getString(R.string.progress_fmt, done, total));
        }
        progress.setMax(Math.max(1, total));
        progress.setProgress(done);

        boolean showEmpty = total == 0;
        emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        if (showEmpty) {
            emptyTitle.setText(R.string.empty_title);
            emptyHint.setText(R.string.empty_hint);
        } else if (done == total) {
            // everything checked off: celebrate quietly via the header, list stays visible
        }
    }

    // ---------------- helpers ----------------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    // ---------------- adapter ----------------

    private final class TaskAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int p) { return visible.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout outer;
            LinearLayout card;
            ImageButton check;
            ImageView checkMark;
            TextView label;
            ImageButton del;

            if (convertView instanceof LinearLayout) {
                outer = (LinearLayout) convertView;
                card = (LinearLayout) outer.getChildAt(0);
                LinearLayout checkWrap = (LinearLayout) card.getChildAt(0);
                check = (ImageButton) checkWrap.getChildAt(0);
                label = (TextView) card.getChildAt(1);
                del = (ImageButton) card.getChildAt(2);
            } else {
                outer = new LinearLayout(MainActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setPadding(dp(2), dp(4), dp(2), dp(6));

                card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(10), dp(10), dp(10));
                outer.addView(card, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                // circular check button (fixed-size wrapper keeps it square)
                LinearLayout checkWrap = new LinearLayout(MainActivity.this);
                LinearLayout.LayoutParams cwp = new LinearLayout.LayoutParams(dp(38), dp(38));
                cwp.rightMargin = dp(12);
                card.addView(checkWrap, cwp);

                check = new ImageButton(MainActivity.this);
                check.setScaleType(ImageView.ScaleType.FIT_CENTER);
                check.setPadding(dp(7), dp(7), dp(7), dp(7));
                check.setImageResource(R.drawable.ic_check);
                checkWrap.addView(check, new LinearLayout.LayoutParams(dp(38), dp(38)));

                label = new TextView(MainActivity.this);
                label.setTextSize(17);
                label.setTextColor(cText);
                label.setMaxLines(3);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                card.addView(label, lp);

                del = new ImageButton(MainActivity.this);
                del.setBackgroundResource(R.drawable.btn_icon);
                del.setImageResource(R.drawable.ic_delete);
                del.setColorFilter(cText3);
                del.setScaleType(ImageView.ScaleType.FIT_CENTER);
                del.setPadding(dp(11), dp(11), dp(11), dp(11));
                LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(dp(44), dp(44));
                dp2.leftMargin = dp(4);
                card.addView(del, dp2);
            }

            final Task t = visible.get(position);

            // card background
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(16));
            bg.setColor(cSurface);
            bg.setStroke(dp(1), cStroke);
            card.setBackground(bg);

            // circular check styling
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            if (t.done) {
                ring.setColor(cAccent);
                ring.setStroke(dp(2), cAccent);
                check.setColorFilter(0xFFFFFFFF);
                check.setImageAlpha(255);
            } else {
                ring.setColor(0x00000000);
                ring.setStroke(dp(2), cText3);
                check.setColorFilter(0x00000000); // hide the tick when unchecked
                check.setImageAlpha(0);
            }
            check.setBackground(ring);

            // label
            label.setText(t.text);
            if (t.done) {
                label.setPaintFlags(label.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                label.setTextColor(cText3);
                label.setAlpha(0.85f);
            } else {
                label.setPaintFlags(label.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
                label.setTextColor(cText);
                label.setAlpha(1f);
            }

            check.setOnClickListener(v -> toggle(t));
            del.setOnClickListener(v -> delete(t));
            // tapping the card body toggles; long-press edits the text
            card.setOnClickListener(v -> toggle(t));
            card.setOnLongClickListener(v -> { hideKeyboard(); editTask(t); return true; });

            return outer;
        }
    }
}
