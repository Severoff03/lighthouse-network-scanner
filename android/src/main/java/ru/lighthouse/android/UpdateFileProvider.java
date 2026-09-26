package ru.lighthouse.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/** Minimal private provider used to hand the downloaded APK to Android's installer. */
public final class UpdateFileProvider extends ContentProvider {
    private File apk;

    @Override public boolean onCreate() {
        apk = new File(getContext().getCacheDir(), "lighthouse-update.apk");
        return true;
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!isApk(uri) || apk == null || !apk.isFile()) throw new FileNotFoundException("APK update not found");
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!isApk(uri) || apk == null || !apk.isFile()) return null;
        String[] columns = projection == null ? new String[]{"_display_name", "_size"} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if ("_display_name".equals(columns[i])) values[i] = apk.getName();
            else if ("_size".equals(columns[i])) values[i] = apk.length();
            else values[i] = null;
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    private boolean isApk(Uri uri) {
        return uri != null && "updates".equals(uri.getPathSegments().isEmpty() ? "" : uri.getPathSegments().get(0))
            && uri.getPathSegments().size() == 2 && "lighthouse-update.apk".equals(uri.getPathSegments().get(1));
    }

}
