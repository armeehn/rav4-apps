package com.reveng.files;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal clean-room content provider that exposes on-disk files to other apps
 * for ACTION_VIEW / ACTION_SEND without exposing raw file:// URIs (which throw
 * FileUriExposedException on N+). Pure android.* framework - no AndroidX.
 *
 * URI form: content://com.reveng.files.fileprovider/raw/<Uri.encode(abs path)>
 * Read-only, exported=false, grantUriPermissions=true so per-intent grants work.
 */
public class FileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.reveng.files.fileprovider";

    public static Uri uriFor(File f) {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("raw")
                .appendPath(f.getAbsolutePath())
                .build();
    }

    private File fileFrom(Uri uri) {
        // path segments: ["raw", "<absolute path>"]
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() < 2) return null;
        File f = new File(seg.get(1));
        return servable(f) ? f : null;
    }

    /**
     * The URI carries a raw absolute path, so this is the only thing standing between a grant
     * and any file the process can read. Two rules:
     *
     * <ul>
     *   <li>Resolve first — a path containing {@code ..} or a symlink must be judged by where
     *       it actually lands, not by how it is spelled.</li>
     *   <li>Never our own private storage. The browser UI cannot show it, so nothing legitimate
     *       ever asks for it, and it holds this app's preferences and databases.</li>
     * </ul>
     */
    private boolean servable(File f) {
        Context ctx = getContext();
        if (ctx == null) return false;
        try {
            String path = f.getCanonicalPath();
            String priv = ctx.getDataDir().getCanonicalPath() + File.separator;
            return !path.startsWith(priv);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // Read-only by contract. Silently handing back a read-only descriptor for "w" made a
        // writer fail on its first write instead of on the open.
        if (mode != null && !mode.equals("r")) {
            throw new FileNotFoundException("read-only provider, requested mode: " + mode);
        }
        File f = fileFrom(uri);
        if (f == null) return null;
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        File f = fileFrom(uri);
        return f == null ? null : mimeOf(f.getName());
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase();
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = fileFrom(uri);
        if (f == null) return null;
        String[] cols = { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(new Object[]{ f.getName(), f.length() });
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
