package com.atakmaps.meshcore.plugin.protocol;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Ping-reply slot timing and over-the-air net slot distribution (TYPE_NET_SLOT_CONFIG).
 */
public final class NetSlotConfig {

    private static final String TAG = "MeshCore.NetSlot";

    public static final String PREF_SLOT_COUNT = "meshcore_ping_slot_count";
    public static final String PREF_SLOT_TIME_SEC = "meshcore_ping_slot_time_sec";
    public static final String PREF_ADMIN_SETTINGS_ENABLED = "meshcore_admin_settings_enabled";
    public static final String PREF_NET_SLOT_CONFIG_SEQ = "meshcore_net_slot_config_seq";
    public static final String PREF_LAST_NET_SLOT_ISSUER = "meshcore_last_net_slot_issuer";

    public static final int DEFAULT_SLOT_COUNT = 20;
    public static final float DEFAULT_SLOT_TIME_SEC = 2.5f;

    public static final int MIN_SLOT_COUNT = 4;
    public static final int MAX_SLOT_COUNT = 128;
    public static final float MIN_SLOT_TIME_SEC = 1.0f;
    public static final float MAX_SLOT_TIME_SEC = 10.0f;

    private static final byte PAYLOAD_MAGIC_0 = 'U';
    private static final byte PAYLOAD_MAGIC_1 = 'V';
    private static final byte PAYLOAD_VERSION = 1;
    /** magic(2) + ver(1) + slots(2) + tenths(2) + seq(4) + callsign(6) */
    private static final int PAYLOAD_LEN = 17;

    private NetSlotConfig() {
    }

    public static void ensureDefaults(Context context) {
        SharedPreferences prefs = getPrefs(context);
        if (!prefs.contains(PREF_SLOT_COUNT) || !prefs.contains(PREF_SLOT_TIME_SEC)) {
            prefs.edit()
                    .putString(PREF_SLOT_COUNT, String.valueOf(DEFAULT_SLOT_COUNT))
                    .putString(PREF_SLOT_TIME_SEC, String.valueOf(DEFAULT_SLOT_TIME_SEC))
                    .apply();
        }
    }

    public static SharedPreferences getPrefs(Context context) {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : context;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static int getSlotCount(Context context) {
        return clampSlotCount(parseInt(getPrefs(context).getString(
                PREF_SLOT_COUNT, String.valueOf(DEFAULT_SLOT_COUNT)),
                DEFAULT_SLOT_COUNT));
    }

    public static float getSlotTimeSec(Context context) {
        return clampSlotTime(parseFloat(getPrefs(context).getString(
                PREF_SLOT_TIME_SEC, String.valueOf(DEFAULT_SLOT_TIME_SEC)),
                DEFAULT_SLOT_TIME_SEC));
    }

    public static void saveLocalSlotSettings(Context context, int slotCount, float slotTimeSec) {
        getPrefs(context).edit()
                .putString(PREF_SLOT_COUNT, String.valueOf(clampSlotCount(slotCount)))
                .putString(PREF_SLOT_TIME_SEC, String.valueOf(clampSlotTime(slotTimeSec)))
                .apply();
    }

    public static int computeSlotIndex(String callsign, int slotCount) {
        String radio = CallsignUtil.toRadioCallsign(callsign);
        if (radio == null || radio.isEmpty()) {
            radio = "UNKNOWN";
        }
        return Math.floorMod(radio.trim().toUpperCase(Locale.US).hashCode(), slotCount);
    }

    public static long computeReplyDelayMs(Context context, String callsign) {
        int slots = getSlotCount(context);
        float slotSec = getSlotTimeSec(context);
        int index = computeSlotIndex(callsign, slots);
        return (long) (index * slotSec * 1000.0f);
    }

    /**
     * Short outbound stagger for operator-initiated group/contact-list CoT (not the full
     * ping-reply slot span). Spreads collisions when several leaders sync groups at once.
     */
    public static long computeGroupSyncStaggerMs(Context context, String callsign) {
        int buckets = 8;
        int index = computeSlotIndex(callsign, buckets);
        float slotSec = getSlotTimeSec(context);
        long stagger = (long) ((index % 4) * (slotSec * 250.0f));
        return Math.min(stagger, 1500L);
    }

    public static byte[] encodePayload(int slotCount, float slotTimeSec, int sequence, String issuerCallsign) {
        int tenths = Math.round(clampSlotTime(slotTimeSec) * 10f);
        byte[] call = padCallsign(issuerCallsign);
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(PAYLOAD_MAGIC_0);
        buf.put(PAYLOAD_MAGIC_1);
        buf.put(PAYLOAD_VERSION);
        buf.putShort((short) clampSlotCount(slotCount));
        buf.putShort((short) tenths);
        buf.putInt(sequence);
        buf.put(call);
        return buf.array();
    }

    /**
     * Apply net slot assignment from the radio. Returns true if prefs were updated.
     */
    public static boolean applyFromNetwork(Context context, byte[] payload, String senderCallsign) {
        Decoded decoded = decodePayload(payload);
        if (decoded == null) {
            return false;
        }
        SharedPreferences prefs = getPrefs(context);
        int lastSeq = prefs.getInt(PREF_NET_SLOT_CONFIG_SEQ, 0);
        if (decoded.sequence < lastSeq) {
            Log.d(TAG, "Ignoring stale net slot config seq=" + decoded.sequence
                    + " last=" + lastSeq);
            return false;
        }
        prefs.edit()
                .putString(PREF_SLOT_COUNT, String.valueOf(decoded.slotCount))
                .putString(PREF_SLOT_TIME_SEC, String.valueOf(decoded.slotTimeSec))
                .putInt(PREF_NET_SLOT_CONFIG_SEQ, decoded.sequence)
                .putString(PREF_LAST_NET_SLOT_ISSUER,
                        decoded.issuerCallsign != null ? decoded.issuerCallsign : senderCallsign)
                .apply();
        Log.i(TAG, "Net slot config applied: slots=" + decoded.slotCount
                + " time=" + decoded.slotTimeSec + "s seq=" + decoded.sequence
                + " from " + senderCallsign);
        return true;
    }

    private static Decoded decodePayload(byte[] payload) {
        if (payload == null || payload.length < PAYLOAD_LEN) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.order(ByteOrder.BIG_ENDIAN);
        if (buf.get() != PAYLOAD_MAGIC_0 || buf.get() != PAYLOAD_MAGIC_1) {
            return null;
        }
        byte ver = buf.get();
        if (ver != PAYLOAD_VERSION) {
            return null;
        }
        int slotCount = clampSlotCount(buf.getShort() & 0xFFFF);
        float slotTimeSec = clampSlotTime((buf.getShort() & 0xFFFF) / 10f);
        int sequence = buf.getInt();
        byte[] callBytes = new byte[6];
        buf.get(callBytes);
        String issuer = new String(callBytes, StandardCharsets.US_ASCII).trim();
        Decoded d = new Decoded();
        d.slotCount = slotCount;
        d.slotTimeSec = slotTimeSec;
        d.sequence = sequence;
        d.issuerCallsign = issuer;
        return d;
    }

    private static byte[] padCallsign(String callsign) {
        String radio = CallsignUtil.toRadioCallsign(callsign);
        if (radio == null || radio.isEmpty()) {
            radio = "UNK";
        }
        byte[] raw = radio.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[6];
        int len = Math.min(6, raw.length);
        System.arraycopy(raw, 0, out, 0, len);
        return out;
    }

    private static int clampSlotCount(int value) {
        return Math.max(MIN_SLOT_COUNT, Math.min(MAX_SLOT_COUNT, value));
    }

    private static float clampSlotTime(float value) {
        return Math.max(MIN_SLOT_TIME_SEC, Math.min(MAX_SLOT_TIME_SEC, value));
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static final class Decoded {
        int slotCount;
        float slotTimeSec;
        int sequence;
        String issuerCallsign;
    }
}
