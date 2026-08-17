package com.atakmaps.meshcore.plugin.bluetooth;

import android.content.Context;

import androidx.annotation.Nullable;

import com.atakmaps.meshcore.plugin.util.CallsignUtil;
import com.atakmaps.meshcore.plugin.util.MeshPathUtil;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves ATAK peer labels to MeshCore device-contact routes for direct TX.
 */
final class MeshContactRouteResolver {

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
        String nameKey = normalizeNameKey(contact.name);
        if (!nameKey.isEmpty()) {
            liveByNameKey.put(nameKey, contact);
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
        if (wire == null || wire.length == 0) {
            return null;
        }
        return new DirectRoute(contact.outPathLen & 0xFF, wire);
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
            BtConnectionManager.MeshDeviceContact byName =
                    findByNameKey(normalizeNameKey(hint.substring(ANDROID_PREFIX.length())));
            if (byName != null) {
                return byName;
            }
            return MeshDeviceContactCache.findByNodeName(
                    context, deviceAddress, hint.substring(ANDROID_PREFIX.length()));
        }
        BtConnectionManager.MeshDeviceContact byName = findByNameKey(normalizeNameKey(hint));
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
    private BtConnectionManager.MeshDeviceContact findByNameKey(String nameKey) {
        if (nameKey == null || nameKey.isEmpty()) {
            return null;
        }
        BtConnectionManager.MeshDeviceContact live = liveByNameKey.get(nameKey);
        if (live != null) {
            return live;
        }
        String radio = CallsignUtil.toRadioCallsign(nameKey).toUpperCase(Locale.US);
        if (!radio.isEmpty()) {
            live = liveByNameKey.get(radio);
            if (live != null) {
                return live;
            }
        }
        for (java.util.Map.Entry<String, BtConnectionManager.MeshDeviceContact> e
                : liveByNameKey.entrySet()) {
            String key = e.getKey();
            if (key.equalsIgnoreCase(nameKey)
                    || CallsignUtil.toRadioCallsign(key).equalsIgnoreCase(radio)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String normalizeNameKey(String name) {
        return CallsignUtil.alphanumericCallsignKey(name);
    }
}
