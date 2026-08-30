package com.reveng.installer;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.reveng.design.Palette;

/**
 * Clean-room replacement for the GT6 built-in "Apk Installer"
 * (com.szchoiceway.apkinstall). Two plain-framework tabs:
 *   - Install: APK files found on the device (MediaStore.Files, package-archive
 *     mime) plus a best-effort /sdcard/Download scan; each yields a content://
 *     URI driven into ACTION_INSTALL_PACKAGE.
 *   - Apps: installed user apps with Open / App info / Uninstall actions.
 * Pure android.* framework, no AndroidX / Gradle.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;
    private static final String APK_MIME = "application/vnd.android.package-archive";

    // ---- models -------------------------------------------------------------

    private static final class ApkItem {
        String name;          // display filename
        long size;            // bytes
        Uri contentUri;       // installable content:// (may be null for file-only)
        String filePath;      // absolute path when known (from DATA or Download scan)
        // parsed lazily:
        String label;
        String version;
        Drawable icon;
        boolean parsed;
    }

    private static final class AppItem {
        String pkg;
        String label;
        String version;
        Drawable icon;
    }

    private final ArrayList<ApkItem> apks = new ArrayList<>();
    private final ArrayList<AppItem> apps = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView apkList, appList;
    private View empty;
    private ImageView emptyIcon;
    private TextView emptyText, emptyHint, count, section;
    private Button grantBtn, tabInstall, tabApps;

    private ApkAdapter apkAdapter;
    private AppAdapter appAdapter;

    private boolean showingInstall = true;

    // resolved palette (shared design system)
    private int cAccent, cAccentDim, cSurface2, cText, cText2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cAccent = Palette.color(this, R.color.accent);
        cAccentDim = Palette.color(this, R.color.accent_dim);
        cSurface2 = Palette.color(this, R.color.surface2);
        cText = Palette.color(this, R.color.text);
        cText2 = Palette.color(this, R.color.text2);

        apkList = findViewById(R.id.apk_list);
        appList = findViewById(R.id.app_list);
        empty = findViewById(R.id.empty);
        emptyIcon = findViewById(R.id.empty_icon);
        emptyText = findViewById(R.id.empty_text);
        emptyHint = findViewById(R.id.empty_hint);
        count = findViewById(R.id.count);
        section = findViewById(R.id.section);
        grantBtn = findViewById(R.id.grant);
        tabInstall = findViewById(R.id.tab_install);
        tabApps = findViewById(R.id.tab_apps);

        apkAdapter = new ApkAdapter();
        appAdapter = new AppAdapter();
        apkList.setAdapter(apkAdapter);
        appList.setAdapter(appAdapter);

        apkList.setOnItemClickListener((p, v, pos, id) -> install(apks.get(pos)));
        appList.setOnItemClickListener((p, v, pos, id) -> openApp(apps.get(pos)));

        tabInstall.setOnClickListener(v -> selectTab(true));
        tabApps.setOnClickListener(v -> selectTab(false));
        findViewById(R.id.btn_refresh).setOnClickListener(v -> refresh());
        grantBtn.setOnClickListener(v -> {
            if (showingInstall && !hasStoragePerm()) requestPermissions(storagePerms(), REQ_PERM);
        });

        selectTab(true);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // an uninstall / install may have changed the app list while we were away
        if (!showingInstall) loadApps();
    }

    // ---- tab switching ------------------------------------------------------

    private void selectTab(boolean install) {
        showingInstall = install;
        tabInstall.setBackgroundResource(install ? R.drawable.btn_accent : R.drawable.btn_ghost);
        tabInstall.setTextColor(install ? 0xFFFFFFFF : cText2);
        tabApps.setBackgroundResource(install ? R.drawable.btn_ghost : R.drawable.btn_accent);
        tabApps.setTextColor(install ? cText2 : 0xFFFFFFFF);
        section.setText(install ? R.string.section_available : R.string.section_installed);
        apkList.setVisibility(install ? View.VISIBLE : View.GONE);
        appList.setVisibility(install ? View.GONE : View.VISIBLE);
        updateChrome();
    }

    private void refresh() {
        if (showingInstall) {
            if (hasStoragePerm()) loadApks();
            else requestPermissions(storagePerms(), REQ_PERM);
        } else {
            loadApps();
        }
    }

    private void updateChrome() {
        if (showingInstall) {
            count.setText(getString(R.string.apks_count, apks.size()));
            boolean noPerm = !hasStoragePerm();
            boolean isEmpty = apks.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            apkList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            emptyIcon.setImageResource(R.drawable.ic_download);
            if (noPerm) {
                emptyText.setText(R.string.need_storage_perm);
                emptyHint.setText(R.string.empty_apks_hint);
                grantBtn.setVisibility(View.VISIBLE);
            } else {
                emptyText.setText(R.string.empty_no_apks);
                emptyHint.setText(R.string.empty_apks_hint);
                grantBtn.setVisibility(View.GONE);
            }
        } else {
            count.setText(getString(R.string.apps_count, apps.size()));
            boolean isEmpty = apps.isEmpty();
            empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            appList.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
            emptyIcon.setImageResource(R.drawable.ic_file);
            emptyText.setText(R.string.empty_no_apps);
            emptyHint.setText(R.string.empty_apps_hint);
            grantBtn.setVisibility(View.GONE);
        }
    }

    // ---- permissions --------------------------------------------------------

    private String[] storagePerms() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO,
            };
        }
        return new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE };
    }

    private boolean hasStoragePerm() {
        for (String p : storagePerms()) {
            if (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_PERM) {
            if (hasStoragePerm()) { grantBtn.setVisibility(View.GONE); loadApks(); }
            else updateChrome();
        }
    }

    // ---- load APK files -----------------------------------------------------

    private void loadApks() {
        io.execute(() -> {
            ArrayList<ApkItem> found = new ArrayList<>();
            ArrayList<String> seenPaths = new ArrayList<>();

            // 1. MediaStore.Files, filtered to the package-archive mime type.
            //    Yields a content:// URI (file:// installs are blocked on API 24+).
            Uri filesUri = MediaStore.Files.getContentUri("external");
            String[] proj = {
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATA,
            };
            String sel = MediaStore.Files.FileColumns.MIME_TYPE + "=?";
            try (Cursor c = getContentResolver().query(
                    filesUri, proj, sel, new String[]{ APK_MIME }, null)) {
                if (c != null) {
                    int idc = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
                    int nmc = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int szc = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE);
                    int dac = c.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                    while (c.moveToNext()) {
                        ApkItem it = new ApkItem();
                        long id = c.getLong(idc);
                        it.contentUri = ContentUris.withAppendedId(filesUri, id);
                        it.name = nmc >= 0 ? c.getString(nmc) : null;
                        it.size = szc >= 0 ? c.getLong(szc) : 0;
                        it.filePath = dac >= 0 ? c.getString(dac) : null;
                        if (it.name == null && it.filePath != null)
                            it.name = new File(it.filePath).getName();
                        if (it.name == null) it.name = "package.apk";
                        if (it.filePath != null) seenPaths.add(it.filePath);
                        found.add(it);
                    }
                }
            } catch (Exception ignored) { }

            // 2. Best-effort direct scan of /sdcard/Download for anything the
            //    MediaStore index missed (subject to scoped-storage visibility).
            try {
                File dl = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                scanDir(dl, found, seenPaths);
            } catch (Exception ignored) { }

            Collections.sort(found, new Comparator<ApkItem>() {
                @Override public int compare(ApkItem a, ApkItem b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });

            ui.post(() -> {
                apks.clear();
                apks.addAll(found);
                apkAdapter.notifyDataSetChanged();
                if (showingInstall) updateChrome();
            });

            // 3. parse label / version / icon for each (needs a real file path)
            for (ApkItem it : found) parseArchive(it);
        });
    }

    private void scanDir(File dir, ArrayList<ApkItem> out, ArrayList<String> seen) {
        if (dir == null || !dir.isDirectory()) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) continue;
            if (!f.getName().toLowerCase().endsWith(".apk")) continue;
            if (seen.contains(f.getAbsolutePath())) continue;
            ApkItem it = new ApkItem();
            it.name = f.getName();
            it.size = f.length();
            it.filePath = f.getAbsolutePath();
            out.add(it);
            seen.add(f.getAbsolutePath());
        }
    }

    /** Copy a content URI into cache so PackageManager can parse it. */
    private File cacheCopy(ApkItem it) {
        try {
            File out = new File(getCacheDir(), "parse.apk");
            try (InputStream in = getContentResolver().openInputStream(it.contentUri);
                 OutputStream os = new FileOutputStream(out)) {
                if (in == null) return null;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void parseArchive(ApkItem it) {
        io.execute(() -> {
            String path = it.filePath;
            File tmp = null;
            if ((path == null || !new File(path).canRead()) && it.contentUri != null) {
                tmp = cacheCopy(it);
                if (tmp != null) path = tmp.getAbsolutePath();
            }
            if (path != null) {
                try {
                    PackageManager pm = getPackageManager();
                    PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_META_DATA);
                    if (pi != null) {
                        ApplicationInfo ai = pi.applicationInfo;
                        ai.sourceDir = path;
                        ai.publicSourceDir = path;
                        it.label = String.valueOf(pm.getApplicationLabel(ai));
                        it.version = pi.versionName;
                        try { it.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) { }
                    }
                } catch (Exception ignored) { }
            }
            it.parsed = true;
            if (tmp != null) { try { tmp.delete(); } catch (Exception ignored) { } }
            ui.post(() -> apkAdapter.notifyDataSetChanged());
        });
    }

    // ---- load installed user apps ------------------------------------------

    private void loadApps() {
        io.execute(() -> {
            ArrayList<AppItem> found = new ArrayList<>();
            PackageManager pm = getPackageManager();
            String self = getPackageName();
            try {
                for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                    boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                            || (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                    if (system) continue;
                    if (ai.packageName.equals(self)) continue;
                    AppItem it = new AppItem();
                    it.pkg = ai.packageName;
                    it.label = String.valueOf(pm.getApplicationLabel(ai));
                    try { it.icon = pm.getApplicationIcon(ai); } catch (Exception ignored) { }
                    try {
                        PackageInfo pi = pm.getPackageInfo(ai.packageName, 0);
                        it.version = pi.versionName;
                    } catch (Exception ignored) { }
                    found.add(it);
                }
            } catch (Exception ignored) { }

            Collections.sort(found, new Comparator<AppItem>() {
                @Override public int compare(AppItem a, AppItem b) {
                    return a.label.compareToIgnoreCase(b.label);
                }
            });

            ui.post(() -> {
                apps.clear();
                apps.addAll(found);
                appAdapter.notifyDataSetChanged();
                if (!showingInstall) updateChrome();
            });
        });
    }

    // ---- actions ------------------------------------------------------------

    private void install(ApkItem it) {
        // On API 26+ the app must be an approved install source.
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.not_allowed_install, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        if (it.contentUri != null) {
            launchInstall(it.contentUri);
        } else if (it.filePath != null) {
            // file-only entry: index it to obtain an installable content:// URI
            MediaScannerConnection.scanFile(this, new String[]{ it.filePath },
                    new String[]{ APK_MIME }, (p, uri) -> ui.post(() -> {
                        if (uri != null) launchInstall(uri);
                        else Toast.makeText(this, R.string.unknown_pkg,
                                Toast.LENGTH_SHORT).show();
                    }));
        }
    }

    private void launchInstall(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivity(i);
        } catch (Exception e) {
            // fallback: ACTION_VIEW with the package-archive mime
            try {
                Intent v = new Intent(Intent.ACTION_VIEW);
                v.setDataAndType(uri, APK_MIME);
                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(v);
            } catch (Exception ignored) {
                Toast.makeText(this, R.string.unknown_pkg, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openApp(AppItem it) {
        Intent li = getPackageManager().getLaunchIntentForPackage(it.pkg);
        if (li != null) startActivity(li);
        else Toast.makeText(this, R.string.no_launch, Toast.LENGTH_SHORT).show();
    }

    private void appInfo(AppItem it) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + it.pkg)));
        } catch (Exception ignored) { }
    }

    private void uninstall(AppItem it) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + it.pkg)));
        } catch (Exception ignored) { }
    }

    // ---- helpers ------------------------------------------------------------

    private static String fmtSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] u = { "B", "KB", "MB", "GB" };
        int g = (int) (Math.log10(bytes) / 3);
        if (g >= u.length) g = u.length - 1;
        double v = bytes / Math.pow(1024, g);
        return (v >= 100 ? String.format("%.0f", v) : String.format("%.1f", v)) + " " + u[g];
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void styleAvatar(ImageView avatar, Drawable icon, int fallbackRes) {
        if (icon != null) {
            avatar.setImageDrawable(icon);
            avatar.setColorFilter(null);
            avatar.setBackground(null);
            avatar.setPadding(0, 0, 0, 0);
        } else {
            avatar.setImageResource(fallbackRes);
            avatar.setColorFilter(cText2);
            GradientDrawable oval = new GradientDrawable();
            oval.setShape(GradientDrawable.OVAL);
            oval.setColor(cSurface2);
            avatar.setBackground(oval);
            int ap = dp(11);
            avatar.setPadding(ap, ap, ap, ap);
        }
    }

    // ---- adapters -----------------------------------------------------------

    private ImageView newAvatar() {
        ImageView avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return avatar;
    }

    private ImageButton newIconBtn(int iconRes, int tint) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(iconRes);
        b.setBackgroundResource(R.drawable.btn_icon);
        b.setColorFilter(tint);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(13);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    /** Shared row scaffold: [card] avatar + (title/caption) + trailing buttons. */
    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(14);
        row.setPadding(padH, dp(10), padH, dp(10));
        row.setMinimumHeight(dp(76));
        row.setBackgroundResource(R.drawable.bg_card);

        ImageView avatar = newAvatar();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(48), dp(48));
        row.addView(avatar, alp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(14);
        clp.rightMargin = dp(10);
        row.addView(col, clp);

        TextView title = new TextView(this);
        title.setTextColor(cText);
        title.setTextSize(17);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView caption = new TextView(this);
        caption.setTextColor(cText2);
        caption.setTextSize(13);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cap.topMargin = dp(2);
        col.addView(caption, cap);

        return row;
    }

    private LinearLayout wrapRow(LinearLayout row) {
        // outer container gives cards vertical spacing within the ListView
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(5), 0, dp(5));
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private final class ApkAdapter extends BaseAdapter {
        @Override public int getCount() { return apks.size(); }
        @Override public Object getItem(int p) { return apks.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout wrap;
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                wrap = (LinearLayout) convertView;
                row = (LinearLayout) wrap.getChildAt(0);
            } else {
                row = newRow();
                row.addView(newIconBtn(R.drawable.ic_download, cAccent),
                        new LinearLayout.LayoutParams(dp(52), dp(52)));
                wrap = wrapRow(row);
            }

            ApkItem it = apks.get(position);
            ImageView avatar = (ImageView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView title = (TextView) col.getChildAt(0);
            TextView caption = (TextView) col.getChildAt(1);
            ImageButton go = (ImageButton) row.getChildAt(2);

            styleAvatar(avatar, it.icon, R.drawable.ic_file);
            title.setText(it.label != null ? it.label : it.name);
            StringBuilder sub = new StringBuilder(fmtSize(it.size));
            if (it.version != null) sub.append("  ·  v").append(it.version);
            else if (it.label != null) sub.append("  ·  ").append(it.name);
            caption.setText(sub.toString());

            go.setOnClickListener(v -> install(it));
            return wrap;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int p) { return apps.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout wrap;
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                wrap = (LinearLayout) convertView;
                row = (LinearLayout) wrap.getChildAt(0);
            } else {
                row = newRow();
                row.addView(newIconBtn(R.drawable.ic_settings, cText2),
                        new LinearLayout.LayoutParams(dp(52), dp(52)));
                ImageButton del = newIconBtn(R.drawable.ic_delete, cText2);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(52), dp(52));
                dlp.leftMargin = dp(4);
                row.addView(del, dlp);
                wrap = wrapRow(row);
            }

            AppItem it = apps.get(position);
            ImageView avatar = (ImageView) row.getChildAt(0);
            LinearLayout col = (LinearLayout) row.getChildAt(1);
            TextView title = (TextView) col.getChildAt(0);
            TextView caption = (TextView) col.getChildAt(1);
            ImageButton info = (ImageButton) row.getChildAt(2);
            ImageButton del = (ImageButton) row.getChildAt(3);

            styleAvatar(avatar, it.icon, R.drawable.ic_file);
            title.setText(it.label);
            caption.setText(it.version != null ? it.pkg + "  ·  v" + it.version : it.pkg);

            info.setOnClickListener(v -> appInfo(it));
            del.setOnClickListener(v -> uninstall(it));
            return wrap;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}
