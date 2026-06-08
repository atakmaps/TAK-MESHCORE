package com.atakmaps.meshcore.plugin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * Cross-process QR scan result handoff between plugin and ATAK processes.
 */
public class QrResultProvider extends ContentProvider {

    public static final String AUTHORITY = "com.atakmaps.meshcore.plugin.qrresult";
    public static final Uri PENDING_URI = Uri.parse("content://" + AUTHORITY + "/pending");

    private static final String PREFS = "qr_pending";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TS = "ts";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = prefs();
        String content = prefs.getString(KEY_CONTENT, "");
        long ts = prefs.getLong(KEY_TS, 0L);
        MatrixCursor cursor = new MatrixCursor(new String[]{KEY_CONTENT, KEY_TS});
        if (content != null && !content.isEmpty()) {
            cursor.addRow(new Object[]{content, ts});
        }
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (values == null) return uri;
        String content = values.getAsString(KEY_CONTENT);
        Long ts = values.getAsLong(KEY_TS);
        prefs().edit()
                .putString(KEY_CONTENT, content != null ? content : "")
                .putLong(KEY_TS, ts != null ? ts : System.currentTimeMillis())
                .apply();
        return uri;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        prefs().edit().remove(KEY_CONTENT).remove(KEY_TS).apply();
        return 1;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (values == null) return 0;
        insert(uri, values);
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/qr-result";
    }

    private SharedPreferences prefs() {
        Context ctx = getContext();
        if (ctx == null) {
            throw new IllegalStateException("QrResultProvider not initialized");
        }
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void clearPending(Context context) {
        try {
            context.getContentResolver().delete(PENDING_URI, null, null);
        } catch (Exception ignored) {
        }
    }

    public static void storePending(Context context, String content) {
        if (content == null || content.isEmpty()) {
            clearPending(context);
            return;
        }
        ContentValues values = new ContentValues();
        values.put(KEY_CONTENT, content);
        values.put(KEY_TS, System.currentTimeMillis());
        try {
            context.getContentResolver().insert(PENDING_URI, values);
        } catch (Exception e) {
            android.util.Log.w("MeshCore.QrResult", "storePending failed", e);
        }
    }

    public static String consumePending(Context context, long maxAgeMs) {
        try (Cursor cursor = context.getContentResolver().query(
                PENDING_URI, new String[]{KEY_CONTENT, KEY_TS}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            long ts = cursor.getLong(1);
            String content = cursor.getString(0);
            clearPending(context);
            if (content == null || content.isEmpty()) return null;
            if (System.currentTimeMillis() - ts > maxAgeMs) return null;
            return content.trim();
        } catch (Exception e) {
            android.util.Log.w("MeshCore.QrResult", "consumePending failed", e);
            return null;
        }
    }
}
