package com.reveng.notes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import com.reveng.design.Palette;

/**
 * Clean-room standalone notes app. Master-detail layout: a card list of notes
 * on the left (newest first) and a full multiline editor on the right. Notes are
 * persisted one-file-per-note to app-private internal storage (getFilesDir), so
 * no permissions are required. Pure android.* framework, no AndroidX.
 *
 * A note's title is its first non-empty line; blank/whitespace notes are
 * discarded rather than saved. Editing auto-saves when switching notes, on
 * explicit Save, on back, and on pause.
 */
public class MainActivity extends Activity {

    private static final String PREFIX = "note_";
    private static final String SUFFIX = ".txt";

    /** A single note backed by a file named note_<id>.txt. */
    private static final class Note {
        final long id;       // creation timestamp, also the filename key
        String text;         // full body
        long modified;       // last edit time (ms)
        boolean persisted;   // true once written to disk at least once
        Note(long id, String text, long modified, boolean persisted) {
            this.id = id; this.text = text; this.modified = modified;
            this.persisted = persisted;
        }
    }

    private final ArrayList<Note> all = new ArrayList<>();     // every note on disk
    private final ArrayList<Note> visible = new ArrayList<>(); // filtered view
    private String filter = "";

    // The note currently loaded in the editor (may be a brand-new, unsaved one).
    private Note current = null;
    private boolean loading = false; // guards the editor TextWatcher during load

    private ListView listView;
    private NoteAdapter adapter;
    private View emptyBox;
    private TextView emptyTitle, emptyHint, count;
    private EditText search;

    private View editorContainer, detailEmpty;
    private EditText editor;
    private TextView editorDate;
    private ImageButton btnSave, btnDelete, btnFab;

    // resolved palette
    private int cAccent, cSurface, cSurface2, cStroke, cText, cText2, cText3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cAccent = Palette.color(this, R.color.accent);
        cSurface = Palette.color(this, R.color.surface);
        cSurface2 = Palette.color(this, R.color.surface2);
        cStroke = Palette.color(this, R.color.stroke);
        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);
        cText3 = Palette.color(this, R.color.text3);

        listView = findViewById(R.id.list);
        emptyBox = findViewById(R.id.empty);
        emptyTitle = findViewById(R.id.empty_title);
        emptyHint = findViewById(R.id.empty_hint);
        count = findViewById(R.id.count);
        search = findViewById(R.id.search);

        editorContainer = findViewById(R.id.editor_container);
        detailEmpty = findViewById(R.id.detail_empty);
        editor = findViewById(R.id.editor);
        editorDate = findViewById(R.id.editor_date);
        btnSave = findViewById(R.id.btn_save);
        btnDelete = findViewById(R.id.btn_delete);
        btnFab = findViewById(R.id.btn_fab);

        adapter = new NoteAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> openNote(visible.get(pos)));

        btnFab.setOnClickListener(v -> newNote());
        btnSave.setOnClickListener(v -> saveAndStay());
        btnDelete.setOnClickListener(v -> confirmDelete());

        search.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.getDefault());
                applyFilter();
            }
        });

        editor.addTextChangedListener(new SimpleWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (!loading && current != null) current.text = s.toString();
            }
        });

        loadAll();
        showDetail(false);
    }

    // ---------------- storage ----------------

    private void loadAll() {
        all.clear();
        File dir = getFilesDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
        if (files != null) {
            for (File f : files) {
                String base = f.getName();
                String idStr = base.substring(PREFIX.length(), base.length() - SUFFIX.length());
                long id;
                try { id = Long.parseLong(idStr); } catch (NumberFormatException e) { continue; }
                String text = readFile(f);
                if (text.trim().isEmpty()) { f.delete(); continue; } // clean up stray empties
                all.add(new Note(id, text, f.lastModified(), true));
            }
        }
        sortNotes();
        applyFilter();
    }

    private String readFile(File f) {
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try { int n = 0, off = 0; while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n; }
            finally { in.close(); }
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private File fileFor(Note n) {
        return new File(getFilesDir(), PREFIX + n.id + SUFFIX);
    }

    private void writeNote(Note n) {
        try {
            FileOutputStream out = openFileOutput(PREFIX + n.id + SUFFIX, Context.MODE_PRIVATE);
            try { out.write(n.text.getBytes("UTF-8")); }
            finally { out.close(); }
            n.persisted = true;
            fileFor(n).setLastModified(n.modified);
        } catch (Exception e) {
            Toast.makeText(this, "Could not save note", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteNoteFile(Note n) {
        File f = fileFor(n);
        if (f.exists()) f.delete();
        n.persisted = false;
    }

    // ---------------- editing flow ----------------

    /** Persist whatever is in the editor for `current`; discards it if blank. */
    private void commitCurrent() {
        if (current == null) return;
        String text = current.text == null ? "" : current.text;
        if (text.trim().isEmpty()) {
            // discard empty note
            deleteNoteFile(current);
            all.remove(current);
        } else {
            current.modified = System.currentTimeMillis();
            writeNote(current);
            if (!all.contains(current)) all.add(current);
        }
    }

    private void openNote(Note n) {
        if (n == current) return;
        commitCurrent();
        current = n;
        bindEditor(n);
        sortNotes();
        applyFilter();
    }

    private void newNote() {
        commitCurrent();
        long id = System.currentTimeMillis();
        Note n = new Note(id, "", id, false);
        current = n; // not added to `all` until it has content
        bindEditor(n);
        sortNotes();
        applyFilter();
        editor.requestFocus();
        showKeyboard(editor);
    }

    private void bindEditor(Note n) {
        loading = true;
        editor.setText(n.text == null ? "" : n.text);
        editor.setSelection(editor.getText().length());
        loading = false;
        editorDate.setText(n.persisted
                ? "Edited " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                        .format(new Date(n.modified))
                : "New note");
        showDetail(true);
    }

    /** Explicit Save button: commit and keep editing (or clear if it went blank). */
    private void saveAndStay() {
        if (current == null) return;
        String text = current.text == null ? "" : current.text;
        commitCurrent();
        if (text.trim().isEmpty()) {
            current = null;
            showDetail(false);
        } else {
            // refresh timestamp label + re-sort list to reflect new modified time
            bindEditor(current);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
        }
        sortNotes();
        applyFilter();
        hideKeyboard();
    }

    private void confirmDelete() {
        if (current == null) return;
        final Note victim = current;
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_msg)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    deleteNoteFile(victim);
                    all.remove(victim);
                    if (current == victim) { current = null; showDetail(false); }
                    sortNotes();
                    applyFilter();
                })
                .show();
    }

    // ---------------- list / filter ----------------

    private void sortNotes() {
        Collections.sort(all, new Comparator<Note>() {
            @Override public int compare(Note a, Note b) {
                return Long.compare(b.modified, a.modified); // newest first
            }
        });
    }

    private void applyFilter() {
        visible.clear();
        for (Note n : all) {
            if (filter.isEmpty() || n.text.toLowerCase(Locale.getDefault()).contains(filter)) {
                visible.add(n);
            }
        }
        adapter.notifyDataSetChanged();

        int total = all.size();
        count.setText(total == 1 ? getString(R.string.notes_count_one)
                : getString(R.string.notes_count, total));

        boolean showEmpty = visible.isEmpty();
        emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        listView.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        if (showEmpty) {
            boolean filtering = !filter.isEmpty();
            emptyTitle.setText(filtering ? R.string.no_results : R.string.empty_title);
            emptyHint.setVisibility(filtering ? View.GONE : View.VISIBLE);
        }
    }

    private void showDetail(boolean editing) {
        editorContainer.setVisibility(editing ? View.VISIBLE : View.GONE);
        detailEmpty.setVisibility(editing ? View.GONE : View.VISIBLE);
    }

    // ---------------- lifecycle ----------------

    @Override
    public void onBackPressed() {
        commitCurrent();
        current = null;
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        commitCurrent();
        // keep `current` so returning to the app keeps context; re-sync list
        sortNotes();
        applyFilter();
    }

    // ---------------- helpers ----------------

    private void showKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private static String titleOf(String text) {
        if (text == null) return "";
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String previewOf(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        boolean seenTitle = false;
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (!seenTitle) { if (!t.isEmpty()) seenTitle = true; continue; }
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
            if (sb.length() > 140) break;
        }
        return sb.toString();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
    }

    // ---------------- adapter ----------------

    private final class NoteAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int p) { return visible.get(p); }
        @Override public long getItemId(int p) { return visible.get(p).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout outer;
            LinearLayout card;
            TextView title, preview, date;
            if (convertView instanceof LinearLayout) {
                outer = (LinearLayout) convertView;
                card = (LinearLayout) outer.getChildAt(0);
                title = (TextView) card.getChildAt(0);
                preview = (TextView) card.getChildAt(1);
                date = (TextView) card.getChildAt(2);
            } else {
                outer = new LinearLayout(MainActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setPadding(dp(2), dp(4), dp(2), dp(8));

                card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(16), dp(14), dp(16), dp(14));
                outer.addView(card, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                title = new TextView(MainActivity.this);
                title.setTextSize(17);
                title.setTypeface(Typeface.create("sans-serif-medium", 0));
                title.setTextColor(cText);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(title, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                preview = new TextView(MainActivity.this);
                preview.setTextSize(13);
                preview.setTextColor(cText2);
                preview.setMaxLines(2);
                preview.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                pp.topMargin = dp(4);
                card.addView(preview, pp);

                date = new TextView(MainActivity.this);
                date.setTextSize(11);
                date.setTextColor(cText3);
                LinearLayout.LayoutParams dpp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                dpp.topMargin = dp(8);
                card.addView(date, dpp);
            }

            Note n = visible.get(position);
            boolean active = current != null && n.id == current.id;

            String t = titleOf(n.text);
            title.setText(t.isEmpty() ? getString(R.string.untitled) : t);
            title.setTextColor(active ? cAccent : cText);

            String pv = previewOf(n.text);
            if (pv.isEmpty()) {
                preview.setVisibility(View.GONE);
            } else {
                preview.setVisibility(View.VISIBLE);
                preview.setText(pv);
            }

            date.setText(DateUtils.getRelativeTimeSpanString(
                    n.modified, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(18));
            bg.setColor(active ? Palette.color(MainActivity.this, R.color.accent_dim) : cSurface);
            bg.setStroke(dp(1), active ? cAccent : cStroke);
            card.setBackground(bg);

            return outer;
        }
    }
}
