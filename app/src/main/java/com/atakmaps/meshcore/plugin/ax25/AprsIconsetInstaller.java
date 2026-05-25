package com.atakmaps.meshcore.plugin.ax25;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Ensures the APRS iconset package is available for import on ATAK devices.
 *
 * This does not force-import into ATAK internals; it stages the zip into the
 * normal ATAK import folder and prompts the user once.
 */
public final class AprsIconsetInstaller {
    private static final String TAG = "MeshCore.Iconset";
    private static final String ICONSET_UID = AprsSymbolMapper.ICONSET_UID;
    private static final String ICONSET_ASSET = "APRS-Symbols-APRSdroid.zip";
    private static final String ICONSET_FILENAME = "APRS.zip";
    private static final String REMINDER_CHANNEL_ID = "uvpro_aprs_iconset";
    private static final int REMINDER_NOTIFICATION_ID = 22001;
    private static volatile boolean reminderVisible = false;
    private static volatile long lastDialogMs = 0L;
    private static final long DIALOG_THROTTLE_MS = 45_000L;
    private static volatile boolean dialogShowing = false;
    private static final String IMPORT_INSTRUCTION =
            "Select Point Dropper>Gear Icon>Add Iconset\n"
                    + "Path= /sdcard/atak/tools/import/aprs.zip";

    private AprsIconsetInstaller() {
    }

    /**
     * @return true when iconset is still missing (reminder should continue)
     */
    public static boolean ensureStagedAndPromptIfMissing(Context pluginContext, Context uiContext) {
        if (pluginContext == null || uiContext == null) {
            return false;
        }
        try {
            if (isIconsetInstalled()) {
                clearPersistentReminder(uiContext);
                return false;
            }

            boolean staged = stageIconsetZip(pluginContext);
            if (!staged) {
                Log.w(TAG, "APRS iconset missing and staging failed");
            }
            showPersistentReminder(uiContext);
            showInAppDialogReminder(uiContext);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "ensureStagedAndPromptIfMissing failed: " + e.getMessage(), e);
            return false;
        }
    }

    public static void clearPersistentReminder(Context uiContext) {
        if (uiContext == null) {
            return;
        }
        try {
            NotificationManager nm = (NotificationManager)
                    uiContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.cancel(REMINDER_NOTIFICATION_ID);
                reminderVisible = false;
            }
            dialogShowing = false;
        } catch (Exception e) {
            Log.w(TAG, "clearPersistentReminder failed: " + e.getMessage());
        }
    }

    public static boolean isIconsetInstalled() {
        File db = new File(Environment.getExternalStorageDirectory(), "atak/Databases/iconsets.sqlite");
        if (!db.exists()) {
            return false;
        }

        SQLiteDatabase sqlite = null;
        Cursor cursor = null;
        try {
            sqlite = SQLiteDatabase.openDatabase(db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            cursor = sqlite.rawQuery("SELECT 1 FROM iconsets WHERE uid=? LIMIT 1",
                    new String[]{ICONSET_UID});
            return cursor.moveToFirst();
        } catch (Exception e) {
            Log.w(TAG, "isIconsetInstalled query failed: " + e.getMessage());
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
            if (sqlite != null) {
                sqlite.close();
            }
        }
    }

    private static boolean stageIconsetZip(Context pluginContext) {
        File importDir = new File(Environment.getExternalStorageDirectory(), "atak/tools/import");
        if (!importDir.exists() && !importDir.mkdirs()) {
            Log.w(TAG, "Failed to create import dir: " + importDir.getAbsolutePath());
            return false;
        }

        File outFile = new File(importDir, ICONSET_FILENAME);
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = pluginContext.getAssets().open(ICONSET_ASSET);
            out = new FileOutputStream(outFile, false);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            Log.i(TAG, "Staged APRS iconset for ATAK import: " + outFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "stageIconsetZip failed: " + e.getMessage(), e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void showPersistentReminder(Context uiContext) {
        try {
            if (reminderVisible) {
                return;
            }
            NotificationManager nm = (NotificationManager)
                    uiContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        REMINDER_CHANNEL_ID,
                        "UV-PRO APRS Setup",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Guidance for APRS iconset import");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Intent launchIntent = uiContext.getPackageManager()
                    .getLaunchIntentForPackage(uiContext.getPackageName());
            PendingIntent pi = null;
            if (launchIntent != null) {
                launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= PendingIntent.FLAG_IMMUTABLE;
                }
                pi = PendingIntent.getActivity(uiContext, 0, launchIntent, flags);
            }

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(uiContext, REMINDER_CHANNEL_ID)
                    : new Notification.Builder(uiContext);
            b.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setOnlyAlertOnce(true)
                    .setContentTitle("APRS iconset import required")
                    .setContentText("Select Point Dropper>Gear Icon>Add Iconset");
            if (pi != null) {
                b.setContentIntent(pi);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                b.setStyle(new Notification.BigTextStyle().bigText(IMPORT_INSTRUCTION));
            }
            nm.notify(REMINDER_NOTIFICATION_ID, b.build());
            reminderVisible = true;
        } catch (Exception e) {
            Log.w(TAG, "showPersistentReminder failed: " + e.getMessage());
        }
    }

    private static void showInAppDialogReminder(Context uiContext) {
        if (!(uiContext instanceof Activity)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (dialogShowing || (now - lastDialogMs) < DIALOG_THROTTLE_MS) {
            return;
        }
        lastDialogMs = now;
        Activity activity = (Activity) uiContext;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) {
                dialogShowing = false;
                return;
            }
            dialogShowing = true;
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("APRS iconset required")
                        .setMessage(IMPORT_INSTRUCTION)
                        .setCancelable(true)
                        .setPositiveButton("OK", (d, which) -> {
                            dialogShowing = false;
                            d.dismiss();
                        })
                        .setOnDismissListener(d -> dialogShowing = false)
                        .show();
            } catch (Exception e) {
                dialogShowing = false;
                Log.w(TAG, "showInAppDialogReminder failed: " + e.getMessage());
            }
        });
    }
}
