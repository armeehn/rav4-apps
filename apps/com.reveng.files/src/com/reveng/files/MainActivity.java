package com.reveng.files;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.reveng.design.Palette;

/**
 * Clean-room standalone file manager. Browses the on-disk filesystem with
 * java.io.File starting at /storage/emulated/0, requesting all-files access
 * (MANAGE_EXTERNAL_STORAGE) with READ_MEDIA_* as a fallback. Folders first,
 * then files, alphabetical; per-row Open/Share/Rename/Delete plus New folder.
 * Pure android.* framework - no AndroidX / Gradle.
 */
public class MainActivity extends Activity {

    private static final int REQ_MEDIA = 1;

    private static final HashSet<String> IMG = set("jpg","jpeg","png","gif","bmp","webp","heic","heif","svg");
    private static final HashSet<String> AUD = set("mp3","wav","flac","aac","ogg","m4a","wma","opus","amr");
    private static final HashSet<String> VID = set("mp4","mkv","avi","mov","webm","3gp","flv","wmv","m4v","ts");

    private static HashSet<String> set(String... v) { return new HashSet<>(Arrays.asList(v)); }

    private final ArrayList<File> entries = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault());

    private File root;
    private File currentDir;

    private ListView list;
    private View empty;
    private ImageView emptyIcon;
    private TextView emptyText, emptyHint, pathText, storageText;
    private Button grantBtn;
    private ImageButton btnUp, btnHome, btnNewFolder;
    private FilesAdapter adapter;

    private int cAccent, cAccentDim, cSurface2, cText, cText2, cText3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.5.2: re-paint anything the design-pack resources coloured.
        Palette.apply(this);
        setContentView(R.layout.activity_main);

        cAccent = Palette.color(this, R.color.accent);
        cAccentDim = Palette.color(this, R.color.accent_dim);
        cSurface2 = Palette.color(this, R.color.surface2);
        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);
        cText3 = Palette.color(this, R.color.text3);

        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        emptyIcon = findViewById(R.id.empty_icon);
        emptyText = findViewById(R.id.empty_text);
        emptyHint = findViewById(R.id.empty_hint);
        pathText = findViewById(R.id.path_text);
        storageText = findViewById(R.id.storage_text);
        grantBtn = findViewById(R.id.grant);
        btnUp = findViewById(R.id.btn_up);
        btnHome = findViewById(R.id.btn_home);
        btnNewFolder = findViewById(R.id.btn_new_folder);

        root = new File("/storage/emulated/0");
        if (!root.isDirectory()) root = Environment.getExternalStorageDirectory();
        if (root == null || !root.isDirectory()) root = new File("/sdcard");
        currentDir = root;

        list.setDivider(null);
        list.setDividerHeight(dp(8));
        adapter = new FilesAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener((p, v, pos, id) -> onEntryTap(pos));
        list.setOnItemLongClickListener((p, v, pos, id) -> { showActions(entries.get(pos)); return true; });

        btnUp.setOnClickListener(v -> goUp());
        btnHome.setOnClickListener(v -> navigate(root));
        btnNewFolder.setOnClickListener(v -> promptNewFolder());
        grantBtn.setOnClickListener(v -> requestAccess());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAccess()) navigate(currentDir);
        else showPermissionState();
    }

    // ---- permissions ----

    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) return true;
        }
        // fallback: legacy read (pre-30) or media read grants
        if (Build.VERSION.SDK_INT < 30) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            // route the user to the all-files access settings screen
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                    toast("Cannot open all-files access settings");
                }
            }
        }
        // also request media read as a fallback so at least media is browsable
        ArrayList<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        try { requestPermissions(perms.toArray(new String[0]), REQ_MEDIA); } catch (Exception ignored) {}
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (hasAccess()) navigate(currentDir);
        else navigate(currentDir); // media grants may still let us read some dirs
    }

    // ---- navigation ----

    private void navigate(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        currentDir = dir;
        pathText.setText(dir.getAbsolutePath());
        pathText.post(() -> {
            View hsv = findViewById(R.id.path_scroll);
            if (hsv instanceof android.widget.HorizontalScrollView)
                ((android.widget.HorizontalScrollView) hsv).fullScroll(View.FOCUS_RIGHT);
        });
        updateStorage();
        loadEntries(dir);
    }

    private void goUp() {
        if (currentDir == null) return;
        File parent = currentDir.getParentFile();
        if (parent != null && parent.canRead()
                && currentDir.getAbsolutePath().length() > 1) {
            navigate(parent);
        }
    }

    private void loadEntries(File dir) {
        io.execute(() -> {
            ArrayList<File> found = new ArrayList<>();
            File[] kids = null;
            try { kids = dir.listFiles(); } catch (Exception ignored) {}
            if (kids != null) {
                for (File f : kids) {
                    if (f.isHidden() && f.getName().startsWith(".")) { /* keep dotfiles visible */ }
                    found.add(f);
                }
            }
            Collections.sort(found, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    boolean da = a.isDirectory(), db = b.isDirectory();
                    if (da != db) return da ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            final File[] arr = kids;
            ui.post(() -> {
                entries.clear();
                entries.addAll(found);
                adapter.notifyDataSetChanged();
                boolean noAccess = !hasAccess() && (arr == null);
                if (noAccess) {
                    showPermissionState();
                } else {
                    empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    list.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
                    if (entries.isEmpty()) {
                        emptyIcon.setImageResource(R.drawable.ic_folder);
                        emptyText.setText(R.string.empty_folder);
                        emptyHint.setText(R.string.empty_hint);
                        grantBtn.setVisibility(View.GONE);
                    }
                }
            });
        });
    }

    private void showPermissionState() {
        empty.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        emptyIcon.setImageResource(R.drawable.ic_folder);
        emptyText.setText(R.string.need_permission);
        emptyHint.setText(R.string.need_permission_hint);
        grantBtn.setVisibility(View.VISIBLE);
    }

    private void updateStorage() {
        try {
            StatFs st = new StatFs(root.getAbsolutePath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            long used = total - free;
            storageText.setText(human(used) + " / " + human(total));
        } catch (Exception e) {
            storageText.setText("");
        }
    }

    // ---- row tap ----

    private void onEntryTap(int pos) {
        if (pos < 0 || pos >= entries.size()) return;
        File f = entries.get(pos);
        if (f.isDirectory()) navigate(f);
        else openFile(f);
    }

    // ---- actions ----

    private void showActions(File f) {
        final String[] items = f.isDirectory()
                ? new String[]{ getString(R.string.rename), getString(R.string.delete) }
                : new String[]{ getString(R.string.open), getString(R.string.share),
                                getString(R.string.rename), getString(R.string.delete) };
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(f.getName());
        b.setItems(items, (d, which) -> {
            String choice = items[which];
            if (choice.equals(getString(R.string.open))) openFile(f);
            else if (choice.equals(getString(R.string.share))) shareFile(f);
            else if (choice.equals(getString(R.string.rename))) promptRename(f);
            else if (choice.equals(getString(R.string.delete))) confirmDelete(f);
        });
        b.show();
    }

    private void openFile(File f) {
        Uri uri = mediaUriFor(f);
        boolean granted = false;
        if (uri == null) { uri = FileProvider.uriFor(f); granted = true; }
        String mime = FileProvider.mimeOf(f.getName());
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, f.getName()));
        } catch (Exception e) {
            showInfo(f);
        }
    }

    private void shareFile(File f) {
        Uri uri = mediaUriFor(f);
        if (uri == null) uri = FileProvider.uriFor(f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(FileProvider.mimeOf(f.getName()));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, f.getName()));
        } catch (Exception e) {
            toast("No app to share with");
        }
    }

    /** Best-effort MediaStore content:// lookup by _data (native path). */
    private Uri mediaUriFor(File f) {
        try {
            Uri base = MediaStore.Files.getContentUri("external");
            String[] proj = { MediaStore.Files.FileColumns._ID };
            String sel = MediaStore.Files.FileColumns.DATA + "=?";
            try (Cursor c = getContentResolver().query(base, proj, sel,
                    new String[]{ f.getAbsolutePath() }, null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    return ContentUris.withAppendedId(base, id);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void promptRename(File f) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setText(f.getName());
        in.setSelectAllOnFocus(true);
        in.setTextColor(cText);
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(R.string.rename);
        b.setView(pad(in));
        b.setPositiveButton(R.string.ok, (d, w) -> {
            String name = in.getText().toString().trim();
            if (TextUtils.isEmpty(name)) return;
            File dest = new File(f.getParentFile(), name);
            if (f.renameTo(dest)) navigate(currentDir);
            else toast("Rename failed");
        });
        b.setNegativeButton(R.string.cancel, null);
        b.show();
    }

    private void promptNewFolder() {
        if (currentDir == null) return;
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setHint("Folder name");
        in.setTextColor(cText);
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(R.string.new_folder);
        b.setView(pad(in));
        b.setPositiveButton(R.string.create, (d, w) -> {
            String name = in.getText().toString().trim();
            if (TextUtils.isEmpty(name)) return;
            File dir = new File(currentDir, name);
            if (dir.mkdirs() || dir.isDirectory()) navigate(currentDir);
            else toast("Could not create folder");
        });
        b.setNegativeButton(R.string.cancel, null);
        b.show();
    }

    private void confirmDelete(File f) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(R.string.delete);
        b.setMessage(getString(R.string.delete_confirm) + "\n\n" + f.getName());
        b.setPositiveButton(R.string.delete, (d, w) -> {
            if (deleteRecursive(f)) navigate(currentDir);
            else toast("Delete failed");
        });
        b.setNegativeButton(R.string.cancel, null);
        b.show();
    }

    private boolean deleteRecursive(File f) {
        try {
            if (f.isDirectory()) {
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) deleteRecursive(k);
            }
            return f.delete();
        } catch (Exception e) {
            return false;
        }
    }

    private void showInfo(File f) {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle(f.getName());
        String msg = "Path: " + f.getAbsolutePath()
                + "\nSize: " + human(f.length())
                + "\nModified: " + dateFmt.format(new Date(f.lastModified()))
                + "\n\nNo app can open this file type.";
        b.setMessage(msg);
        b.setPositiveButton(R.string.ok, null);
        b.show();
    }

    private FrameLayout pad(View v) {
        FrameLayout fl = new FrameLayout(this);
        int p = dp(20);
        fl.setPadding(p, dp(8), p, 0);
        fl.addView(v);
        return fl;
    }

    // ---- helpers ----

    private String human(long bytes) {
        return Formatter.formatShortFileSize(this, bytes);
    }

    private int iconFor(File f) {
        if (f.isDirectory()) return R.drawable.ic_folder;
        String ext = ext(f.getName());
        if (IMG.contains(ext)) return R.drawable.ic_image;
        if (AUD.contains(ext)) return R.drawable.ic_music;
        if (VID.contains(ext)) return R.drawable.ic_movie;
        return R.drawable.ic_file;
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.US) : "";
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- adapter ----

    private final class FilesAdapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int p) { return entries.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            ImageView avatar;
            TextView name, sub;
            ImageButton more;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
                avatar = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                name = (TextView) col.getChildAt(0);
                sub = (TextView) col.getChildAt(1);
                more = (ImageButton) row.getChildAt(2);
            } else {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int padH = dp(14);
                row.setPadding(padH, dp(8), dp(6), dp(8));
                row.setMinimumHeight(dp(70));

                avatar = new ImageView(MainActivity.this);
                avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int ap = dp(11);
                avatar.setPadding(ap, ap, ap, ap);
                row.addView(avatar, new LinearLayout.LayoutParams(dp(46), dp(46)));

                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                clp.leftMargin = dp(14);
                clp.rightMargin = dp(8);
                row.addView(col, clp);

                name = new TextView(MainActivity.this);
                name.setTextSize(16);
                name.setTextColor(cText);
                name.setTypeface(Typeface.create("sans-serif-medium", 0));
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                col.addView(name, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                sub = new TextView(MainActivity.this);
                sub.setTextSize(12);
                sub.setTextColor(cText2);
                sub.setSingleLine(true);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.topMargin = dp(2);
                col.addView(sub, slp);

                more = new ImageButton(MainActivity.this);
                more.setImageResource(R.drawable.ic_more);
                more.setBackgroundResource(R.drawable.btn_icon);
                more.setColorFilter(cText2);
                more.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int mp = dp(11);
                more.setPadding(mp, mp, mp, mp);
                row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));

                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setShape(GradientDrawable.RECTANGLE);
                rowBg.setCornerRadius(dp(16));
                rowBg.setColor(Palette.color(MainActivity.this, R.color.surface));
                rowBg.setStroke(dp(1), Palette.color(MainActivity.this, R.color.stroke));
                row.setBackground(rowBg);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }

            final File f = entries.get(position);
            boolean isDir = f.isDirectory();
            name.setText(f.getName());
            if (isDir) {
                int n = 0;
                try { String[] l = f.list(); n = l == null ? 0 : l.length; } catch (Exception ignored) {}
                sub.setText("Folder  ·  " + n + " items");
            } else {
                sub.setText(human(f.length()) + "  ·  " + dateFmt.format(new Date(f.lastModified())));
            }

            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(isDir ? cAccentDim : cSurface2);
            avatar.setBackground(oval);
            avatar.setImageResource(iconFor(f));
            avatar.setColorFilter(isDir ? cAccent : cText2);

            more.setOnClickListener(v -> showActions(f));

            // spacing between rows
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            if (lp == null) lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            return row;
        }
    }
}
