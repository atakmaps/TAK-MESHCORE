package com.atakmaps.meshcore.plugin.bluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.atakmaps.meshcore.plugin.util.CallsignUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persists mesh node contact lists locally so the Contacts picker can open instantly.
 * Keyed by the connected radio's Bluetooth address; refreshed from the device in background.
 */
public final class MeshDeviceContactCache {

    private static final String PREF_SYNCED_AT_SUFFIX = "_synced_at_ms";
    private static final String PREF_CONTACTS_SUFFIX = "_json";

    private MeshDeviceContactCache() {
    }

    private static SharedPreferences prefs(Context context) {
        Context ctx = context;
        try {
            com.atakmap.android.maps.MapView mv =
                    com.atakmap.android.maps.MapView.getMapView();
            if (mv != null) {
                ctx = mv.getContext();
            }
        } catch (Exception ignored) {
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    @Nullable
    public static String normalizeDeviceAddress(@Nullable String address) {
        if (address == null || address.trim().isEmpty()) {
            return null;
        }
        return address.trim().toUpperCase(Locale.US);
    }

    private static String contactsKey(@NonNull String deviceAddress) {
        return "meshcore_device_contacts" + PREF_CONTACTS_SUFFIX + "_"
                + deviceAddress.replace(':', '_');
    }

    private static String syncedAtKey(@NonNull String deviceAddress) {
        return "meshcore_device_contacts" + PREF_SYNCED_AT_SUFFIX + "_"
                + deviceAddress.replace(':', '_');
    }

    public static long getSyncedAtMs(Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null) {
            return 0L;
        }
        return prefs(context).getLong(syncedAtKey(addr), 0L);
    }

    public static boolean isStale(Context context, @Nullable String deviceAddress, long maxAgeMs) {
        long syncedAt = getSyncedAtMs(context, deviceAddress);
        if (syncedAt <= 0L) {
            return true;
        }
        return System.currentTimeMillis() - syncedAt > maxAgeMs;
    }

    @NonNull
    public static List<BtConnectionManager.MeshDeviceContact> load(
            Context context, @Nullable String deviceAddress) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null) {
            return new ArrayList<>();
        }
        String json = prefs(context).getString(contactsKey(addr), null);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        return decodeContacts(json);
    }

    public static void save(Context context, @Nullable String deviceAddress,
                            @NonNull List<BtConnectionManager.MeshDeviceContact> contacts) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null) {
            return;
        }
        prefs(context).edit()
                .putString(contactsKey(addr), encodeContacts(contacts))
                .putLong(syncedAtKey(addr), System.currentTimeMillis())
                .apply();
    }

    public static void upsertFromDeviceContact(Context context, @Nullable String deviceAddress,
                                               @NonNull BtConnectionManager.MeshDeviceContact contact) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || contact.pubKeyHex == null) {
            return;
        }
        String key = contact.pubKeyHex.trim().toUpperCase(Locale.US);
        List<BtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        boolean replaced = false;
        for (int i = 0; i < contacts.size(); i++) {
            BtConnectionManager.MeshDeviceContact existing = contacts.get(i);
            if (existing.pubKeyHex != null
                    && existing.pubKeyHex.toUpperCase(Locale.US).equals(key)) {
                contacts.set(i, contact);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            contacts.add(contact);
        }
        save(context, addr, contacts);
    }

    public static void removeByPubKey(Context context, @Nullable String deviceAddress,
                                      @Nullable String pubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || pubKeyHex == null || pubKeyHex.trim().isEmpty()) {
            return;
        }
        String key = pubKeyHex.trim().toUpperCase(Locale.US);
        List<BtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        if (contacts.isEmpty()) {
            return;
        }
        boolean removed = false;
        for (int i = contacts.size() - 1; i >= 0; i--) {
            BtConnectionManager.MeshDeviceContact c = contacts.get(i);
            if (c.pubKeyHex != null && pubKeysMatch(key, c.pubKeyHex.toUpperCase(Locale.US))) {
                contacts.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            save(context, addr, contacts);
        }
    }

    public static void updateFavoriteFlag(Context context, @Nullable String deviceAddress,
                                          @Nullable String pubKeyHex, boolean favorite) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || pubKeyHex == null || pubKeyHex.trim().isEmpty()) {
            return;
        }
        List<BtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        if (contacts.isEmpty()) {
            return;
        }
        String key = pubKeyHex.trim().toUpperCase(Locale.US);
        List<BtConnectionManager.MeshDeviceContact> updated = new ArrayList<>(contacts.size());
        boolean changed = false;
        for (BtConnectionManager.MeshDeviceContact c : contacts) {
            if (c.pubKeyHex != null && pubKeysMatch(key, c.pubKeyHex.toUpperCase(Locale.US))) {
                int flags = favorite
                        ? (c.flags | BtConnectionManager.CONTACT_FLAG_FAVORITE)
                        : (c.flags & ~BtConnectionManager.CONTACT_FLAG_FAVORITE);
                updated.add(new BtConnectionManager.MeshDeviceContact(
                        c.pubKeyHex, c.type, flags, c.outPathLen, c.outPath, c.name,
                        c.lastAdvertTimestamp, c.gpsLat, c.gpsLon, c.lastMod));
                changed = true;
            } else {
                updated.add(c);
            }
        }
        if (changed) {
            save(context, addr, updated);
        }
    }

    @Nullable
    public static BtConnectionManager.MeshDeviceContact findByNodeName(
            Context context, @Nullable String deviceAddress, @Nullable String nameHint) {
        if (nameHint == null || nameHint.trim().isEmpty()) {
            return null;
        }
        String bare = nameHint.trim();
        if (bare.toUpperCase(Locale.US).startsWith("ANDROID-")) {
            bare = bare.substring("ANDROID-".length());
        }
        String key = CallsignUtil.alphanumericCallsignKey(bare);
        String radio = CallsignUtil.toRadioCallsign(bare).toUpperCase(Locale.US);
        for (BtConnectionManager.MeshDeviceContact contact : load(context, deviceAddress)) {
            if (contact.name == null) {
                continue;
            }
            String contactName = contact.name.trim();
            if (CallsignUtil.isSameCallsignAlias(contactName, bare)
                    || CallsignUtil.isSameCallsignAlias(contactName, key)
                    || contactName.equalsIgnoreCase(radio)) {
                return contact;
            }
        }
        return null;
    }

    @Nullable
    public static BtConnectionManager.MeshDeviceContact findByPubKeyPrefix(
            Context context, @Nullable String deviceAddress, @Nullable String prefix12) {
        if (prefix12 == null || prefix12.length() < 12) {
            return null;
        }
        String prefix = prefix12.trim().toUpperCase(Locale.US).substring(0, 12);
        for (BtConnectionManager.MeshDeviceContact contact : load(context, deviceAddress)) {
            if (contact.pubKeyHex != null
                    && contact.pubKeyHex.toUpperCase(Locale.US).startsWith(prefix)) {
                return contact;
            }
        }
        return null;
    }

    @Nullable
    public static String resolvePubKeyHexFromNodeCaches(Context context,
                                                        @Nullable String prefix12) {
        if (prefix12 == null || prefix12.length() < 12) {
            return null;
        }
        String prefix = prefix12.trim().toUpperCase(Locale.US).substring(0, 12);
        String[] prefKeys = {
                "meshcore_mesh_node_cache_v1",
                "meshcore_mesh_repeater_cache_v1"
        };
        SharedPreferences prefs = prefs(context);
        for (String prefKey : prefKeys) {
            String raw = prefs.getString(prefKey, "[]");
            if (raw == null || raw.isEmpty()) {
                continue;
            }
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                    if (pubKey.startsWith(prefix)) {
                        return pubKey;
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        return null;
    }

    /**
     * Keep plugin Contacts picker in sync when a favorite is set from the map or ATAK pane.
     */
    public static void syncFavoriteFromUid(Context context, @Nullable String deviceAddress,
                                             @Nullable String pubKeyPrefix12,
                                             @Nullable String displayName, int contactType,
                                             @Nullable String fullPubKeyHex) {
        String addr = normalizeDeviceAddress(deviceAddress);
        if (addr == null || pubKeyPrefix12 == null || pubKeyPrefix12.length() < 12) {
            return;
        }
        String prefix = pubKeyPrefix12.trim().toUpperCase(Locale.US).substring(0, 12);
        BtConnectionManager.MeshDeviceContact existing = findByPubKeyPrefix(context, addr, prefix);
        if (existing != null) {
            updateFavoriteFlag(context, addr, existing.pubKeyHex, true);
            return;
        }
        String pubKey = fullPubKeyHex;
        if (pubKey == null || pubKey.length() < 64) {
            pubKey = resolvePubKeyHexFromNodeCaches(context, prefix);
        }
        if (pubKey == null || pubKey.length() < 64) {
            return;
        }
        String name = displayName != null && !displayName.trim().isEmpty()
                ? displayName.trim() : prefix;
        List<BtConnectionManager.MeshDeviceContact> contacts = load(context, addr);
        contacts.add(new BtConnectionManager.MeshDeviceContact(
                pubKey, contactType, BtConnectionManager.CONTACT_FLAG_FAVORITE,
                0, name, 0, 0.0, 0.0,
                (int) (System.currentTimeMillis() / 1000L)));
        save(context, addr, contacts);
    }

    private static boolean pubKeysMatch(@NonNull String a, @NonNull String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() >= 12 && b.length() >= 12) {
            return a.regionMatches(0, b, 0, 12);
        }
        return false;
    }

    @NonNull
    private static String encodeContacts(@NonNull List<BtConnectionManager.MeshDeviceContact> contacts) {
        JSONArray arr = new JSONArray();
        for (BtConnectionManager.MeshDeviceContact c : contacts) {
            try {
                JSONObject o = new JSONObject();
                o.put("pubKeyHex", c.pubKeyHex);
                o.put("type", c.type);
                o.put("flags", c.flags);
                o.put("outPathLen", c.outPathLen);
                if (c.outPath != null && c.outPath.length > 0) {
                    o.put("outPathHex", bytesToHex(c.outPath));
                }
                o.put("name", c.name);
                o.put("lastAdvertTimestamp", c.lastAdvertTimestamp);
                o.put("gpsLat", c.gpsLat);
                o.put("gpsLon", c.gpsLon);
                o.put("lastMod", c.lastMod);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    @NonNull
    private static List<BtConnectionManager.MeshDeviceContact> decodeContacts(@NonNull String json) {
        List<BtConnectionManager.MeshDeviceContact> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new BtConnectionManager.MeshDeviceContact(
                        o.optString("pubKeyHex", ""),
                        o.optInt("type", 0),
                        o.optInt("flags", 0),
                        o.optInt("outPathLen", 0),
                        decodeOutPathHex(o.optString("outPathHex", null)),
                        o.optString("name", ""),
                        o.optInt("lastAdvertTimestamp", 0),
                        o.optDouble("gpsLat", 0.0),
                        o.optDouble("gpsLon", 0.0),
                        o.optInt("lastMod", 0)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    @Nullable
    private static byte[] decodeOutPathHex(@Nullable String hex) {
        if (hex == null || hex.trim().isEmpty()) {
            return null;
        }
        String cleaned = hex.trim();
        if (cleaned.length() % 2 != 0) {
            return null;
        }
        int len = cleaned.length() / 2;
        byte[] out = new byte[Math.min(len, 64)];
        try {
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(cleaned.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (len < 64) {
            byte[] padded = new byte[64];
            System.arraycopy(out, 0, padded, 0, out.length);
            return padded;
        }
        return out;
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
