package com.atakmaps.meshcore.plugin.bluetooth;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.atakmaps.meshcore.plugin.util.CallsignUtil;
import com.atakmaps.meshcore.plugin.util.MeshPathUtil;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves ATAK peer labels to MeshCore device-contact routes for direct TX.
 */
final class MeshContactRouteResolver {

    private static final String TAG = "MeshCore.Route";

    static final class DirectRoute {
        final int pathLenEncoded;
        final byte[] pathWireBytes;

        DirectRoute(int pathLenEncoded, byte[] pathWireBytes) {
            this.pathLenEncoded = pathLenEncoded;
            this.pathWireBytes = pathWireBytes;
        }
    }

    private static final String MESH_NODE_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_PREFIX = "MESHCORE-RPTR-";
    private static final String ANDROID_PREFIX = "ANDROID-";

    private final Context context;
    private final ConcurrentHashMap<String, BtConnectionManager.MeshDeviceContact> liveByPubKeyPrefix =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BtConnectionManager.MeshDeviceContact> liveByNameKey =
            new ConcurrentHashMap<>();

    MeshContactRouteResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    void clear() {
        liveByPubKeyPrefix.clear();
        liveByNameKey.clear();
    }

    void indexContact(BtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || contact.pubKeyHex == null || contact.pubKeyHex.length() < 12) {
            return;
        }
        String prefix = contact.pubKeyHex.trim().toUpperCase(Locale.US).substring(0, 12);
        liveByPubKeyPrefix.put(prefix, contact);
        indexNameKeys(contact);
    }

    private void indexNameKeys(BtConnectionManager.MeshDeviceContact contact) {
        if (contact.name == null || contact.name.trim().isEmpty()) {
            return;
        }
        String raw = contact.name.trim();
        putNameKey(normalizeNameKey(raw), contact);
        putNameKey(CallsignUtil.toRadioCallsign(raw).toUpperCase(Locale.US), contact);
        putNameKey(CallsignUtil.alphanumericCallsignKey(raw), contact);
    }

    private void putNameKey(String key, BtConnectionManager.MeshDeviceContact contact) {
        if (key != null && !key.isEmpty()) {
            liveByNameKey.put(key, contact);
        }
    }

    @Nullable
    DirectRoute resolveDirectRoute(@Nullable String peerHint, @Nullable String deviceAddress) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return null;
        }
        BtConnectionManager.MeshDeviceContact contact =
                lookupContact(peerHint.trim(), deviceAddress);
        if (contact == null) {
            return null;
        }
        if (!MeshPathUtil.isUsableDirectPath(contact.outPathLen)) {
            return null;
        }
        byte[] wire = MeshPathUtil.pathWireBytes(contact.outPathLen, contact.outPath);
        if (wire == null) {
            return null;
        }
        return new DirectRoute(contact.outPathLen & 0xFF, wire);
    }

    /** Human-readable reason direct routing failed (for TX logs). */
    String describeRouteMiss(@Nullable String peerHint, @Nullable String deviceAddress) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return "empty peer hint";
        }
        BtConnectionManager.MeshDeviceContact contact =
                lookupContact(peerHint.trim(), deviceAddress);
        if (contact == null) {
            return "no firmware contact for hint=" + peerHint.trim();
        }
        int encoded = contact.outPathLen & 0xFF;
        if (!MeshPathUtil.isUsableDirectPath(contact.outPathLen)) {
            if (encoded == 0xFF) {
                return "contact " + contact.name + " out_path unknown (0xFF) — firmware will flood";
            }
            return "contact " + contact.name + " out_path unusable (0x"
                    + Integer.toHexString(encoded) + ")";
        }
        byte[] wire = MeshPathUtil.pathWireBytes(contact.outPathLen, contact.outPath);
        if (wire == null) {
            return "contact " + contact.name + " out_path wire missing";
        }
        int hops = MeshPathUtil.pathHopCount(contact.outPathLen);
        return hops == 0 ? "ok zero-hop direct" : "ok hops=" + hops;
    }

    @Nullable
    private BtConnectionManager.MeshDeviceContact lookupContact(String hint, String deviceAddress) {
        String upper = hint.toUpperCase(Locale.US);
        if (upper.startsWith(MESH_NODE_PREFIX) || upper.startsWith(MESH_RPTR_PREFIX)) {
            String suffix = upper.startsWith(MESH_NODE_PREFIX)
                    ? upper.substring(MESH_NODE_PREFIX.length())
                    : upper.substring(MESH_RPTR_PREFIX.length());
            BtConnectionManager.MeshDeviceContact c = findByPubKeyPrefix(suffix);
            if (c != null) {
                return c;
            }
            return MeshDeviceContactCache.findByPubKeyPrefix(context, deviceAddress, suffix);
        }
        if (upper.startsWith(ANDROID_PREFIX)) {
            String bare = hint.substring(ANDROID_PREFIX.length());
            BtConnectionManager.MeshDeviceContact byName = findByNameHint(bare);
            if (byName != null) {
                return byName;
            }
            return MeshDeviceContactCache.findByNodeName(context, deviceAddress, bare);
        }
        BtConnectionManager.MeshDeviceContact byName = findByNameHint(hint);
        if (byName != null) {
            return byName;
        }
        if (hint.length() >= 12 && hint.matches("(?i)[0-9A-F]+")) {
            BtConnectionManager.MeshDeviceContact byPk = findByPubKeyPrefix(hint);
            if (byPk != null) {
                return byPk;
            }
            return MeshDeviceContactCache.findByPubKeyPrefix(context, deviceAddress, hint);
        }
        return MeshDeviceContactCache.findByNodeName(context, deviceAddress, hint);
    }

    @Nullable
    private BtConnectionManager.MeshDeviceContact findByPubKeyPrefix(String prefixOrFull) {
        if (prefixOrFull == null || prefixOrFull.length() < 12) {
            return null;
        }
        String prefix = prefixOrFull.trim().toUpperCase(Locale.US).substring(0, 12);
        BtConnectionManager.MeshDeviceContact live = liveByPubKeyPrefix.get(prefix);
        if (live != null) {
            return live;
        }
        for (java.util.Map.Entry<String, BtConnectionManager.MeshDeviceContact> e
                : liveByPubKeyPrefix.entrySet()) {
            if (e.getKey().startsWith(prefix) || prefix.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    @Nullable
    private BtConnectionManager.MeshDeviceContact findByNameHint(String nameHint) {
        if (nameHint == null || nameHint.trim().isEmpty()) {
            return null;
        }
        String trimmed = nameHint.trim();
        BtConnectionManager.MeshDeviceContact live = findByNameKey(normalizeNameKey(trimmed));
        if (live != null) {
            return live;
        }
        live = findByNameKey(CallsignUtil.toRadioCallsign(trimmed).toUpperCase(Locale.US));
        if (live != null) {
            return live;
        }
        for (java.util.Map.Entry<String, BtConnectionManager.MeshDeviceContact> e
                : liveByNameKey.entrySet()) {
            if (matchesNameHint(e.getKey(), trimmed) || matchesNameHint(e.getValue().name, trimmed)) {
                return e.getValue();
            }
        }
        return null;
    }

    @Nullable
    private BtConnectionManager.MeshDeviceContact findByNameKey(String nameKey) {
        if (nameKey == null || nameKey.isEmpty()) {
            return null;
        }
        BtConnectionManager.MeshDeviceContact live = liveByNameKey.get(nameKey);
        if (live != null) {
            return live;
        }
        for (java.util.Map.Entry<String, BtConnectionManager.MeshDeviceContact> e
                : liveByNameKey.entrySet()) {
            if (e.getKey().equalsIgnoreCase(nameKey)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean matchesNameHint(@Nullable String stored, String hint) {
        if (stored == null || hint == null) {
            return false;
        }
        return CallsignUtil.isSameCallsignAlias(stored, hint);
    }

    private static String normalizeNameKey(String name) {
        return CallsignUtil.alphanumericCallsignKey(name);
    }
}
