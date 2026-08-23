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

    /** After direct RF from a peer, prefer zero-hop over a cached repeater {@code out_path}. */
    private static final long DIRECT_RX_ROUTE_TTL_MS = 5L * 60L * 1000L;
    /** Throttle firmware {@code GET_CONTACT_BY_KEY} refresh requests per peer. */
    private static final long CONTACT_REFRESH_MIN_INTERVAL_MS = 60L * 1000L;
    /**
     * After a directed delivery ACK via repeater ({@code hops > 0}), ignore periodic
     * GPS/ping for {@link #lastDirectInboundMsByNameKey} so idle marker TX keeps
     * {@code out_path=0x1}. Same TTL as direct-RX window.
     */
    private static final long REPEATER_ROUTE_CONFIRMED_TTL_MS = DIRECT_RX_ROUTE_TTL_MS;

    interface StaleRepeaterPathListener {
        void onStaleRepeaterPath(String pubKeyHex, String peerLabel);
    }

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
    /** Last time a peer was heard with zero-hop / direct RX metadata. */
    private final ConcurrentHashMap<String, Long> lastDirectInboundMsByNameKey =
            new ConcurrentHashMap<>();
    /**
     * TX escalation when directed delivery fails: 0=normal cache, 1=force zero-hop,
     * 2+=flood.
     */
    private final ConcurrentHashMap<String, Integer> txRouteEscalationByNameKey =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastContactRefreshRequestMsByNameKey =
            new ConcurrentHashMap<>();
    /** Last time directed TX via repeater ({@code hops > 0}) was ACK'd for a peer. */
    private final ConcurrentHashMap<String, Long> lastRepeaterRouteConfirmedMsByNameKey =
            new ConcurrentHashMap<>();
    private volatile StaleRepeaterPathListener staleRepeaterPathListener;

    MeshContactRouteResolver(Context context) {
        this.context = context.getApplicationContext();
    }

    void setStaleRepeaterPathListener(@Nullable StaleRepeaterPathListener listener) {
        this.staleRepeaterPathListener = listener;
    }

    void clear() {
        liveByPubKeyPrefix.clear();
        liveByNameKey.clear();
        lastDirectInboundMsByNameKey.clear();
        txRouteEscalationByNameKey.clear();
        lastContactRefreshRequestMsByNameKey.clear();
        lastRepeaterRouteConfirmedMsByNameKey.clear();
    }

    void indexContact(BtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || contact.pubKeyHex == null || contact.pubKeyHex.length() < 12) {
            return;
        }
        String prefix = contact.pubKeyHex.trim().toUpperCase(Locale.US).substring(0, 12);
        liveByPubKeyPrefix.put(prefix, contact);
        indexNameKeys(contact);
        if (MeshPathUtil.pathHopCount(contact.outPathLen) <= 0) {
            clearRepeaterRouteConfirmed(contact.name);
        }
        if (contact.name != null && !contact.name.trim().isEmpty()) {
            Log.d(TAG, "Contact route name=" + contact.name.trim()
                    + " out_path=0x" + Integer.toHexString(contact.outPathLen & 0xFF)
                    + " txRoute=" + MeshPathUtil.formatTxRoute(contact.outPathLen));
        }
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

    enum InboundPathSignal {
        /** Stale {@code out_path} refresh only (no recent-direct tracking). */
        STALE_REFRESH,
        /** Chat / CoT / ACK — may mark peer recently direct. */
        APP_TRAFFIC,
        /** Periodic GPS beacon or ping — may mark direct unless repeater path is confirmed. */
        PERIODIC_BEACON
    }

    void noteInboundPath(@Nullable String peerHint, int rxPathLen) {
        noteInboundPath(peerHint, rxPathLen, InboundPathSignal.STALE_REFRESH);
    }

    /**
     * Record inbound RF from a peer. App traffic and (usually) beacons can mark a peer
     * recently direct for stale-repeater override; firmware contact refresh still runs
     * for all zero-hop / unknown RX when cache says {@code hops > 0}.
     */
    void noteInboundPath(@Nullable String peerHint, int rxPathLen, InboundPathSignal signal) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return;
        }
        if (signal != InboundPathSignal.STALE_REFRESH && rxPathLen <= 0) {
            boolean suppressPeriodic = signal == InboundPathSignal.PERIODIC_BEACON
                    && isRepeaterRouteRecentlyConfirmed(peerHint.trim());
            if (!suppressPeriodic) {
                for (String key : nameKeysForHint(peerHint.trim())) {
                    lastDirectInboundMsByNameKey.put(key, System.currentTimeMillis());
                    txRouteEscalationByNameKey.remove(key);
                }
            } else {
                Log.d(TAG, "Skip recent-direct from periodic beacon: peer=" + peerHint.trim()
                        + " (repeater route confirmed recently)");
            }
        }
        if (rxPathLen > 0) {
            return;
        }
        maybeRefreshStaleRepeaterPath(peerHint.trim());
    }

    /** Record successful directed delivery via repeater ({@code hops > 0}). */
    void noteRepeaterRouteConfirmed(@Nullable String peerHint) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String key : nameKeysForHint(peerHint.trim())) {
            lastRepeaterRouteConfirmedMsByNameKey.put(key, now);
        }
    }

    int resolveTxHopCount(@Nullable String peerHint, @Nullable String deviceAddress) {
        DirectRoute route = resolveDirectRoute(peerHint, deviceAddress);
        if (route == null) {
            return -1;
        }
        return MeshPathUtil.pathHopCount(route.pathLenEncoded);
    }

    private void maybeRefreshStaleRepeaterPath(String peerHint) {
        BtConnectionManager.MeshDeviceContact contact =
                lookupContact(peerHint, null);
        if (contact == null || MeshPathUtil.pathHopCount(contact.outPathLen) <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean shouldRefresh = true;
        for (String key : nameKeysForHint(peerHint)) {
            Long last = lastContactRefreshRequestMsByNameKey.get(key);
            if (last != null && now - last < CONTACT_REFRESH_MIN_INTERVAL_MS) {
                shouldRefresh = false;
                break;
            }
        }
        if (!shouldRefresh) {
            return;
        }
        for (String key : nameKeysForHint(peerHint)) {
            lastContactRefreshRequestMsByNameKey.put(key, now);
        }
        StaleRepeaterPathListener listener = staleRepeaterPathListener;
        if (listener != null && contact.pubKeyHex != null && !contact.pubKeyHex.trim().isEmpty()) {
            String label = contact.name != null && !contact.name.trim().isEmpty()
                    ? contact.name.trim() : peerHint;
            listener.onStaleRepeaterPath(contact.pubKeyHex.trim(), label);
        }
    }

    /** Escalate TX routing after directed delivery fails (zero-hop, then flood). */
    void noteTxRouteFailure(@Nullable String peerHint) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return;
        }
        for (String key : nameKeysForHint(peerHint.trim())) {
            txRouteEscalationByNameKey.merge(key, 1, (oldVal, inc) ->
                    Math.min(2, oldVal + inc));
        }
    }

    void clearTxRouteFailure(@Nullable String peerHint) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return;
        }
        for (String key : nameKeysForHint(peerHint.trim())) {
            txRouteEscalationByNameKey.remove(key);
        }
    }

    @Nullable
    DirectRoute resolveDirectRoute(@Nullable String peerHint, @Nullable String deviceAddress) {
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return null;
        }
        int escalation = escalationLevel(peerHint.trim());
        if (escalation >= 2) {
            Log.d(TAG, "Route escalate flood: peer=" + peerHint.trim()
                    + " (directed TX retries exhausted repeater/zero-hop)");
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
        int cachedHops = MeshPathUtil.pathHopCount(contact.outPathLen);
        if (cachedHops > 0
                && (escalation >= 1 || isRecentlyDirect(peerHint.trim()))) {
            Log.d(TAG, "Route override zero-hop: peer=" + peerHint.trim()
                    + " cached out_path=0x" + Integer.toHexString(contact.outPathLen & 0xFF)
                    + " txRoute=" + MeshPathUtil.formatTxRoute(contact.outPathLen)
                    + (escalation >= 1 ? " reason=tx-retry-escalation" : " reason=recent-direct-rx"));
            return new DirectRoute(0, new byte[0]);
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

    private int escalationLevel(String peerHint) {
        int max = 0;
        for (String key : nameKeysForHint(peerHint)) {
            Integer level = txRouteEscalationByNameKey.get(key);
            if (level != null && level > max) {
                max = level;
            }
        }
        return max;
    }

    private boolean isRepeaterRouteRecentlyConfirmed(String peerHint) {
        long now = System.currentTimeMillis();
        for (String key : nameKeysForHint(peerHint)) {
            Long last = lastRepeaterRouteConfirmedMsByNameKey.get(key);
            if (last != null && now - last <= REPEATER_ROUTE_CONFIRMED_TTL_MS) {
                return true;
            }
        }
        return false;
    }

    private void clearRepeaterRouteConfirmed(@Nullable String peerName) {
        if (peerName == null || peerName.trim().isEmpty()) {
            return;
        }
        for (String key : nameKeysForHint(peerName.trim())) {
            lastRepeaterRouteConfirmedMsByNameKey.remove(key);
        }
    }

    private boolean isRecentlyDirect(String peerHint) {
        long now = System.currentTimeMillis();
        for (String key : nameKeysForHint(peerHint)) {
            Long last = lastDirectInboundMsByNameKey.get(key);
            if (last != null && now - last <= DIRECT_RX_ROUTE_TTL_MS) {
                return true;
            }
        }
        return false;
    }

    private java.util.List<String> nameKeysForHint(String peerHint) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        if (peerHint == null || peerHint.trim().isEmpty()) {
            return keys;
        }
        String trimmed = peerHint.trim();
        addNameKey(keys, normalizeNameKey(trimmed));
        String upper = trimmed.toUpperCase(Locale.US);
        if (upper.startsWith(ANDROID_PREFIX)) {
            String bare = trimmed.substring(ANDROID_PREFIX.length());
            addNameKey(keys, normalizeNameKey(bare));
            addNameKey(keys, CallsignUtil.toRadioCallsign(bare).toUpperCase(Locale.US));
            addNameKey(keys, CallsignUtil.alphanumericCallsignKey(bare));
        } else {
            addNameKey(keys, CallsignUtil.toRadioCallsign(trimmed).toUpperCase(Locale.US));
            addNameKey(keys, CallsignUtil.alphanumericCallsignKey(trimmed));
        }
        if (trimmed.length() >= 12 && trimmed.matches("(?i)[0-9A-F]+")) {
            addNameKey(keys, trimmed.toUpperCase(Locale.US).substring(0, 12));
        }
        return keys;
    }

    private static void addNameKey(java.util.List<String> keys, @Nullable String key) {
        if (key != null && !key.isEmpty() && !keys.contains(key)) {
            keys.add(key);
        }
    }
}
