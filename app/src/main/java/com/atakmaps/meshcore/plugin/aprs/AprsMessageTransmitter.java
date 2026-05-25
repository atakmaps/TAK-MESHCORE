package com.atakmaps.meshcore.plugin.aprs;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import java.util.Locale;

/**
 * Sends APRS text messages over KISS/AX.25 using standard {@code :addressee:text} payloads.
 */
public final class AprsMessageTransmitter {

    private static final String TAG = "MeshCore.APRS.MsgTx";
    private static final int MAX_TEXT_LEN = 67;
    private static final String PREF_APRS_ACK_COUNTER = "uvpro_aprs_ack_counter";
    private static final String PREF_APRS_CONTACT_RESPONDED_PREFIX = "uvpro_aprs_contact_responded_";
    private static final String PREF_APRS_CONTACT_ACK_SENT_PREFIX = "uvpro_aprs_contact_ack_sent_";

    private AprsMessageTransmitter() {
    }

    public static boolean sendMessage(Context context, BtConnectionManager btManager,
                                      String toCallsignRaw, String messageRaw) {
        if (context == null || btManager == null || !btManager.isConnected()) {
            return false;
        }
        if (btManager.isRadioSilenceEnabled()) {
            Log.w(TAG, "APRS message blocked (radio silence)");
            return false;
        }
        String fromBase = SettingsFragment.getAprsCallsign(context);
        if (!SettingsFragment.isValidAprsCallsign(fromBase)) {
            Log.w(TAG, "APRS message blocked (invalid FCC callsign)");
            return false;
        }

        String to = normalizeAddressee(toCallsignRaw);
        if (to.isEmpty()) {
            Log.w(TAG, "APRS message blocked (empty addressee)");
            return false;
        }
        String text = normalizeMessage(messageRaw);
        if (text.isEmpty()) {
            Log.w(TAG, "APRS message blocked (empty message)");
            return false;
        }

        SharedPreferences prefs = getPrefs(context);
        boolean requestAck = shouldRequestAck(prefs, to);
        String ackId = requestAck ? nextAckMessageId(prefs) : "";
        if (requestAck) {
            int maxBodyLen = MAX_TEXT_LEN - (1 + ackId.length()); // "{id"
            if (maxBodyLen < 1) {
                maxBodyLen = 1;
            }
            if (text.length() > maxBodyLen) {
                text = text.substring(0, maxBodyLen);
            }
            text = text + "{" + ackId;
        }

        int ssid = SettingsFragment.getAprsSsid(context);
        String payload = ":" + String.format(Locale.US, "%-9s", to) + ":" + text;
        try {
            Ax25Frame frame = Ax25Frame.createAprsFrame(fromBase, ssid, payload);
            boolean ok = btManager.sendKissFrame(frame.encode());
            if (ok) {
                if (requestAck) {
                    prefs.edit().putBoolean(PREF_APRS_CONTACT_ACK_SENT_PREFIX + to, true).apply();
                }
                Log.i(TAG, "APRS message TX " + fromBase + "-" + ssid + " -> " + to
                        + (requestAck ? " (ack " + ackId + ")" : ""));
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "APRS message TX failed", e);
            return false;
        }
    }

    /**
     * Send APRS delivery acknowledgment payload: {@code :ADDRESSEE:ackNN}.
     */
    public static boolean sendAcknowledgement(Context context, BtConnectionManager btManager,
                                              String toCallsignRaw, String messageIdRaw) {
        if (context == null || btManager == null || !btManager.isConnected()) {
            return false;
        }
        if (btManager.isRadioSilenceEnabled()) {
            Log.w(TAG, "APRS ack blocked (radio silence)");
            return false;
        }
        String fromBase = SettingsFragment.getAprsCallsign(context);
        if (!SettingsFragment.isValidAprsCallsign(fromBase)) {
            Log.w(TAG, "APRS ack blocked (invalid FCC callsign)");
            return false;
        }
        String to = normalizeAddressee(toCallsignRaw);
        if (to.isEmpty()) {
            Log.w(TAG, "APRS ack blocked (empty addressee)");
            return false;
        }
        String ackId = normalizeAckMessageId(messageIdRaw);
        if (ackId.isEmpty()) {
            return false;
        }
        int ssid = SettingsFragment.getAprsSsid(context);
        String payload = ":" + String.format(Locale.US, "%-9s", to) + ":ack" + ackId;
        try {
            Ax25Frame frame = Ax25Frame.createAprsFrame(fromBase, ssid, payload);
            boolean ok = btManager.sendKissFrame(frame.encode());
            if (ok) {
                Log.i(TAG, "APRS ack TX " + fromBase + "-" + ssid + " -> " + to
                        + " id=" + ackId);
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "APRS ack TX failed", e);
            return false;
        }
    }

    /**
     * Mark a remote station as having replied at least once; future outbound messages
     * to this contact will not request APRS ack IDs.
     */
    public static void markContactResponded(Context context, String fromCallsignRaw) {
        if (context == null) {
            return;
        }
        String peer = normalizeAddressee(fromCallsignRaw);
        if (peer.isEmpty()) {
            return;
        }
        getPrefs(context).edit()
                .putBoolean(PREF_APRS_CONTACT_RESPONDED_PREFIX + peer, true)
                .apply();
    }

    public static String normalizeAddressee(String callsign) {
        if (callsign == null) {
            return "";
        }
        String s = callsign.trim().toUpperCase(Locale.US);
        if (s.startsWith("ANDROID-")) {
            s = s.substring("ANDROID-".length());
        }
        s = s.replaceAll("[^A-Z0-9\\-]", "");
        if (s.length() > 9) {
            s = s.substring(0, 9);
        }
        return s;
    }

    private static String normalizeMessage(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > MAX_TEXT_LEN) {
            s = s.substring(0, MAX_TEXT_LEN);
        }
        return s;
    }

    private static String normalizeAckMessageId(String id) {
        if (id == null) {
            return "";
        }
        String s = id.trim().toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
        if (s.length() > 5) {
            s = s.substring(0, 5);
        }
        return s;
    }

    private static SharedPreferences getPrefs(Context context) {
        Context c = context != null ? context.getApplicationContext() : null;
        if (c == null) {
            c = context;
        }
        if (c == null) {
            throw new IllegalStateException("Context unavailable for APRS message prefs");
        }
        return PreferenceManager.getDefaultSharedPreferences(c);
    }

    private static boolean shouldRequestAck(SharedPreferences prefs, String addressee) {
        boolean alreadyRequested = prefs.getBoolean(
                PREF_APRS_CONTACT_ACK_SENT_PREFIX + addressee, false);
        boolean alreadyResponded = prefs.getBoolean(
                PREF_APRS_CONTACT_RESPONDED_PREFIX + addressee, false);
        return !alreadyRequested && !alreadyResponded;
    }

    private static String nextAckMessageId(SharedPreferences prefs) {
        int current = prefs.getInt(PREF_APRS_ACK_COUNTER, 0);
        int next = (current + 1) % 1296; // 36^2 space (00..ZZ)
        prefs.edit().putInt(PREF_APRS_ACK_COUNTER, next).apply();
        String token = Integer.toString(current, 36).toUpperCase(Locale.US);
        if (token.length() < 2) {
            token = "0" + token;
        } else if (token.length() > 2) {
            token = token.substring(token.length() - 2);
        }
        return token;
    }
}

