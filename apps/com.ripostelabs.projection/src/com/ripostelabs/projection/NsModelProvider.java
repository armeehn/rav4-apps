package com.ripostelabs.projection;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.ripostelabs.projection.ns.Manifest;
import com.ripostelabs.projection.ns.Model;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * The mic's model choice, read-only, for other apps of the suite (Voice Recorder's A/B test
 * mode) so they hear what a call would:
 *
 *   query  content://com.ripostelabs.projection.nsmodel/choice
 *          one row: model (STANDARD or CAR_TUNED), version (0 = none, the standard model runs),
 *          label ("2026-10-02.1", "" for none)
 *   open   content://com.ripostelabs.projection.nsmodel/active.bin
 *          the car-tuned blob in use, the format of jni/rnnoise/weights/rnnoise_little.bin
 *
 * A reader needs a {@code <queries><provider android:authorities="…nsmodel"/></queries>}.
 */
public final class NsModelProvider extends ContentProvider {

    static final String COL_MODEL = "model";
    static final String COL_VERSION = "version";
    static final String COL_LABEL = "label";
    private static final String PATH_CHOICE = "choice";
    private static final String PATH_BLOB = "active.bin";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (!PATH_CHOICE.equals(uri.getLastPathSegment())) {
            return null;
        }
        Model model = MicPrefs.model(getContext());
        int version = model == Model.CAR_TUNED ? ModelUpdater.store(getContext()).activeVersion() : 0;
        MatrixCursor c = new MatrixCursor(new String[] {COL_MODEL, COL_VERSION, COL_LABEL});
        c.addRow(new Object[] {model.name(), version, version == 0 ? "" : Manifest.label(version)});
        return c;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!PATH_BLOB.equals(uri.getLastPathSegment()) || !"r".equals(mode)) {
            throw new FileNotFoundException(uri.toString());
        }
        File f = ModelUpdater.store(getContext()).activeFile();
        if (f == null || MicPrefs.model(getContext()) != Model.CAR_TUNED) {
            throw new FileNotFoundException("no car-tuned model in use");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
}
