package com.atakmaps.meshcore.plugin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.atakmaps.meshcore.plugin.protocol.NetSlotConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Password gate for hidden administrative settings. Only a SHA-256 hash of the
 * password is stored in source — never the plaintext.
 */
public final class AdminAccessGate {

    public static final String PREF_UNLOCKED = "meshcore_admin_access_unlocked";

    /** Same digest as UV-PRO plugin — plaintext password is not stored in repo. */
    public static final String PASSWORD_SHA256_HEX =
            "74035b144baba522d46f26d7a7f17b89b3bd5aec1114769fc5322a4dfb93ae29";

    private AdminAccessGate() {
    }

    public static boolean isConfigured() {
        return PASSWORD_SHA256_HEX != null
                && !PASSWORD_SHA256_HEX.isEmpty()
                && !"PENDING_SET_PASSWORD".equals(PASSWORD_SHA256_HEX);
    }

    public static boolean isUnlocked(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_UNLOCKED, false);
    }

    public static boolean verifyPassword(String password) {
        if (!isConfigured() || password == null || password.isEmpty()) {
            return false;
        }
        String hash = sha256Hex(password.trim());
        return hash.equalsIgnoreCase(PASSWORD_SHA256_HEX);
    }

    public static boolean unlock(Context ctx, String password) {
        if (!verifyPassword(password)) {
            return false;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit()
                .putBoolean(PREF_UNLOCKED, true)
                .apply();
        return true;
    }

    public static void lock(Context ctx) {
        if (ctx == null) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit()
                .putBoolean(PREF_UNLOCKED, false)
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                .apply();
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
