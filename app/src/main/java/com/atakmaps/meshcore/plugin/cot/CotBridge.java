package com.atakmaps.meshcore.plugin.cot;

import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.util.Log;

import android.os.Bundle;

import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.chat.GeoChatService;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactPresenceDropdown;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.comms.CommsLogger;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.NetConnectString;
import com.atakmap.commoncommo.CoTSendMethod;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

import com.atakmaps.meshcore.plugin.BuildConfig;
import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.chat.GeoChatContactListHelper;
import com.atakmaps.meshcore.plugin.chat.InboundGroupSyncApplier;
import com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector;
import com.atakmaps.meshcore.plugin.protocol.RfSlottedCoTScheduler;
import com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator;
import com.atakmaps.meshcore.plugin.protocol.MeshCorePacket;
import com.atakmaps.meshcore.plugin.protocol.PacketFragmenter;
import com.atakmaps.meshcore.plugin.protocol.PingReplyNotifier;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;
import com.atakmaps.meshcore.plugin.MeshCoreContactHandler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Bridges between ATAK CoT events and the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives decoded data from PacketRouter
 *   - Builds CotEvent objects using CotBuilder
 *   - Dispatches them into ATAK's CoT processing pipeline
 *
 * Outbound (ATAK → radio):
 *   - Listens for local CoT events from ATAK
 *   - Compresses and fragments if needed
 *   - Sends via radio link
 */
public class CotBridge {

    private static final String TAG = "MeshCore.CotBridge";

    /** Same action as ATAK radial menu {@code actions/showdetails.xml}. */
    public static final String ACTION_COT_MARKER_DETAILS =
            "com.atakmap.android.cotdetails.COTINFO";

    /** Marker flag for MeshCore repeater advert points. */
    public static final String META_MESHCORE_MESH_REPEATER = "meshcore_mesh_repeater";
    /** Marker flag for MeshCore node advert points. */
    public static final String META_MESHCORE_MESH_NODE = "meshcore_mesh_node";
    /** Multi-line MeshCore details text for custom details panel. */
    public static final String META_MESHCORE_MESH_DETAILS = "meshcore_mesh_details";
    /** Inbound radio peers: ATAK uses CoT stale as marker TTL; keep ≥ 2h for sparse updates. */
    private static final long MIN_INBOUND_RADIO_STALE_MS = 2 * 60 * 60_000L;

    private static final long COT_RETRY_INTERVAL_MS   = 15_000L;  // 15s — faster recovery
    private static final int  COT_MAX_RETRIES          = 5;        // 5 retries × 15s = 75s window
    private static final long COT_DOUBLE_SEND_DELAY_MS = 3_000L;   // re-send 3s after first attempt

    private static final class PendingOutboundCot {
        final String cotUid;
        final CotEvent event;
        volatile int retryCount;
        PendingOutboundCot(String cotUid, CotEvent event) {
            this.cotUid = cotUid;
            this.event  = event;
            this.retryCount = 0;
        }
    }

    private final ConcurrentHashMap<String, PendingOutboundCot> pendingOutboundCots =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService cotRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MeshCore-CotRetry");
                t.setDaemon(true);
                return t;
            });

    private final Context pluginContext;
    private final MapView mapView;

    /** Set from UI thread once; BT read thread often cannot resolve {@link MapView#getDeviceUid()}. */
    private volatile String cachedLocalDeviceUidForGeoChat;
    private BtConnectionManager btManager;
    private String localCallsign = "OPENRL";
    private EncryptionManager encryptionManager;
    private CommsMapComponent.PreSendProcessor preSendProcessor;
    private BroadcastReceiver localCotBroadcastReceiver;
    private MapEventDispatcher.MapEventDispatchListener sharedMapItemListener;

    /** Catches outbound GeoChat: ATAK sends via CotMapComponent external dispatcher, not PreSend. */
    private CommsLogger outboundCommsLogger;

    /** Whether to relay all outgoing SA to radio (can flood the channel) */
    private boolean relayOutgoingSa = false;

    /**
     * UIDs that the plugin considers radio-transport contacts.
     * Populated when the plugin creates/registers contacts from radio packets.
     */
    private final Set<String> btechContactUids = ConcurrentHashMap.newKeySet();

    /**
     * Map plugin-created display identifiers to contact UIDs.
     * Key is typically the normalized callsign (upper) or a known chat-room label.
     */
    private final Map<String, String> btechIdToUid = new ConcurrentHashMap<>();

    private final RfSlottedCoTScheduler slottedCoTScheduler = new RfSlottedCoTScheduler();

    /** Minimum interval between SA Relay broadcasts onto RF (WiFi/TAK → Mesh). */
    private static final long SA_RELAY_RF_BROADCAST_INTERVAL_MS = 10 * 60_000L;
    private long lastSaRelay = 0;

    /** Per-UID throttle map for SA Relay to prevent channel flooding */
    private final Map<String, Long> saRelayLastSentByUid = new ConcurrentHashMap<>();
    /** Per-UID payload signature for SA Relay; unchanged payloads are suppressed. */
    private final Map<String, String> saRelayLastSignatureByUid = new ConcurrentHashMap<>();
    /** Short de-dupe window so PreSend + COT_PLACED do not double-transmit the same event. */
    private final Map<String, Long> recentLocalRelayKeys = new ConcurrentHashMap<>();
    private static final long LOCAL_RELAY_DEDUPE_MS = 1500L;
    /**
     * Net-wide map points (ATAK Auto Send): one RF chirp per marker at most this often.
     * WiFi/TAK rebroadcast is unchanged; only mesh radio relay is throttled.
     */
    private static final long BROADCAST_MAP_RF_MIN_INTERVAL_MS = 120_000L;
    private final Map<String, Long> lastBroadcastMapRfRelayByUid = new ConcurrentHashMap<>();
    private static final long INBOUND_CHAT_NOTIFY_DEDUPE_MS = 7000L;
    /** Avoid duplicate user alerts when redundant RF GeoChat CoT arrives. */
    private final Map<String, Long> inboundChatNotifyUntil = new ConcurrentHashMap<>();

    /**
     * Inbound network CoT types eligible for SA Relay (network → radio).
     * Matches friendly SA ({@code a-*-G…}), points/markers ({@code b-m-p…}), routes ({@code b-m-r…}).
     */
    private static final java.util.regex.Pattern SA_RELAY_TYPE_PATTERN =
            java.util.regex.Pattern.compile(
                    "^(a-[a-z]-G|b-m-p|b-m-r)");
    private static final Pattern COT_TIME_ATTR_PATTERN =
            Pattern.compile("\\s+(time|start|stale)=\"[^\"]*\"");
    private static final String ALL_CHAT_ROOMS = "All Chat Rooms";

    /**
     * Read SA Relay preference from plugin SharedPreferences.
     * Called on the radio-receive thread; SharedPreferences reads are thread-safe.
     */
    private boolean isSaRelayEnabled() {
        try {
            return com.atakmaps.meshcore.plugin.ui.SettingsFragment
                    .isSaRelayEnabled(pluginContext);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRfToTakUplinkEnabled() {
        try {
            return isSaRelayEnabled() && com.atakmaps.meshcore.plugin.ui.SettingsFragment
                    .isRfToTakUplinkEnabled(pluginContext);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Injected inbound GeoChat (and similar) is re-processed by core comms; PreSendProcessor
     * then sees the same b-t-f with toUIDs pointing at a BTECH contact and would re-transmit
     * over RF (echo loop, duplicate fragments, unknown receipt UIDs).
     */
    private static final long INBOUND_INJECT_NO_RELAY_MS = 10_000L;
    private final Map<String, Long> inboundInjectNoRelayUntil = new ConcurrentHashMap<>();

    /** Set after {@link ChatBridge} construction; used to send compact TYPE_CHAT with wire ACK ids. */
    private volatile ChatBridge chatBridge;
    private volatile com.atakmaps.meshcore.plugin.protocol.PacketRouter packetRouter;

    private void markInboundInjectSkipOutboundRelay(String cotUid) {
        if (cotUid == null || cotUid.isEmpty()) return;
        inboundInjectNoRelayUntil.put(cotUid,
                System.currentTimeMillis() + INBOUND_INJECT_NO_RELAY_MS);
    }

    private boolean shouldSkipOutboundRelayWasInboundInject(String cotUid) {
        if (cotUid == null) return false;
        Long until = inboundInjectNoRelayUntil.get(cotUid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            inboundInjectNoRelayUntil.remove(cotUid);
            return false;
        }
        return true;
    }

    public CotBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    /**
     * Snapshot local ATAK device/self-marker UID on the UI thread for GeoChat {@code chatgrp} uid1
     * when injecting inbound chat from Bluetooth RX (background thread).
     */
    public void refreshCachedLocalDeviceUidForGeoChat() {
        String u = tryResolveAtakSelfUidForChatGrp(mapView);
        if (u != null) {
            cachedLocalDeviceUidForGeoChat = u;
            Log.d(TAG, "Cached local ATAK UID for inbound GeoChat DMs: " + u);
        }
    }

    public void setRelayOutgoingSa(boolean relay) {
        this.relayOutgoingSa = relay;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    public void setChatBridge(ChatBridge chatBridge) {
        this.chatBridge = chatBridge;
    }

    public void setPacketRouter(com.atakmaps.meshcore.plugin.protocol.PacketRouter packetRouter) {
        this.packetRouter = packetRouter;
    }

    /** TAK endpoint available and MeshCore RF not connected (WiFi-only ping path). */
    public boolean canSendPingOverWifiNetwork() {
        if (!isTakNetworkAvailable()) {
            return false;
        }
        return !isRadioConnected();
    }

    private boolean isTakNetworkAvailable() {
        try {
            String endpoint = CotMapComponent.getEndpoint();
            return endpoint != null && !endpoint.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean sendPingOverWifiNetwork(String targetCallsign) {
        if (!canSendPingOverWifiNetwork()) {
            return false;
        }
        CotEvent event = CotBuilder.buildWifiPingCot(mapView, targetCallsign);
        if (event == null || !event.isValid()) {
            Log.w(TAG, "WiFi ping CoT build failed");
            return false;
        }
        try {
            com.atakmap.comms.CotDispatcher dispatcher =
                    CotMapComponent.getExternalDispatcher();
            if (dispatcher == null) {
                return false;
            }
            String target = targetCallsign != null ? targetCallsign.trim() : "";
            if (!target.isEmpty()) {
                IndividualContact peer = findWifiPingContact(target);
                if (peer != null) {
                    dispatcher.dispatchToContact(
                            event, peer, CoTSendMethod.POINT_TO_POINT);
                    Log.i(TAG, "WiFi directed ping sent to " + target);
                } else {
                    dispatcher.dispatchToBroadcast(event, CoTSendMethod.ANY);
                    Log.i(TAG, "WiFi ping broadcast (no contact for " + target + ")");
                }
            } else {
                dispatcher.dispatchToBroadcast(event, CoTSendMethod.ANY);
                Log.i(TAG, "WiFi ping broadcast");
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "WiFi ping send failed: " + e.getMessage());
            return false;
        }
    }

    public boolean sendSelfPositionOverWifiNetwork() {
        if (!isTakNetworkAvailable()) {
            return false;
        }
        try {
            CotEvent event = CotBuilder.buildWifiPingReplyCot(mapView);
            if (event == null || !event.isValid()) {
                return false;
            }
            com.atakmap.comms.CotDispatcher dispatcher =
                    CotMapComponent.getExternalDispatcher();
            if (dispatcher == null) {
                return false;
            }
            dispatcher.dispatchToBroadcast(event, CoTSendMethod.ANY);
            Log.i(TAG, "WiFi ping reply (position SA) broadcast");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "WiFi ping reply failed: " + e.getMessage());
            return false;
        }
    }

    private IndividualContact findWifiPingContact(String targetCallsign) {
        if (targetCallsign == null || targetCallsign.trim().isEmpty()) {
            return null;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            Contact match = com.atakmaps.meshcore.plugin.chat.ContactMergeUtil
                    .findContactByCallsignVariants(contacts, targetCallsign.trim());
            if (match instanceof IndividualContact) {
                return (IndividualContact) match;
            }
        } catch (Exception e) {
            Log.w(TAG, "findWifiPingContact failed", e);
        }
        return null;
    }

    private void maybeHandleInboundNetworkWifiPing(CotEvent event) {
        if (!CotBuilder.isWifiPingCot(event)) {
            return;
        }
        if (packetRouter == null) {
            return;
        }
        String localUid = null;
        try {
            localUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        String eventUid = event.getUID();
        if (localUid != null && eventUid != null
                && localUid.trim().equalsIgnoreCase(eventUid.trim())) {
            return;
        }
        packetRouter.handleInboundWifiPing(event);
    }

    /**
     * Register a contact UID as a MeshCore radio endpoint.
     * This is used to route ATAK "send to contact" actions to the radio link
     * without globally relaying all CoT.
     */
    public void registerBtechContactUid(String uid) {
        if (uid == null) return;
        String trimmed = uid.trim();
        if (trimmed.isEmpty()) return;
        btechContactUids.add(trimmed);
        btechContactUids.add(trimmed.toUpperCase(Locale.US));
    }

    /**
     * Register a plugin-created BTECH contact identifier (e.g. callsign) → UID mapping.
     * Used for routing GeoChat messages where ATAK does not provide explicit toUIDs.
     */
    public void registerBtechContactId(String id, String uid) {
        if (id == null || uid == null) return;
        String key = id.trim().toUpperCase();
        if (key.isEmpty()) return;
        btechIdToUid.put(key, uid);
        registerBtechContactUid(uid);
    }

    /**
     * Best ATAK callsign alias registered for a specific contact UID (reverse of
     * {@link #registerBtechContactId}). Prefers full callsign forms over compact wire keys.
     */
    public String resolveRegisteredCallsignForUid(String uid, String preferredNameHint) {
        if (uid == null || uid.trim().isEmpty()) {
            return null;
        }
        String uidKey = uid.trim();
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : btechIdToUid.entrySet()) {
            if (e == null || e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (uidKey.equalsIgnoreCase(e.getValue().trim())) {
                keys.add(e.getKey());
            }
        }
        if (keys.isEmpty()) {
            return null;
        }
        if (preferredNameHint != null && !preferredNameHint.trim().isEmpty()) {
            String hint = preferredNameHint.trim();
            String hintUpper = hint.toUpperCase(Locale.US);
            String hintFlat = hintUpper.replace("_", "").replace("-", "");
            for (String key : keys) {
                if (key.equalsIgnoreCase(hint)) {
                    return key;
                }
                String keyFlat = key.replace("_", "").replace("-", "");
                if (keyFlat.equalsIgnoreCase(hintFlat)) {
                    return key;
                }
            }
        }
        String best = keys.get(0);
        int bestScore = scoreRegisteredCallsignKey(best);
        for (int i = 1; i < keys.size(); i++) {
            String candidate = keys.get(i);
            int score = scoreRegisteredCallsignKey(candidate);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static int scoreRegisteredCallsignKey(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        int score = key.length();
        if (key.indexOf('_') >= 0 || key.indexOf('-') >= 0) {
            score += 1000;
        }
        if (key.length() > 6) {
            score += 500;
        }
        return score;
    }

    public boolean isBtechContactUid(String uid) {
        if (uid == null) {
            return false;
        }
        String trimmed = uid.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return btechContactUids.contains(trimmed)
                || btechContactUids.contains(trimmed.toUpperCase(Locale.US));
    }

    public boolean isRadioConnected() {
        return btManager != null && btManager.isConnected();
    }

    private static boolean isRelayableMapCotType(String type) {
        if (type == null || type.isEmpty()) {
            return false;
        }
        return type.startsWith("b-m-p")
                || type.startsWith("b-m-r")
                || type.startsWith("a-f-")
                || type.startsWith("a-h-")
                || type.startsWith("a-n-")
                || type.startsWith("a-u-")
                || type.startsWith("a-p-")
                || type.startsWith("u-")
                || type.startsWith("b-r-f-h")
                || type.startsWith("t-x-d-d");
    }

    private boolean isSelfPliBeacon(CotEvent event) {
        if (event == null) {
            return false;
        }
        String type = event.getType();
        if (type == null || !type.startsWith("a-f-")) {
            return false;
        }
        if (type.contains("-U-C")) {
            return true;
        }
        String uid = event.getUID();
        if (uid == null || uid.isEmpty()) {
            return false;
        }
        String localUid = cachedLocalDeviceUidForGeoChat;
        if (localUid == null || localUid.isEmpty()) {
            try {
                localUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
        }
        return uid.equals(localUid);
    }

    private boolean shouldRelayBroadcastMapCot(CotEvent event, String[] toUIDs) {
        if (event == null) {
            return false;
        }
        if (toUIDs != null && toUIDs.length > 0) {
            return false;
        }
        String type = event.getType();
        if (!isRelayableMapCotType(type)) {
            return false;
        }
        return !isSelfPliBeacon(event);
    }

    private boolean isInboundDirectedRfCotForThisDevice(CotEvent event) {
        String self = cachedLocalDeviceUidForGeoChat;
        if (self == null || self.trim().isEmpty()) {
            try {
                self = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
        }
        return CotBuilder.isDirectedCotForLocalDevice(event, self, this::resolveBtechUidForId);
    }

    private void relayContactTargetedCotOverRadio(CotEvent event, String[] toUIDs) {
        new Thread(() -> {
            CotEvent rfEvent = (toUIDs != null && toUIDs.length > 0)
                    ? CotBuilder.stampDirectedDestinations(event, toUIDs)
                    : event;
            sendCotOverRadio(rfEvent != null ? rfEvent : event);
        }).start();
    }

    /**
     * Contact-targeted map CoT (point/route/marker send-to-contact) when at least one
     * destination is RF-reachable. WiFi/TAK-only recipients rely on ATAK core unicast.
     */
    private boolean shouldRelayMapCotToContactUids(String[] toUIDs) {
        return hasAnyRfReachableMapRecipient(toUIDs);
    }

    /** No RF peer can ACK when all directed recipients are WiFi/TAK-only. */
    private boolean shouldRegisterCotRfRetry(CotEvent event) {
        java.util.List<String> destUids = CotBuilder.extractDirectedDestUids(event);
        if (!destUids.isEmpty()) {
            return shouldRelayMapCotToContactUids(
                    destUids.toArray(new String[0]));
        }
        return true;
    }

    private void cancelPendingCotRfRetry(String cotUid) {
        if (cotUid == null || cotUid.trim().isEmpty()) {
            return;
        }
        PendingOutboundCot removed = pendingOutboundCots.remove(cotUid.trim());
        if (removed != null) {
            Log.d(TAG, "Cancelled pending CoT RF retry uid=" + cotUid.trim());
        }
    }

    private boolean hasAnyRfReachableMapRecipient(String[] toUIDs) {
        if (toUIDs == null || toUIDs.length == 0 || !isRadioConnected()) {
            return false;
        }
        String self = null;
        try {
            self = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        for (String uid : toUIDs) {
            if (uid == null || uid.trim().isEmpty()) {
                continue;
            }
            String trimmed = uid.trim();
            if (ALL_CHAT_ROOMS.equalsIgnoreCase(trimmed)) {
                continue;
            }
            if (self != null && self.equalsIgnoreCase(trimmed)) {
                continue;
            }
            try {
                Contact c = Contacts.getInstance().getContactByUuid(trimmed);
                if (c instanceof IndividualContact
                        && hasRfMapRecipientPath((IndividualContact) c)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            if (isBtechContactUid(trimmed) && !isOpaqueDeviceUid(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRfMapRecipientPath(IndividualContact contact) {
        if (contact == null || !isRadioConnected() || isWifiOnlyMapRecipient(contact)) {
            return false;
        }
        if (hasMeshRadioConnector(contact)) {
            return true;
        }
        String uid = contact.getUID();
        return uid != null && isBtechContactUid(uid.trim());
    }

    private boolean isWifiOnlyMapRecipient(IndividualContact contact) {
        if (contact == null) {
            return false;
        }
        String uid = contact.getUID();
        if (uid != null && isOpaqueDeviceUid(uid)) {
            return true;
        }
        if (hasMeshRadioConnector(contact)) {
            return false;
        }
        return resolveRoutableNetworkEndpoint(contact) != null;
    }

    private static boolean hasMeshRadioConnector(IndividualContact contact) {
        if (contact == null) {
            return false;
        }
        com.atakmap.android.contact.Connector conn =
                contact.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE);
        return conn != null
                && ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(conn.getConnectionString());
    }

    private static NetConnectString resolveRoutableNetworkEndpoint(IndividualContact contact) {
        if (contact == null) {
            return null;
        }
        NetConnectString ncs = parseRoutableEndpoint(contact.getConnector(IpConnector.CONNECTOR_TYPE));
        if (ncs != null) {
            return ncs;
        }
        return parseRoutableEndpoint(contact.getConnector(GeoChatConnector.CONNECTOR_TYPE));
    }

    private static NetConnectString parseRoutableEndpoint(com.atakmap.android.contact.Connector connector) {
        if (connector == null) {
            return null;
        }
        String cs = connector.getConnectionString();
        if (cs == null || cs.trim().isEmpty()) {
            return null;
        }
        try {
            NetConnectString ncs = NetConnectString.fromString(cs.trim());
            if (ncs == null) {
                return null;
            }
            String proto = ncs.getProto();
            if (proto == null) {
                return null;
            }
            String lower = proto.trim().toLowerCase(Locale.US);
            if ("ssl".equals(lower) || "stcp".equals(lower) || "tcp".equals(lower)) {
                return ncs;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final String ANDROID_UID_PREFIX = "ANDROID-";
    private static final long GROUP_CONTACT_COT_REDUNDANT_TX_DELAY_MS = 1200L;
    private static final Pattern AUTO_POINT_CALLSIGN =
            Pattern.compile("^[A-Z]\\.\\d{2}\\.\\d{6,}$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPAQUE_DEVICE_ID_HEX16 =
            Pattern.compile("^[0-9A-F]{16}$", Pattern.CASE_INSENSITIVE);

    /**
     * ATAK GeoChat/direct destinations sometimes use the literal contact UID label
     * (e.g. ANDROID-VETTE1); registration keys are typically the bare callsign
     * (VETTE1). Normalizes for routing-map lookup.
     */
    private static String normalizeBtechRoutingId(String id) {
        if (id == null) return "";
        String key = id.trim().toUpperCase();
        if (key.startsWith(ANDROID_UID_PREFIX)) {
            key = key.substring(ANDROID_UID_PREFIX.length());
        }
        return key;
    }

    private static boolean isOpaqueDeviceId(String value) {
        String normalized = normalizeBtechRoutingId(value);
        if (normalized.isEmpty()) {
            return false;
        }
        return OPAQUE_DEVICE_ID_HEX16.matcher(normalized).matches();
    }

    /** True for opaque Wi-Fi device ids ({@code ANDROID-b726a98286ca1d08} suffix). */
    public static boolean isOpaqueDeviceUid(String value) {
        return isOpaqueDeviceId(value);
    }

    private static String buildCallsignUidSuffix(String callsign) {
        if (callsign == null) {
            return "";
        }
        String upper = callsign.trim().toUpperCase(Locale.US);
        if (upper.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char ch = upper.charAt(i);
            if ((ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '-') {
                out.append(ch);
            } else if (ch == ' ' || ch == '.' || ch == '/') {
                out.append('_');
            }
        }
        String normalized = out.toString().replaceAll("_+", "_");
        if (normalized.startsWith("_")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("_")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isPseudoPointContactIdentifier(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return AUTO_POINT_CALLSIGN.matcher(trimmed).matches() || isOpaqueDeviceId(trimmed);
    }

    /**
     * Mesh chat sender IDs are often compressed forms (for example: SMKY15).
     * Build a consonant/digit key to map compact IDs to known full callsigns.
     */
    private static String compactRoutingKey(String id) {
        String normalized = normalizeBtechRoutingId(id);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                if (ch == 'A' || ch == 'E' || ch == 'I' || ch == 'O' || ch == 'U') {
                    continue;
                }
                out.append(ch);
            } else if (ch >= '0' && ch <= '9') {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /**
     * Resolve a chat destination label/callsign to a BTECH contact UID, if known.
     */
    public String resolveBtechUidForId(String id) {
        if (id == null) return null;
        String key = id.trim().toUpperCase();
        if (key.isEmpty()) return null;
        if (isOpaqueDeviceId(key)) {
            return null;
        }
        if (isBtechContactUid(key)) {
            return key;
        }
        String mapped = btechIdToUid.get(key);
        if (mapped != null) return mapped;
        String stripped = normalizeBtechRoutingId(id);
        if (!stripped.isEmpty() && !stripped.equals(key)) {
            mapped = btechIdToUid.get(stripped);
            if (mapped != null) return mapped;
        }
        String compact = compactRoutingKey(id);
        if (!compact.isEmpty() && compact.length() >= 4) {
            for (Map.Entry<String, String> e : btechIdToUid.entrySet()) {
                if (e == null || e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String existingCompact = compactRoutingKey(e.getKey());
                if (compact.equals(existingCompact)) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    /**
     * True if an outbound GeoChat/send intent targets a plugin-registered radio
     * contact, using UID, chat-room label, or ATAK ANDROID-* display identifiers.
     */
    public boolean isBtechOutboundChatDestination(String uid, String chatroom) {
        if (uid != null) {
            String u = uid.trim();
            if (!u.isEmpty() && isBtechContactUid(u)) return true;
            String resolvedUid = resolveBtechUidForId(u);
            if (resolvedUid != null && isBtechContactUid(resolvedUid)) return true;
        }
        if (chatroom != null && !chatroom.isEmpty()) {
            if ("ALL CHAT ROOMS".equalsIgnoreCase(chatroom.trim())) return false;
            String resolvedRm = resolveBtechUidForId(chatroom);
            if (resolvedRm != null && isBtechContactUid(resolvedRm)) return true;
        }
        return false;
    }

    /**
     * Decide whether an ATAK outbound event (from broadcast/intent) should be
     * relayed to the radio based on its destination UIDs.
     *
     * Many ATAK broadcasts include a `toUIDs` extra. If that list intersects
     * with our plugin-created contact UIDs, we treat it as a radio-targeted send.
     */
    public boolean shouldRelayToRadio(Intent intent) {
        if (intent == null) return false;
        try {
            java.util.ArrayList<String> toUIDs =
                    intent.getStringArrayListExtra("toUIDs");
            if (toUIDs == null || toUIDs.isEmpty()) return false;
            for (String uid : toUIDs) {
                if (isBtechOutboundChatDestination(uid, null)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Decide whether an outbound GeoChat CoT event should be relayed to radio.
     *
     * ATAK GeoChat CoT varies by release: {@code __chat} vs {@code chat}, and
     * destination may appear under {@code chatgrp}, {@code chatroom}, or only in
     * the serialized XML. We combine structured parsing with a containment check
     * against registered radio-contact UIDs.
     */
    public boolean shouldRelayGeoChatToRadio(CotEvent event) {
        if (event == null) return false;
        try {
            if (!"b-t-f".equals(event.getType())) return false;
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) return false;

            com.atakmap.coremap.cot.event.CotDetail chat =
                    detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            if (chat != null && geoChatDetailTargetsBtechContact(chat, detail)) {
                return true;
            }

            // Some ATAK layouts omit renameable wrappers; probe full serialization.
            if (geoChatXmlReferencesRegisteredBtechContact(event)) {
                Log.d(TAG, "GeoChat relay: matched BTECH UID via CoT substring probe");
                return true;
            }

            // Final fallback: check if any UID referenced in the CoT belongs to a contact
            // carrying MeshSendMessageConnector. Covers the case where the contact was created
            // this session but has not yet sent a GPS beacon to register in btechContactUids.
            if (chat != null && geoChatDetailTargetsMeshConnector(chat, detail)) {
                Log.d(TAG, "GeoChat relay: matched MeshSendMessageConnector contact");
                return true;
            }

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Check if any UID in the chat CoT detail belongs to a contact with MeshSendMessageConnector. */
    private boolean geoChatDetailTargetsMeshConnector(
            com.atakmap.coremap.cot.event.CotDetail chat,
            com.atakmap.coremap.cot.event.CotDetail detail) {
        try {
            com.atakmap.coremap.cot.event.CotDetail chatgrp =
                    chat.getFirstChildByName(0, "chatgrp");
            if (chatgrp != null) {
                for (String attr : new String[]{"uid0", "uid1", "uid2"}) {
                    if (hasMeshConnector(chatgrp.getAttribute(attr))) return true;
                }
            }
            for (String attr : new String[]{"chatroom", "id", "destination", "recipient"}) {
                String val = chat.getAttribute(attr);
                if (hasMeshConnector(val)) return true;
                String resolved = resolveBtechUidForId(val);
                if (hasMeshConnector(resolved)) return true;
            }
            com.atakmap.coremap.cot.event.CotDetail remarks =
                    detail.getFirstChildByName(0, "remarks");
            if (remarks != null) {
                String to = remarks.getAttribute("to");
                if (hasMeshConnector(to)) return true;
                if (hasMeshConnector(resolveBtechUidForId(to))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean hasMeshConnector(String uid) {
        if (uid == null || uid.trim().isEmpty()) return false;
        try {
            Contact c = Contacts.getInstance().getContactByUuid(uid.trim());
            if (c == null) c = Contacts.getInstance().getContactByUuid(uid.trim().toUpperCase(Locale.US));
            if (c instanceof IndividualContact) {
                com.atakmap.android.contact.Connector conn = ((IndividualContact) c)
                        .getConnector(com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector.CONNECTOR_TYPE);
                if (conn != null && ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(conn.getConnectionString())) {
                    btechContactUids.add(uid.trim()); // register for future fast-path
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean geoChatDetailTargetsBtechContact(
            com.atakmap.coremap.cot.event.CotDetail chat,
            com.atakmap.coremap.cot.event.CotDetail detail) {

        com.atakmap.coremap.cot.event.CotDetail chatgrp =
                chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp != null) {
            String uid0 = chatgrp.getAttribute("uid0");
            String uid1 = chatgrp.getAttribute("uid1");
            if (isBtechContactUid(uid0) || isBtechContactUid(uid1)) {
                return true;
            }
        }

        for (String attr : new String[] {"chatroom", "id", "destination", "recipient"}) {
            String chatRoom = chat.getAttribute(attr);
            String uidFromRoom = resolveBtechUidForId(chatRoom);
            if (isBtechContactUid(uidFromRoom)) return true;
        }

        com.atakmap.coremap.cot.event.CotDetail remarks =
                detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            String to = remarks.getAttribute("to");
            String uidFromTo = resolveBtechUidForId(to);
            if (isBtechContactUid(uidFromTo)) return true;
        }
        return false;
    }

    /**
     * Last-resort matcher for outbound b-t-f when structured {@code __chat} is absent
     * or uses nonstandard tags (different ATAK revisions).
     */
    private boolean geoChatXmlReferencesRegisteredBtechContact(CotEvent event) {
        try {
            if (btechContactUids.isEmpty()) return false;
            String s = event.toString();
            if (s == null || s.length() > 524288) return false;
            for (String uid : btechContactUids) {
                if (uid != null && uid.length() > 8 && s.indexOf(uid) >= 0) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Backwards-compatible name for chat routing decisions.
     */
    public boolean shouldRelayChatCotToRadio(CotEvent event) {
        return shouldRelayGeoChatToRadio(event);
    }

    /**
     * Inject a position CoT event into ATAK from radio GPS data.
     *
     * @param senderTeamFromPeer ATAK {@code locationTeam} from the transmitting node
     *                           (embedded in GPS packet). If null or empty,
     *                           falls back to {@code "Cyan"} so we do not apply the
     *                           <em>receiver's</em> team tint to peers.
     */
    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, null, null);
    }

    public void injectPositionCot(String callsign, double lat, double lon,
                                  double alt, double speed, double course,
                                  String senderTeamFromPeer, String cotTypeOverride) {
        injectPositionCot(callsign, lat, lon, alt, speed, course,
                senderTeamFromPeer, cotTypeOverride, null);
    }

    private void injectPositionCot(String callsign, double lat, double lon,
                                   double alt, double speed, double course,
                                   String senderTeamFromPeer, String cotTypeOverride,
                                   String remarksInner) {
        try {
            String teamForCot = senderTeamFromPeer != null && !senderTeamFromPeer.trim().isEmpty()
                    ? senderTeamFromPeer.trim()
                    : "Cyan";

            CotEvent event = CotBuilder.buildPositionCot(
                    callsign, lat, lon, alt, speed, course, teamForCot,
                    resolveInboundContactStaleMs(),
                    cotTypeOverride,
                    remarksInner);

            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting position CoT for " + callsign + " team=" + teamForCot);
                try {
                    String uid = event.getUID();
                    if (uid != null && uid.startsWith(ANDROID_UID_PREFIX)) {
                        // Ensure position-only peers are also valid GeoChat/plugin contacts so
                        // radial menu Contact Card + GeoChat actions stay enabled.
                        ChatBridge.ensurePluginChatContactExactUid(callsign, uid);
                        registerBtechAliases(callsign, uid, uid);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to seed chat contact from position for " + callsign, e);
                }
                markInboundInjectSkipOutboundRelay(event.getUID());
                dispatchCotEvent(event);
                maybeRelayInboundRadioCotToTak(event);
                MapView pingMv = MapView.getMapView();
                if (pingMv != null) {
                    PingReplyNotifier.maybeNotifyPingReply(pingMv.getContext(), callsign);
                }
            } else {
                Log.w(TAG, "Invalid position CoT for " + callsign);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting position CoT", e);
        }
    }

    /**
     * Inject a MeshCore node/repeater position CoT at an explicit map UID, carrying the
     * imported {@code meschore} iconset usericon so ATAK renders the iconset bitmap.
     *
     * <p>Mirrors the Darksteal mesh inject path. APRS was removed from MeshcoreAtak, so the
     * symbol table is unused (vestigial for call-site parity) and the symbol code is routed
     * directly to {@link com.atakmaps.meshcore.plugin.ax25.MeshcoreIconset}.
     *
     * @param meshSymbolTable vestigial table selector ({@code 'M'} from callers); ignored
     * @param meshSymbolCode  MeshCore symbol code ({@code '>'} repeater, letter for node)
     * @param mapUidOverride  existing/target map marker UID
     */
    public void injectPositionCotAtMapUid(String callsign, double lat, double lon,
                                          double alt, double speed, double course,
                                          String senderTeamFromPeer,
                                          Character meshSymbolTable,
                                          Character meshSymbolCode,
                                          String remarksInner,
                                          String mapUidOverride) {
        try {
            String teamForCot = senderTeamFromPeer != null && !senderTeamFromPeer.trim().isEmpty()
                    ? senderTeamFromPeer.trim()
                    : "Cyan";
            char symbolCode = meshSymbolCode != null ? meshSymbolCode : 'N';

            CotEvent event = CotBuilder.buildMeshPositionCot(
                    callsign, lat, lon, alt, speed, course, teamForCot,
                    resolveInboundContactStaleMs(),
                    symbolCode,
                    remarksInner,
                    mapUidOverride);

            if (event != null && event.isValid()) {
                Log.d(TAG, "Injecting mesh position CoT for " + callsign
                        + " uid=" + event.getUID() + " team=" + teamForCot
                        + " symbol=" + symbolCode);
                markInboundInjectSkipOutboundRelay(event.getUID());
                dispatchCotEvent(event);
                maybeRelayInboundRadioCotToTak(event);
            } else {
                Log.w(TAG, "Invalid mesh position CoT for " + callsign);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting mesh position CoT", e);
        }
    }

    /**
     * Receiver-side stale policy for inbound radio contacts.
     * ATAK marks contacts stale based on the CoT stale timestamp we dispatch.
     */
    private long resolveInboundContactStaleMs() {
        return MIN_INBOUND_RADIO_STALE_MS;
    }

    /**
     * Inject GeoChat delivered ({@code b-t-f-d}) or read ({@code b-t-f-r}) receipt for a sent line.
     */
    public void injectGeoChatReceipt(String referencedOriginalMessageLineUid,
                                     boolean readNotDelivered) {
        try {
            CotEvent event = CotBuilder.buildGeoChatReceiptCot(
                    referencedOriginalMessageLineUid,
                    readNotDelivered,
                    cachedLocalDeviceUidForGeoChat,
                    localCallsign);
            if (event == null || !event.isValid()) {
                return;
            }
            markInboundInjectSkipOutboundRelay(event.getUID());
            deliverInboundGeoChatToAtak(event);
            broadcastReceiptCotPlaced(event);
            Log.d(TAG, "Injected GeoChat receipt type=" + event.getType()
                    + " cotUID=" + event.getUID()
                    + " for line=" + referencedOriginalMessageLineUid);
        } catch (Exception e) {
            Log.e(TAG, "Error injecting GeoChat receipt", e);
        }
    }

    /**
     * Some ATAK GeoChat paths hook {@code COT_PLACED} rather than the internal dispatcher alone;
     * receipts still skip RF relay via PreSend guards on {@code b-t-f-d}/{@code b-t-f-r}.
     */
    private void broadcastReceiptCotPlaced(CotEvent event) {
        if (event == null) {
            return;
        }
        try {
            Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
            intent.putExtra("xml", event.toString());
            AtakBroadcast.getInstance().sendBroadcast(intent);
            Log.d(TAG, "Broadcast COT_PLACED for GeoChat receipt uid=" + event.getUID());
        } catch (Exception e) {
            Log.w(TAG, "COT_PLACED broadcast for GeoChat receipt failed", e);
        }
    }

    /**
     * Inject a compressed CoT XML received from another MeshCore node.
     */
    public void injectCompressedCot(byte[] compressed) {
        injectCompressedCot(compressed, 0);
    }

    /**
     * Inject a compressed CoT XML received from another MeshCore node, carrying
     * the MeshCore pathLen (number of repeater hops) for display purposes.
     */
    public void injectCompressedCot(byte[] compressed, int pathLen) {
        try {
            String xml = CotBuilder.decompressCot(compressed);
            if (xml == null || xml.isEmpty()) {
                Log.w(TAG, "Failed to decompress CoT");
                return;
            }

            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                Log.w(TAG, "injectCompressedCot: CotEvent.parse returned null/invalid — xml="
                        + (xml.length() > 120 ? xml.substring(0, 120) + "…" : xml));
                return;
            }
            if (event != null && event.isValid()) {
                sanitizeInboundAutoPointCot(event);
                if (!isInboundDirectedRfCotForThisDevice(event)) {
                    Log.d(TAG, "Skipping directed RF CoT not addressed to this device: type="
                            + event.getType() + " uid=" + event.getUID()
                            + " destUids=" + CotBuilder.extractDirectedDestUids(event));
                    return;
                }
                CotBuilder.stripDirectedDestinations(event);
                Log.d(TAG, "Injecting decompressed CoT: type=" + event.getType()
                        + " uid=" + event.getUID());
                // Mark ALL injected CoT to skip outbound RF relay — prevents the
                // PreSendProcessor from echoing received items back over the air.
                markInboundInjectSkipOutboundRelay(event.getUID());
                if (isGeoChatCotType(event.getType())) {
                    deliverInboundGeoChatToAtak(event);
                } else {
                    dispatchCotEvent(event);
                }
                maybeRelayInboundRadioCotToTak(event);
                MapView pingMv = MapView.getMapView();
                if (pingMv != null) {
                    PingReplyNotifier.maybeNotifyPingReplyFromCot(
                            pingMv.getContext(), event);
                }

                String hopStr = pathLen <= 0 ? "direct" : pathLen + (pathLen == 1 ? " hop" : " hops");
                Log.d(TAG, "CoT received: type=" + event.getType() + " via " + hopStr);

                // Send TYPE_COT_ACK back to cancel the sender's retry watchdog.
                String ackUid = event.getUID();
                if (ackUid != null && !ackUid.trim().isEmpty()
                        && btManager != null && btManager.isConnected()) {
                    try {
                        MeshCorePacket ackPkt = MeshCorePacket.createCotAck(ackUid.trim());
                        byte[] ackBytes = ackPkt.encode();
                        if (encryptionManager != null && encryptionManager.isEnabled()) {
                            ackBytes = encryptionManager.encrypt(ackBytes);
                        }
                        if (ackBytes != null) {
                            com.atakmaps.meshcore.plugin.ax25.Ax25Frame ackFrame =
                                    com.atakmaps.meshcore.plugin.ax25.Ax25Frame
                                            .createMeshCoreFrame(localCallsign, 0, ackBytes);
                            btManager.sendKissFrame(ackFrame.encode());
                            Log.d(TAG, "CoT ACK sent uid=" + ackUid.trim());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to send CoT ACK uid=" + ackUid, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting compressed CoT", e);
        }
    }

    /**
     * Convert auto-generated ATAK point names (U.27.xxxxxx / N.27.xxxxxx) into
     * plain map-point presentation so they do not appear as pseudo-contacts.
     */
    private void sanitizeInboundAutoPointCot(CotEvent event) {
        if (event == null) {
            return;
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return;
        }
        CotDetail contact = detail.getFirstChildByName(0, "contact");
        if (contact == null) {
            return;
        }
        String callsign = contact.getAttribute("callsign");
        String trimmed = callsign != null ? callsign.trim() : "";
        String uid = event.getUID();

        // Some relayed SA arrives as ANDROID-<16hex>; remap to callsign UID so ATAK does not
        // create opaque pseudo-contacts.
        if (uid != null && uid.startsWith(ANDROID_UID_PREFIX) && isOpaqueDeviceId(uid)) {
            String suffix = buildCallsignUidSuffix(trimmed);
            if (!suffix.isEmpty()
                    && !AUTO_POINT_CALLSIGN.matcher(trimmed).matches()
                    && !isOpaqueDeviceId(suffix)) {
                String remappedUid = ANDROID_UID_PREFIX + suffix;
                if (!remappedUid.equalsIgnoreCase(uid)) {
                    String oldUid = uid;
                    event.setUID(remappedUid);
                    registerBtechAliases(trimmed, remappedUid, oldUid, trimmed);
                    uid = remappedUid;
                    Log.d(TAG, "Remapped opaque SA UID " + oldUid + " -> " + remappedUid
                            + " callsign=" + trimmed);
                }
            }
        }

        boolean callsignLooksAuto = isPseudoPointContactIdentifier(trimmed);
        boolean uidLooksAuto = isPseudoPointContactIdentifier(uid);
        if (!callsignLooksAuto && !uidLooksAuto) {
            return;
        }
        contact.setAttribute("callsign", "Point");
        event.setType("b-m-p-s-p-i");
        Log.d(TAG, "Sanitized inbound auto-point CoT uid=" + event.getUID()
                + " callsign=" + trimmed
                + " callsignAuto=" + callsignLooksAuto
                + " uidAuto=" + uidLooksAuto
                + " -> Point");
    }

    /**
     * Inject a chat CoT event into ATAK.
     */
    /**
     * @param radioPacketMessageId MeshCore wire id ({@literal >} 0 distinguishes duplicate ATAK merges); 0 = unknown
     */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom, int radioPacketMessageId) {
        try {
            String trimmed = senderCallsign != null ? senderCallsign.trim() : "";
            // Align with GPS-registered contacts: AX.25 truncates sender (e.g. JUNIOR → JNR).
            String canonicalUid = resolveBtechUidForId(trimmed);
            if (canonicalUid == null && !trimmed.isEmpty()) {
                if (isOpaqueDeviceId(trimmed)) {
                    Log.w(TAG, "injectChatCot: dropping sender opaque device id: " + trimmed);
                    return;
                }
                String key = normalizeBtechRoutingId(trimmed);
                if (!key.isEmpty()) {
                    canonicalUid = ANDROID_UID_PREFIX + key;
                }
            }
            if (canonicalUid == null || canonicalUid.isEmpty()) {
                Log.w(TAG, "injectChatCot: no UID for sender " + trimmed);
                return;
            }
            String displayCallsign = canonicalUid.startsWith(ANDROID_UID_PREFIX)
                    ? canonicalUid.substring(ANDROID_UID_PREFIX.length())
                    : trimmed.toUpperCase();
            // GeoChat dedupes/threads by messageId (Cot UID). If we use only wire mid (1,2,3...),
            // restarting ATAK (or receiver) with existing chat history causes collisions and the
            // UI "updates" an old row instead of inserting the new message. Make the UID globally
            // unique while still embedding the wire mid for debugging.
            long uniq;
            if (radioPacketMessageId != 0) {
                long mid = ((long) radioPacketMessageId) & 0xffffffffL;
                long t = System.currentTimeMillis() & 0xffffffffL;
                uniq = (mid << 32) | t;
            } else {
                uniq = System.nanoTime();
            }
            // Peer-to-peer DM: GeoChat expects chatgrp uid0=peer, uid1=local self. Radio RX runs on
            // BT thread; MapView.getDeviceUid() is often NULL there — omitting uid1 caused
            // GeoChat.ANDROID-VETTE.ANDROID-VETTE and broken UI / ACK path.
            // The same applies for native MeshCore peer UIDs (MESHCORE-NODE- / MESHCORE-RPTR-).
            String chatGrpUid1ForDm = null;
            if (isPeerToPeerChatRoom(chatRoom)) {
                chatGrpUid1ForDm = cachedLocalDeviceUidForGeoChat;
                if (chatGrpUid1ForDm == null) {
                    chatGrpUid1ForDm = resolveLocalAtakUidForChatGrp(canonicalUid, chatRoom);
                }
            }

            CotEvent event = CotBuilder.buildChatCot(
                    canonicalUid, displayCallsign, message, chatRoom, uniq,
                    chatGrpUid1ForDm);

            if (event != null && event.isValid()) {
                if (chatGrpUid1ForDm != null && chatRoom != null) {
                    Log.d(TAG, "GeoChat DM __chat id(local)=" + chatGrpUid1ForDm
                            + " chatroom(callsign)=" + displayCallsign
                            + " peerTHREAD=" + chatRoom + " sender=" + canonicalUid);
                }
                Log.d(TAG, "Injecting chat CoT from " + displayCallsign
                        + " (uid=" + canonicalUid + " midpkt=" + radioPacketMessageId + ")");
                markInboundInjectSkipOutboundRelay(event.getUID());
                deliverInboundGeoChatToAtak(event);
                maybeRelayInboundRadioCotToTak(event);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error injecting chat CoT", e);
        }
    }

    /**
     * Variant that accepts a pre-resolved sender UID (e.g. for native MeshCore DMs whose pubkey
     * prefix has already been mapped to an ATAK contact). When {@code senderUidOverride} is
     * non-null and non-empty it bypasses the callsign-based UID resolution.
     */
    public void injectChatCot(String senderCallsign, String message,
                              String chatRoom, int radioPacketMessageId,
                              String geoChatLineUid, String senderUidOverride) {
        if (senderUidOverride != null && !senderUidOverride.trim().isEmpty()) {
            String uid = senderUidOverride.trim();
            String displayCallsign = uid.startsWith(ANDROID_UID_PREFIX)
                    ? uid.substring(ANDROID_UID_PREFIX.length())
                    : (senderCallsign != null && !senderCallsign.trim().isEmpty()
                            ? senderCallsign.trim()
                            : uid);
            registerBtechContactUid(uid);
            try {
                long uniq;
                if (radioPacketMessageId != 0) {
                    long mid = ((long) radioPacketMessageId) & 0xffffffffL;
                    long t = System.currentTimeMillis() & 0xffffffffL;
                    uniq = (mid << 32) | t;
                } else {
                    uniq = System.nanoTime();
                }
                String chatGrpUid1ForDm = null;
                if (isPeerToPeerChatRoom(chatRoom)) {
                    chatGrpUid1ForDm = cachedLocalDeviceUidForGeoChat;
                    if (chatGrpUid1ForDm == null) {
                        chatGrpUid1ForDm = resolveLocalAtakUidForChatGrp(uid, chatRoom);
                    }
                }
                CotEvent event = CotBuilder.buildChatCot(
                        uid, displayCallsign, message, chatRoom, uniq, chatGrpUid1ForDm);
                if (event != null && event.isValid()) {
                    Log.d(TAG, "Injecting chat CoT (uid override) from " + displayCallsign
                            + " uid=" + uid + " midpkt=" + radioPacketMessageId);
                    markInboundInjectSkipOutboundRelay(event.getUID());
                    deliverInboundGeoChatToAtak(event);
                    maybeRelayInboundRadioCotToTak(event);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error injecting chat CoT (uid override)", e);
            }
        } else {
            injectChatCot(senderCallsign, message, chatRoom, radioPacketMessageId);
        }
    }

    /**
     * Register a contact as a known mesh/WiFi peer so outbound GeoChat routing resolves
     * correctly after a contact de-duplication merge.
     */
    public void registerMergedContact(
            com.atakmap.android.contact.IndividualContact contact) {
        if (contact == null) return;
        String uid = contact.getUID();
        String name = contact.getName();
        if (uid != null && !uid.trim().isEmpty()) {
            registerBtechContactUid(uid.trim());
            if (name != null && !name.trim().isEmpty()) {
                registerBtechAliases(name.trim(), uid.trim(), null, null);
            }
        }
        com.atakmaps.meshcore.plugin.MeshCoreContactHandler.applyEstablishedContactConnectors(contact);
    }

    /**
     * Remove a stray map-marker contact that was created for a mesh/WiFi callsign when a
     * canonical ATAK contact now exists for the same peer.
     *
     * @param callsignRaw the callsign whose orphan synthetic marker should be removed
     * @param keepUid     the UID to retain (never removed)
     */
    public void removeOrphanRfMapMarkerForCallsign(String callsignRaw, String keepUid) {
        if (callsignRaw == null || callsignRaw.trim().isEmpty()) return;
        String upper = callsignRaw.trim().toUpperCase(java.util.Locale.US);
        com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
        if (mv == null) return;
        com.atakmap.android.maps.MapGroup rootGroup = mv.getRootGroup();
        if (rootGroup == null) return;
        rootGroup.deepForEachItem(item -> {
            if (item == null) return true;
            String uid = item.getUID();
            if (uid == null || (keepUid != null && uid.equalsIgnoreCase(keepUid))) return true;
            String meta = item.getMetaString("callsign", null);
            if (meta == null) meta = item.getMetaString("title", null);
            if (meta == null) return true;
            if (!meta.trim().toUpperCase(java.util.Locale.US).equals(upper)) return true;
            // Only remove synthetic radio-plugin-created markers (not full SA contacts).
            String type = item.getMetaString("type", "");
            if (type.startsWith("a-f") || type.startsWith("a-h") || type.startsWith("a-n")) {
                return true;
            }
            Log.d(TAG, "Removing orphan radio map marker uid=" + uid
                    + " callsign=" + meta + " (canonical=" + keepUid + ")");
            item.removeFromGroup();
            return true;
        });
    }

    private static boolean isGeoChatCotType(String type) {
        return type != null && type.startsWith("b-t-f");
    }

    /**
     * Returns true when {@code chatRoom} represents a direct peer-to-peer DM thread.
     * Both ANDROID-* (ATAK device) and MESHCORE-NODE-* / MESHCORE-RPTR-* (native MeshCore node)
     * UIDs require a {@code uid1=<localDeviceUid>} entry in the GeoChat chatgrp element so ATAK
     * threads and notifies the conversation correctly.
     */
    private static boolean isPeerToPeerChatRoom(String chatRoom) {
        if (chatRoom == null) return false;
        return chatRoom.startsWith(ANDROID_UID_PREFIX)
                || chatRoom.startsWith("MESHCORE-NODE-")
                || chatRoom.startsWith("MESHCORE-RPTR-");
    }

    /**
     * Wi‑Fi/TAK delivers GeoChat through {@link GeoChatService}; internal {@link CotMapComponent}
     * dispatch alone creates stray contacts and skips {@code hierarchy} group parsing.
     */
    private void deliverInboundGeoChatToAtak(CotEvent event) {
        if (event == null) {
            return;
        }
        boolean hasHierarchy = GeoChatContactListHelper.cotHasContactHierarchy(event);
        boolean contactListUpdate = GeoChatContactListHelper.isContactListUpdateMessage(
                extractGeoChatRemarks(event));
        boolean groupSync = hasHierarchy || contactListUpdate;
        try {
            // GeoChatService treats unknown senders as stray IP contacts and can rewrite
            // conversationId to senderUid — seed the RF peer first.
            seedInboundGeoChatSender(event, groupSync);
            if (groupSync) {
                registerBtechPeersFromGroupHierarchy(event);
            }
            GeoChatService.getInstance().onCotEvent(event, new Bundle());
            Log.i(TAG, "Inbound GeoChat via GeoChatService: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " convo=" + GeoChatContactListHelper.extractConversationId(event)
                    + " sender=" + GeoChatContactListHelper.extractChatSenderUid(event)
                    + " hasHierarchy=" + GeoChatContactListHelper.cotHasContactHierarchy(event));
            String inboundSenderUid = GeoChatContactListHelper.extractChatSenderUid(event);
            // GeoChatService may reset the sender's default connector to its own routing choice.
            // Re-apply MeshSendMessageConnector as default so the radio icon is preserved.
            ChatBridge.repairAtakPeerConnectorDefault(inboundSenderUid);
            // For MESHCORE-* contacts our MeshSendMessageConnector is the sole badge source.
            // GeoChatService.onCotEvent() posts an async increment of ATAK's native
            // GeoChatConnector unread to the main thread ("Geo Chat: 1 + Send Message: 1").
            // Schedule markmessageread on the main thread with a short delay so it fires
            // AFTER that increment, clearing the native count before the user sees it.
            // ANDROID-* contacts intentionally skip this — they rely on native GeoChatConnector only.
            if (inboundSenderUid != null
                    && (inboundSenderUid.startsWith("MESHCORE-NODE-")
                        || inboundSenderUid.startsWith("MESHCORE-RPTR-"))) {
                final CotEvent eventForClear = event;
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> clearNativeGeoChatUnread(eventForClear), 300);
            }
            if (groupSync) {
                InboundGroupSyncApplier.applyAfterInboundGroupCot(event, contactListUpdate);
            }
            if (!contactListUpdate) {
                notifyInboundRfChat(event);
            }
        } catch (Exception e) {
            Log.w(TAG, "GeoChatService.onCotEvent failed, fallback dispatch", e);
            dispatchCotEvent(event);
            if (!contactListUpdate) {
                notifyInboundRfChat(event);
            }
        }
    }

    /**
     * Broadcast {@code markmessageread} for the conversation carried in a GeoChat CoT so
     * ATAK's native unread counter resets to zero immediately after delivery.
     * Our plugin's own {@code incrementUnreadOnce} counter is the sole notification source.
     */
    private void clearNativeGeoChatUnread(CotEvent event) {
        try {
            // Mark the chatgrp conversation (local device UID as chatgrp id for DMs).
            String convo = GeoChatContactListHelper.extractConversationId(event);
            if (convo != null && !convo.trim().isEmpty()) {
                android.content.Intent markRead = new android.content.Intent(
                        "com.atakmap.chat.markmessageread");
                markRead.putExtra("conversationId", convo.trim());
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(markRead);
            }
            // Also mark by sender UID — GeoChatConnector tracks unread keyed on the contact UID,
            // which for mesh DMs is the sender (MESHCORE-NODE-* or ANDROID-*), not the chatgrp id.
            String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
            if (senderUid != null && !senderUid.trim().isEmpty()
                    && !senderUid.trim().equals(convo != null ? convo.trim() : "")) {
                android.content.Intent markRead2 = new android.content.Intent(
                        "com.atakmap.chat.markmessageread");
                markRead2.putExtra("conversationId", senderUid.trim());
                com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(markRead2);
            }
        } catch (Exception ignored) {
        }
    }

    private static String extractGeoChatRemarks(CotEvent event) {
        if (event == null || event.getDetail() == null) {
            return null;
        }
        CotDetail remarks = event.getDetail().getFirstChildByName(0, "remarks");
        return remarks != null ? remarks.getInnerText() : null;
    }

    private void notifyInboundRfChat(CotEvent event) {
        if (event == null) {
            return;
        }
        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long until = inboundChatNotifyUntil.get(uid);
        if (until != null && now < until) {
            return;
        }
        inboundChatNotifyUntil.put(uid, now + INBOUND_CHAT_NOTIFY_DEDUPE_MS);

        String sender = GeoChatContactListHelper.extractChatSenderCallsign(event);
        if (sender == null || sender.trim().isEmpty()) {
            sender = GeoChatContactListHelper.extractChatSenderUid(event);
        }
        if (sender == null || sender.trim().isEmpty()) {
            sender = "RF";
        }
        String message = extractGeoChatRemarks(event);
        if (message == null || message.trim().isEmpty()) {
            message = "New RF chat message";
        } else if (message.length() > 72) {
            message = message.substring(0, 72) + "...";
        }
        String alert = sender + ": " + message;
        String conversationId = GeoChatContactListHelper.extractConversationId(event);
        String conversationName = GeoChatContactListHelper.extractConversationName(event);
        String notifyTitle = "RF Chat";
        if (conversationId != null && !conversationId.trim().isEmpty()
                && !conversationId.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            notifyTitle = "RF Group";
            if (conversationName != null && !conversationName.trim().isEmpty()) {
                alert = conversationName.trim() + ": " + message;
            }
        }
        if (conversationId != null && !conversationId.trim().isEmpty()) {
            int msgId = uid.hashCode() & 0x7FFFFFFF;
            MeshCoreContactHandler.incrementUnreadOnce(conversationId.trim(), msgId, message);
        }
        // Keep ATAK contact unread state only; suppress OS-level popups/notifications.
        Log.i(TAG, "Inbound RF chat recorded for ATAK unread only uid=" + uid
                + " title=" + notifyTitle + " alert=" + alert);
    }

    /**
     * Ensure the sender exists before {@link GeoChatService#onCotEvent} so ATAK does not
     * synthesize a TCP/UDP {@link IndividualContact} in the root Contacts list.
     * Group-sync uses a plain ATAK contact (no {@link PluginConnector}) so the sender does
     * not appear as a stray UV‑PRO peer above the imported group tree.
     */
    private void seedInboundGeoChatSender(CotEvent event, boolean groupSync) {
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        String callsign = GeoChatContactListHelper.extractChatSenderCallsign(event);
        String linkUid = null;
        CotDetail detail = event != null ? event.getDetail() : null;
        CotDetail link = detail != null ? detail.getFirstChildByName(0, "link") : null;
        if (link != null) {
            linkUid = link.getAttribute("uid");
        }
        if (senderUid == null || senderUid.isEmpty()) {
            senderUid = linkUid;
        }
        if (callsign == null || callsign.isEmpty()) {
            if (senderUid != null && senderUid.startsWith(ANDROID_UID_PREFIX)) {
                callsign = senderUid.substring(ANDROID_UID_PREFIX.length());
            } else if (senderUid != null) {
                callsign = senderUid;
            }
        }

        // Never seed/synthesize a contact for our own device UID. Inbound delivered/read receipts
        // (b-t-f-d / b-t-f-r) carry the local device UID as the sender; seeding it would create a
        // stray self-contact with a hex UID name in the Contacts list.
        String localDeviceUid = cachedLocalDeviceUidForGeoChat;
        if (localDeviceUid == null) {
            try {
                localDeviceUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
        }
        if (localDeviceUid != null && !localDeviceUid.trim().isEmpty()) {
            String lu = localDeviceUid.trim();
            if ((senderUid != null && senderUid.trim().equalsIgnoreCase(lu))
                    || (linkUid != null && linkUid.trim().equalsIgnoreCase(lu))) {
                return;
            }
        }

        String canonical = ChatBridge.resolveCanonicalPeerUid(callsign, senderUid, linkUid);
        if (canonical == null || canonical.isEmpty()) {
            return;
        }
        if (localDeviceUid != null && canonical.trim().equalsIgnoreCase(localDeviceUid.trim())) {
            return;
        }

        if (groupSync) {
            ensureAtakGeoChatSenderContact(canonical, callsign);
        } else if (canonical.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            ChatBridge.ensurePluginChatContact(callsign, canonical);
        } else {
            ensureAtakGeoChatSenderContact(canonical, callsign);
        }
        registerBtechAliases(callsign, canonical, senderUid, linkUid);
    }

    /** ATAK {@code GeoChatService.sendMessage} errors if peer UID is not in Contacts yet. */
    private void ensureOutboundGeoChatDestinationsKnown(String[] toUIDs) {
        if (toUIDs == null) {
            return;
        }
        String localUid = null;
        try {
            localUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        for (String raw : toUIDs) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String uid = raw.trim().toUpperCase(Locale.US);
            if (!uid.startsWith(ANDROID_UID_PREFIX)) {
                continue;
            }
            if (localUid != null && uid.equalsIgnoreCase(localUid)) {
                // Never synthesize a chat contact for our own ATAK device UID.
                continue;
            }
            if (!isBtechContactUid(uid) && resolveBtechUidForId(uid) == null) {
                // Ignore random ATAK/system UIDs that are not known radio peers.
                continue;
            }
            String callsign = uid.substring(ANDROID_UID_PREFIX.length());
            ChatBridge.ensurePluginChatContactExactUid(callsign, uid);
            // Re-stamp the radio icon pref in case ATAK's send pipeline reset the default.
            ChatBridge.repairAtakPeerConnectorDefault(uid);
        }
    }

    private void registerBtechAliases(String callsign, String canonicalUid, String... aliases) {
        if (canonicalUid == null || canonicalUid.isEmpty()) {
            return;
        }
        registerBtechContactUid(canonicalUid);
        if (callsign != null && !callsign.isEmpty()) {
            registerBtechContactId(callsign, canonicalUid);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        seen.add(canonicalUid.toUpperCase(Locale.US));
        if (aliases != null) {
            for (String raw : aliases) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String uid = raw.trim();
                String upper = uid.toUpperCase(Locale.US);
                if (!seen.add(upper)) {
                    continue;
                }
                registerBtechContactUid(upper);
                if (callsign != null && !callsign.isEmpty()) {
                    registerBtechContactId(callsign, upper);
                }
                if (!upper.startsWith(ANDROID_UID_PREFIX) && upper.matches("[0-9A-F]+")) {
                    String android = ANDROID_UID_PREFIX + upper;
                    if (seen.add(android)) {
                        registerBtechContactUid(android);
                        if (callsign != null && !callsign.isEmpty()) {
                            registerBtechContactId(callsign, android);
                        }
                    }
                }
            }
        }
    }

    private static void ensureAtakGeoChatSenderContact(String senderUid, String callsign) {
        if (senderUid == null || senderUid.isEmpty()) {
            return;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            String uid = senderUid.trim();
            Contact existing = contacts.getContactByUuid(uid);
            if (existing == null) {
                existing = contacts.getContactByUuid(uid.toUpperCase(Locale.US));
            }
            if (existing != null) {
                return;
            }
            String name = callsign != null && !callsign.isEmpty() ? callsign.trim() : uid;
            contacts.addContact(new IndividualContact(name, uid));
        } catch (Exception e) {
            Log.w(TAG, "ensureAtakGeoChatSenderContact failed uid=" + senderUid, e);
        }
    }

    /** RF route map only — do not add every group member to the root Contacts pane. */
    private void registerBtechPeersFromGroupHierarchy(CotEvent event) {
        if (event == null || chatBridge == null) {
            return;
        }
        String senderUid = GeoChatContactListHelper.extractChatSenderUid(event);
        for (String uid : GeoChatContactListHelper.collectContactUidsFromGroupCot(event)) {
            if (uid == null || !uid.startsWith(ANDROID_UID_PREFIX)) {
                continue;
            }
            // Skip sender + local device — only RF-route members already in the group tree.
            if (senderUid != null && uid.equalsIgnoreCase(senderUid)) {
                continue;
            }
            String localUid = MapView.getDeviceUid();
            if (localUid != null && uid.equalsIgnoreCase(localUid)) {
                continue;
            }
            String callsign = uid.substring(ANDROID_UID_PREFIX.length());
            registerBtechContactUid(uid);
            registerBtechContactId(callsign, uid);
        }
    }

    /**
     * Send a CoT event out over the radio link.
     * The CoT XML is gzipped and sent as an MeshCore packet.
     */
    /** Extract a human-readable label from a CoT event: contact callsign if present, else type. */
    private String extractCotCallsign(com.atakmap.coremap.cot.event.CotEvent event) {
        if (event == null) {
            return "item";
        }
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail != null) {
                for (com.atakmap.coremap.cot.event.CotDetail child : detail.getChildren()) {
                    if (child != null && "contact".equals(child.getElementName())) {
                        String cs = child.getAttribute("callsign");
                        if (cs != null && !cs.trim().isEmpty()) {
                            return cs.trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return event.getType() != null ? event.getType() : "item";
    }

    /** Max compressed CoT size to send over RF. Larger items flood the channel. */
    private static final int MAX_COT_COMPRESSED_BYTES = 4096;

    public void sendCotOverRadio(CotEvent event) {
        sendCotOverRadioInternal(event, true);
    }

    private void sendCotOverRadioNoRetry(CotEvent event) {
        sendCotOverRadioInternal(event, false);
    }

    private void sendCotOverRadioInternal(CotEvent event, boolean registerRetry) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected to radio — cannot send CoT");
            return;
        }

        com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator.get().markOpenRlTxStart();
        try {
            String xml = event.toString();
            byte[] full = CotBuilder.compressCot(xml);
            if (full == null) {
                Log.e(TAG, "Failed to compress CoT for radio");
                return;
            }

            // Try minified CoT (heavy detail children stripped). Only use it if it
            // is strictly smaller — never-worse fallback to today's behavior.
            String minXml = CotBuilder.minifyCotXml(event);
            Log.d(TAG, "CoT minified XML:\n" + (minXml != null ? minXml : "(null — using full)"));
            byte[] min = (minXml != null) ? CotBuilder.compressCot(minXml) : null;
            byte[] compressed = (min != null && min.length < full.length) ? min : full;

            Log.d(TAG, "CoT size: full=" + full.length
                    + " min=" + (min == null ? -1 : min.length)
                    + " used=" + compressed.length
                    + " type=" + event.getType());

            if (compressed.length > MAX_COT_COMPRESSED_BYTES) {
                Log.w(TAG, "CoT too large for RF (" + compressed.length + " bytes compressed"
                        + ", type=" + event.getType() + " uid=" + event.getUID()
                        + ") — skipping to avoid blocking channel");
                return;
            }

            Log.d(TAG, "Sending CoT over radio: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " xmlBytes=" + xml.length()
                    + " compressedBytes=" + compressed.length);

            // Fragment if needed
            List<MeshCorePacket> packets = PacketFragmenter.fragment(
                    MeshCorePacket.TYPE_COT, compressed);

            String txFragStr = packets.size() + (packets.size() == 1 ? " fragment" : " fragments");
            Log.d(TAG, "CoT sent over mesh: " + compressed.length + " bytes, " + txFragStr);

            for (MeshCorePacket packet : packets) {
                byte[] packetBytes = packet.encode();
                // Encrypt entire packet bytes if enabled
                if (encryptionManager != null && encryptionManager.isEnabled()) {
                    packetBytes = encryptionManager.encrypt(packetBytes);
                    if (packetBytes == null) {
                        Log.e(TAG, "Encryption failed — aborting CoT send");
                        return;
                    }
                }
                Ax25Frame frame = Ax25Frame.createMeshCoreFrame(
                        localCallsign, 0, packetBytes);
                byte[] ax25 = frame.encode();
                btManager.sendKissFrame(ax25);
            }

            // Register ACK watchdog — skip GeoChat CoT (b-t-f*) which uses chat retry.
            if (registerRetry && shouldRegisterCotRfRetry(event)) {
                String cotType = event.getType();
                if (cotType == null || !cotType.startsWith("b-t-f")) {
                    String uid = event.getUID();
                    if (uid != null && !uid.trim().isEmpty()) {
                        String trimUid = uid.trim();
                        PendingOutboundCot pending = new PendingOutboundCot(trimUid, event);
                        pendingOutboundCots.put(trimUid, pending);
                        // Double-send: re-transmit once after a short delay so a brief RF
                        // collision on the first attempt doesn't cause a silent drop.
                        cotRetryExecutor.schedule(() -> {
                            if (pendingOutboundCots.containsKey(trimUid)) {
                                Log.d(TAG, "CoT double-send uid=" + trimUid);
                                sendCotOverRadioNoRetry(pending.event);
                            }
                        }, COT_DOUBLE_SEND_DELAY_MS, TimeUnit.MILLISECONDS);
                        scheduleCotRetryCheck(trimUid);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending CoT over radio", e);
        } finally {
            com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator.get().markOpenRlTxEnd();
        }
    }

    private void scheduleCotRetryCheck(String cotUid) {
        if (cotRetryExecutor.isShutdown()) return;
        cotRetryExecutor.schedule(() -> onCotRetryTimer(cotUid),
                COT_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void onCotRetryTimer(String cotUid) {
        try {
            PendingOutboundCot pending = pendingOutboundCots.get(cotUid);
            if (pending == null) return; // ACK already received
            if (pending.retryCount < COT_MAX_RETRIES) {
                pending.retryCount++;
                Log.d(TAG, "CoT retry " + pending.retryCount + "/" + COT_MAX_RETRIES
                        + " uid=" + cotUid);
                sendCotOverRadioNoRetry(pending.event);
                scheduleCotRetryCheck(cotUid);
            } else {
                pendingOutboundCots.remove(cotUid);
                Log.w(TAG, "CoT delivery gave up after " + pending.retryCount
                        + " retries uid=" + cotUid);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in CoT retry timer uid=" + cotUid, e);
        }
    }

    public void handleCotAck(String cotUid) {
        if (cotUid == null || cotUid.trim().isEmpty()) return;
        PendingOutboundCot removed = pendingOutboundCots.remove(cotUid.trim());
        if (removed != null) {
            Log.d(TAG, "CoT ACK received uid=" + cotUid.trim()
                    + " after " + removed.retryCount + " retries");
        }
    }

    /**
     * Team string from ATAK Settings (sender side) for outbound GPS TLV extension.
     */
    private String resolveLocalAtakTeamForOutbound() {
        try {
            String t = com.atakmap.android.chat.ChatManagerMapComponent.getTeamName();
            if (t != null && !t.trim().isEmpty()) {
                return t.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            return com.atakmaps.meshcore.plugin.ui.SettingsFragment.getAtakTeamColor(pluginContext);
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    /**
     * Send local GPS position over radio as compact GPS packet.
     */
    public void sendPositionOverRadio(double lat, double lon, double alt,
                                      float speed, float course, int battery) {
        if (btManager == null || !btManager.isConnected()) return;

        com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator.get().markOpenRlTxStart();
        try {
            MeshCorePacket packet = MeshCorePacket.createGpsPacket(
                    com.atakmaps.meshcore.plugin.util.CallsignUtil.toRadioCallsign(localCallsign),
                    localCallsign, lat, lon, (float) alt,
                    speed, course, battery,
                    resolveLocalAtakTeamForOutbound());

            byte[] packetBytes = packet.encode();

            // Encrypt entire packet bytes if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting GPS send");
                    return;
                }
            }

            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(
                    localCallsign, 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending GPS beacon: " + ax25.length + " bytes");
            btManager.sendKissFrame(ax25);
        } catch (Exception e) {
            Log.e(TAG, "Error sending position over radio", e);
        } finally {
            com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator.get().markOpenRlTxEnd();
        }
    }

    public static boolean isMeshcoreMeshMarker(com.atakmap.android.maps.MapItem item) {
        return item != null
                && (item.getMetaBoolean(META_MESHCORE_MESH_REPEATER, false)
                || item.getMetaBoolean(META_MESHCORE_MESH_NODE, false));
    }

    /**
     * Promote MeshCore advert markers as contact-card capable ATAK contacts.
     * Keeps pinwheel contact card action enabled and updates display metadata.
     * Does NOT auto-create an IndividualContact (map item metadata + routing IDs only).
     */
    public void promoteMeshContactMapItem(String uid, String displayName) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable work = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    if (uid.startsWith("MESHCORE-RPTR-")) {
                        item.setMetaString("menu", "menus/default_item_w_type.xml");
                    } else {
                        com.atakmaps.meshcore.plugin.contacts.ContactRadialMenuUtil
                                .applyPingCapableRadialMenu(item, uid);
                    }
                    item.setMetaBoolean("sendable", true);
                    if (displayName != null && !displayName.trim().isEmpty()) {
                        item.setTitle(displayName.trim());
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "promoteMeshContactMapItem map metadata failed uid=" + uid, e);
            }
            try {
                registerBtechContactUid(uid);
                if (displayName != null && !displayName.trim().isEmpty()) {
                    registerBtechContactId(displayName.trim(), uid);
                }
            } catch (Exception e) {
                Log.w(TAG, "promoteMeshContactMapItem contacts failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            work.run();
        } else {
            this.mapView.post(work);
        }
    }

    /** Store Mesh summary/details on a marker for the one-page Mesh details dropdown. */
    public void setMeshMarkerDetails(String uid, String detailsText) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable apply = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null && detailsText != null && !detailsText.trim().isEmpty()) {
                    item.setMetaString(META_MESHCORE_MESH_DETAILS, detailsText.trim());
                }
            } catch (Exception e) {
                Log.w(TAG, "setMeshMarkerDetails failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            apply.run();
        } else {
            this.mapView.post(apply);
        }
    }

    /** Tag a marker as a MeshCore repeater advert point (after the usericon CoT is injected). */
    public void markMeshRepeaterMapItem(String uid) {
        tagMeshMapItemWithRetry(uid, true, 0);
    }

    /** Tag a marker as a MeshCore node advert point so the Mesh details panel handles clicks. */
    public void markMeshNodeMapItem(String uid) {
        tagMeshMapItemWithRetry(uid, false, 0);
    }

    /** CoT dispatch is async; the injected marker may not exist yet — retry briefly. */
    private static final int MESH_TAG_MAX_RETRIES = 5;
    private static final long MESH_TAG_RETRY_DELAY_MS = 120L;

    private void tagMeshMapItemWithRetry(String uid, boolean repeater, int attempt) {
        if (uid == null || uid.isEmpty() || this.mapView == null) {
            return;
        }
        Runnable tag = () -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        this.mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    item.setMetaBoolean(META_MESHCORE_MESH_REPEATER, repeater);
                    item.setMetaBoolean(META_MESHCORE_MESH_NODE, !repeater);
                } else if (attempt < MESH_TAG_MAX_RETRIES) {
                    this.mapView.postDelayed(
                            () -> tagMeshMapItemWithRetry(uid, repeater, attempt + 1),
                            MESH_TAG_RETRY_DELAY_MS);
                }
            } catch (Exception e) {
                Log.w(TAG, "mark" + (repeater ? "MeshRepeater" : "MeshNode")
                        + "MapItem failed uid=" + uid, e);
            }
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            tag.run();
        } else {
            this.mapView.post(tag);
        }
    }

    /**
     * Dispatch a CotEvent into ATAK's internal processing.
     */
    private void dispatchCotEvent(CotEvent event) {
        // Use ATAK's internal CotMapComponent to dispatch
        try {
            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.d(TAG, "Dispatched CoT event: " + event.getUID());
            applyRadioMarkerLabelPresentation(event.getUID());

            if (BuildConfig.DEBUG) {
                try {
                    com.atakmap.android.maps.MapItem item =
                            com.atakmap.android.maps.MapView.getMapView()
                                    .getRootGroup()
                                    .deepFindUID(event.getUID());

                    if (item != null) {
                        Log.d(TAG, "MARKER_DEBUG uid=" + item.getUID()
                                + " title=" + item.getTitle()
                                + " type=" + item.getType()
                                + " callsign=" + item.getMetaString("callsign", "NULL")
                                + " team=" + item.getMetaString("team", "NULL")
                                + " labels_on=" + item.hasMetaValue("labels_on")
                                + " hideLabel=" + item.hasMetaValue("hideLabel")
                                + " menu=" + item.getMetaString("menu", "NULL"));

                        if (item instanceof com.atakmap.android.maps.Marker) {
                            Log.d(TAG, "MARKER_DEBUG marker_class=true");
                        }
                    } else {
                        Log.d(TAG, "MARKER_DEBUG item not found uid=" + event.getUID());
                    }
                } catch (Exception dbg) {
                    Log.e(TAG, "MARKER_DEBUG failed", dbg);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to dispatch via CotMapComponent, "
                    + "trying broadcast", e);
            // Fallback: send as broadcast intent
            try {
                Intent intent = new Intent("com.atakmap.android.maps.COT_PLACED");
                intent.putExtra("xml", event.toString());
                AtakBroadcast.getInstance().sendBroadcast(intent);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to broadcast CoT event", e2);
            }
        }
    }

    /**
     * Ensure inbound radio contacts render callsign labels on-map (not dot-only marker).
     */
    private void applyRadioMarkerLabelPresentation(String uid) {
        if (uid == null || uid.isEmpty()) {
            return;
        }
        if (!uid.startsWith("ANDROID-")) {
            return;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null || mv.getRootGroup() == null) {
                return;
            }
            com.atakmap.android.maps.MapItem item = mv.getRootGroup().deepFindUID(uid);
            if (!(item instanceof com.atakmap.android.maps.Marker)) {
                return;
            }
            com.atakmap.android.maps.Marker marker =
                    (com.atakmap.android.maps.Marker) item;
            String callsign = item.getMetaString("callsign", null);
            if (callsign == null || callsign.trim().isEmpty()) {
                String fromUid = uid.substring("ANDROID-".length());
                callsign = fromUid;
                item.setMetaString("callsign", fromUid);
            }
            item.setTitle(callsign);
            item.setMetaBoolean("labels_on", true);
            item.removeMetaData("hideLabel");
            // Marker API path used by ATAK icon adapters for label visibility.
            marker.setShowLabel(true);
            // Keep callsign labels visible at wider map extents (target ~20km view).
            marker.setMinLabelRenderResolution(0.0d);
            marker.setMaxLabelRenderResolution(100000.0d);
            // Keep labels visible at normal map scales.
            item.setMetaDouble("minRenderScale", 1.0E-7d);
        } catch (Exception e) {
            Log.w(TAG, "applyRadioMarkerLabelPresentation failed uid=" + uid, e);
        }
    }

    /**
     * Start listening for outgoing CoT events to relay to radio.
     * Uses ATAK's PreSendProcessor to intercept all outgoing CoT.
     */
    public void startOutgoingRelay() {
        Log.d(TAG, "Outgoing CoT relay: "
                + (relayOutgoingSa ? "enabled" : "disabled"));

        outboundCommsLogger = new CommsLogger() {
            @Override
            public void dispose() {
            }

            @Override
            public void logSend(CotEvent event, String dest) {
                maybeRelayGeoChatFromCommsLogger(event);
                maybeRelayMapCotFromCommsLogger(event, dest);
            }

            @Override
            public void logSend(CotEvent event, String[] dests) {
                maybeRelayGeoChatFromCommsLogger(event);
            }

            @Override
            public void logReceive(CotEvent event, String src, String dest) {
                maybeHandleInboundNetworkWifiPing(event);
                MapView pingMv = MapView.getMapView();
                if (pingMv != null) {
                    PingReplyNotifier.maybeNotifyPingReplyFromCot(
                            pingMv.getContext(), event);
                }
                maybeSaRelayInboundNetworkCot(event);
            }
        };
        try {
            CommsMapComponent.getInstance().registerCommsLogger(outboundCommsLogger);
            Log.d(TAG, "Registered CommsLogger for outbound GeoChat capture");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register CommsLogger", e);
        }

        // Capture locally placed/edited/deleted CoT so RF-only nodes can propagate
        // point updates/deletes without requiring manual "send again".
        localCotBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                maybeRelayLocalCotBroadcast(intent);
            }
        };
        try {
            AtakBroadcast.DocumentedIntentFilter cotFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            cotFilter.addAction("com.atakmap.android.maps.COT_PLACED");
            cotFilter.addAction("com.atakmap.android.maps.COT_DELETED");
            AtakBroadcast.getInstance().registerReceiver(localCotBroadcastReceiver, cotFilter);
            Log.d(TAG, "Registered local COT_PLACED/COT_DELETED relay receiver");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register local COT relay receiver", e);
        }

        sharedMapItemListener = event -> maybeRelaySharedMapItem(event);
        try {
            mapView.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_PERSIST, sharedMapItemListener);
            mapView.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_SHARED, sharedMapItemListener);
            Log.d(TAG, "Registered ITEM_PERSIST/ITEM_SHARED relay listener");
        } catch (Exception e) {
            Log.w(TAG, "Failed to register shared map item relay listener", e);
        }

        preSendProcessor = (event, toUIDs) -> {
            if (event == null) return;

            Log.d(TAG, "PreSendProcessor fired: type=" + event.getType()
                    + " uid=" + event.getUID()
                    + " toUIDs=" + (toUIDs == null ? "null" : java.util.Arrays.toString(toUIDs)));

            String type = event.getType();
            // GeoChat delivery/read receipts must never go back out over RF.
            if ("b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
                return;
            }
            if (CotBuilder.isWifiNetworkOnlyCot(event)) {
                return;
            }
            boolean btConnected = btManager != null && btManager.isConnected();

            // Log GeoChat BEFORE BT gate — previous bug: early return hid whether PreSend
            // fired at all (shows up as zero lines when BLE disconnected during send tests).
            if ("b-t-f".equals(type)) {
                ensureOutboundGeoChatDestinationsKnown(toUIDs);
                Log.d(TAG, "PreSend GeoChat bluetoothOk=" + btConnected
                        + " uid=" + event.getUID()
                        + " toUIDs="
                        + (toUIDs == null ? "null"
                        : java.util.Arrays.toString(toUIDs))
                        + " registeredBtechUids=" + btechContactUids.size());
            }

            if (!btConnected) return;

            if (shouldSkipOutboundRelayWasInboundInject(event.getUID())) {
                return;
            }

            // Contact-targeted send: only relay when ATAK is sending to a
            // plugin-registered radio contact.
            boolean targetsBtechContact = false;
            if (toUIDs != null && toUIDs.length > 0) {
                for (String uid : toUIDs) {
                    if (uid == null) continue;
                    String trimmed = uid.trim();
                    // Fast path: already registered in our in-memory set (populated on beacon).
                    if (isBtechOutboundChatDestination(trimmed, null)) {
                        targetsBtechContact = true;
                        btechContactUids.add(trimmed);
                        String resolvedUid = resolveBtechUidForId(trimmed);
                        if (resolvedUid != null && !resolvedUid.isEmpty()) {
                            btechContactUids.add(resolvedUid);
                        }
                        break;
                    }
                    // Fallback: contact persisted from a previous session but no beacon yet
                    // this session — check whether it carries our MeshSendMessageConnector.
                    try {
                        Contact c = Contacts.getInstance().getContactByUuid(trimmed);
                        if (c == null) {
                            c = Contacts.getInstance().getContactByUuid(
                                    trimmed.toUpperCase(Locale.US));
                        }
                        if (c == null) {
                            String resolvedUid = resolveBtechUidForId(trimmed);
                            if (resolvedUid != null && !resolvedUid.isEmpty()) {
                                c = Contacts.getInstance().getContactByUuid(resolvedUid);
                            }
                        }
                        Log.d(TAG, "PreSend UID lookup: " + trimmed + " → " + (c == null ? "null" : c.getClass().getSimpleName()));
                        if (c instanceof IndividualContact) {
                            com.atakmap.android.contact.Connector conn =
                                    ((IndividualContact) c).getConnector(
                                            com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector.CONNECTOR_TYPE);
                            if (conn != null
                                    && ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(
                                            conn.getConnectionString())) {
                                targetsBtechContact = true;
                                // Also register now so future checks are fast.
                                btechContactUids.add(trimmed);
                                break;
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            if (type == null) return;

            // GeoChat (b-t-f) often lacks reliable toUID[] in PreSendProcessor; infer
            // destination from CoT (__chat/chatgrp/chatroom/remarks) like SEND_MESSAGE/COT_PLACED.
            if (!targetsBtechContact && "b-t-f".equals(type)) {
                boolean geoRelay = shouldRelayGeoChatToRadio(event);
                Log.d(TAG, "GeoChat shouldRelayGeoChatToRadio=" + geoRelay);
                if (geoRelay) {
                    targetsBtechContact = true;
                    Log.d(TAG, "GeoChat to BTECH contact via CoT routing (weak/missing toUIDs)");
                }
            }

            if (targetsBtechContact) {
                if ("b-t-f".equals(type)) {
                    Log.d(TAG, "Relaying contact-targeted CoT to radio: type=" + type
                            + " uid=" + event.getUID()
                            + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                    if (chatBridge != null) {
                        new Thread(() -> chatBridge.relayOutboundGeoChatCot(event)).start();
                        return;
                    }
                }
                if (isRelayableMapCotType(type)) {
                    if (!shouldRelayMapCotToContactUids(toUIDs)) {
                        Log.d(TAG, "Skipping RF relay for WiFi-only map CoT recipients"
                                + " (plugin contact): type=" + type
                                + " uid=" + event.getUID()
                                + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                        cancelPendingCotRfRetry(event.getUID());
                        return;
                    }
                    Log.d(TAG, "Relaying contact-targeted map CoT to radio: type=" + type
                            + " uid=" + event.getUID()
                            + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                    relayContactTargetedCotOverRadio(event, toUIDs);
                    return;
                }
                Log.d(TAG, "Relaying contact-targeted CoT to radio: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                relayContactTargetedCotOverRadio(event, toUIDs);
                return;
            }

            if (isRelayableMapCotType(type) && shouldRelayMapCotToContactUids(toUIDs)) {
                Log.d(TAG, "Relaying contact-targeted map CoT to radio: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                relayContactTargetedCotOverRadio(event, toUIDs);
                return;
            }

            if (isRelayableMapCotType(type) && toUIDs != null && toUIDs.length > 0) {
                Log.d(TAG, "Skipping RF relay for WiFi-only map CoT recipients: type=" + type
                        + " uid=" + event.getUID()
                        + " toUIDs=" + java.util.Arrays.toString(toUIDs));
                cancelPendingCotRfRetry(event.getUID());
                return;
            }

            if (shouldRelayBroadcastMapCot(event, toUIDs)) {
                maybeRelayBroadcastMapCotToRadio(event, toUIDs);
                return;
            }

            // Optional broadcast SA relay (rate-limited) — does NOT depend on
            // a specific destination contact.
            if (!relayOutgoingSa) return;

            long now = System.currentTimeMillis();
            if (now - lastSaRelay < SA_RELAY_RF_BROADCAST_INTERVAL_MS) return;

            boolean shouldRelay = type.startsWith("a-f-")   // friendly SA
                    || type.startsWith("b-r-f-h")           // CASEVAC/medevac
                    || type.startsWith("b-m-p")             // markers/points
                    || type.equals("b-t-f")                 // geochat
                    || type.startsWith("u-");               // user-defined
            if (!shouldRelay) return;

            // Don't relay our own injected radio events (avoid loops / chatter).
            // Radio-injected contacts and events use ANDROID-* UIDs.
            String uid = event.getUID();
            if (uid != null && uid.startsWith("ANDROID-")) return;

            lastSaRelay = now;
            Log.d(TAG, "Relaying broadcast CoT to radio: type=" + type
                    + " uid=" + event.getUID());
            new Thread(() -> sendCotOverRadio(event)).start();
        };

        try {
            CommsMapComponent.getInstance().registerPreSendProcessor(
                    preSendProcessor);
            Log.d(TAG, "Registered PreSendProcessor for outgoing CoT relay");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register PreSendProcessor", e);
        }
    }

    /**
     * SA Relay: when enabled, broadcast inbound network CoT (PLI, waypoints, routes)
     * over radio so radio-only users receive the picture.
     *
     * Guards:
     *  - SA Relay setting must be on
     *  - Radio must be connected
     *  - CoT must match the relay type filter
     *  - Must NOT be CoT that we injected from the radio (loop prevention)
     *  - Must NOT be our own PLI (beacon already handles local position)
     *  - Must respect the per-UID throttle so a fast-moving contact doesn't flood the channel
     */
    private void maybeSaRelayInboundNetworkCot(CotEvent event) {
        if (event == null) return;
        if (CotBuilder.isWifiNetworkOnlyCot(event)) {
            return;
        }
        if (!isRadioConnected()) {
            return;
        }
        if (!isSaRelayEnabled()) return;
        if (btManager == null || !btManager.isConnected()) return;

        String type = event.getType();
        if (!isSaRelayEligibleType(type)) return;

        String uid = event.getUID();
        if (uid == null) return;

        // Skip CoT we injected from the radio — loop prevention
        if (shouldSkipOutboundRelayWasInboundInject(uid)) return;

        // Skip our own PLI — manual beacon handles local position when the operator sends it
        String localUid = null;
        try { localUid = MapView.getDeviceUid(); } catch (Exception ignored) {}
        if (uid.equals(localUid)) return;

        // Per-UID throttle: don't relay the same contact onto RF more than once per interval.
        long now = System.currentTimeMillis();
        Long lastRelay = saRelayLastSentByUid.get(uid);
        if (lastRelay != null && (now - lastRelay) < SA_RELAY_RF_BROADCAST_INTERVAL_MS) return;
        saRelayLastSentByUid.put(uid, now);

        if ("b-t-f".equals(type) && relayInboundNetworkGeoChatIfAllRooms(event)) {
            return;
        }

        // Suppress unchanged periodic SA/status relays (Wi-Fi/TAK contact PLI churn).
        // Keep non-SA events (routes, markers, chat-like events) forwarding normally.
        String signature = null;
        if (type.startsWith("a-")) {
            signature = buildSaRelaySignature(event);
            String lastSignature = saRelayLastSignatureByUid.get(uid);
            if (signature != null && signature.equals(lastSignature)) {
                Log.d(TAG, "SA Relay: suppressed unchanged payload type=" + type + " uid=" + uid);
                return;
            }
        }

        Log.d(TAG, "SA Relay: broadcasting type=" + type + " uid=" + uid);
        if (signature != null) {
            saRelayLastSignatureByUid.put(uid, signature);
        }
        new Thread(() -> sendCotOverRadio(event)).start();
    }

    /**
     * Build a stable SA payload signature while ignoring volatile CoT timestamps.
     * This suppresses periodic retransmit of unchanged network SA.
     */
    private static String buildSaRelaySignature(CotEvent event) {
        if (event == null) {
            return null;
        }
        try {
            String xml = event.toString();
            if (xml == null || xml.isEmpty()) {
                return null;
            }
            return COT_TIME_ATTR_PATTERN.matcher(xml).replaceAll("");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isSaRelayEligibleType(String type) {
        if (type == null || type.isEmpty()) return false;
        if ("b-t-f".equals(type)) return true;
        return SA_RELAY_TYPE_PATTERN.matcher(type).find();
    }

    private boolean relayInboundNetworkGeoChatIfAllRooms(CotEvent event) {
        if (event == null || chatBridge == null) return false;
        if (!"b-t-f".equals(event.getType())) return false;
        try {
            com.atakmap.coremap.cot.event.CotDetail detail = event.getDetail();
            if (detail == null) return false;

            com.atakmap.coremap.cot.event.CotDetail remarks =
                    detail.getFirstChildByName(0, "remarks");
            String message = remarks != null ? remarks.getInnerText() : null;
            if (message == null || message.trim().isEmpty()) return false;

            com.atakmap.coremap.cot.event.CotDetail chat =
                    detail.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = detail.getFirstChildByName(0, "chat");
            }
            String room = chat != null ? chat.getAttribute("chatroom") : null;
            if (room == null || room.trim().isEmpty()) {
                room = ALL_CHAT_ROOMS;
            }
            if (!ALL_CHAT_ROOMS.equalsIgnoreCase(room.trim())) {
                return false;
            }

            String lineUid = event.getUID();
            Log.d(TAG, "SA Relay: forwarding network All Chat Rooms over RF uid=" + lineUid);
            chatBridge.sendChatOverRadio(localCallsign, ALL_CHAT_ROOMS, message, lineUid);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "SA Relay: failed forwarding network chat", e);
            return false;
        }
    }

    /**
     * Duplicate path after core comms successfully accepts the send — often skipped for
     * plugin-native contacts ("unknown contact" path). PreSend geo hook is authoritative.
     */
    private void maybeRelayGeoChatFromCommsLogger(CotEvent event) {
        if (btManager == null || !btManager.isConnected()) return;
        if (event == null || !"b-t-f".equals(event.getType())) return;
        if (shouldSkipOutboundRelayWasInboundInject(event.getUID())) return;
        Log.d(TAG, "CommsLogger logSend b-t-f uid=" + event.getUID());
        if (!shouldRelayGeoChatToRadio(event)) return;

        String uid = event.getUID();
        Log.d(TAG, "Outbound GeoChat (CommsLogger) relay: uid=" + uid);
        if (chatBridge != null) {
            new Thread(() -> chatBridge.relayOutboundGeoChatCot(event)).start();
        } else if (GeoChatContactListHelper.requiresFullCotRelay(event)) {
            scheduleSlottedGroupContactCotRelay(event);
        } else {
            new Thread(() -> sendCotOverRadio(event)).start();
        }
    }

    private void maybeRelayMapCotFromCommsLogger(CotEvent event, String dest) {
        if (dest == null || !"broadcast".equalsIgnoreCase(dest.trim())) return;
        maybeRelayBroadcastMapCotToRadio(event, null);
    }

    /**
     * One RF transmission for a net-wide map point (Auto Send / broadcast): no ACK retries,
     * max once per {@link #BROADCAST_MAP_RF_MIN_INTERVAL_MS} per marker uid.
     */
    private void maybeRelayBroadcastMapCotToRadio(CotEvent event, String[] toUIDs) {
        if (btManager == null || !btManager.isConnected()) return;
        if (event == null || CotBuilder.isWifiNetworkOnlyCot(event)) return;
        if (!shouldRelayBroadcastMapCot(event, toUIDs)) return;

        String uid = event.getUID();
        if (uid == null || uid.trim().isEmpty()) return;
        String trimUid = uid.trim();
        if (shouldSkipOutboundRelayWasInboundInject(trimUid)) return;

        long now = System.currentTimeMillis();
        Long lastRf = lastBroadcastMapRfRelayByUid.get(trimUid);
        if (lastRf != null && now - lastRf < BROADCAST_MAP_RF_MIN_INTERVAL_MS) {
            Log.d(TAG, "Broadcast map RF skipped (max 1 per 2m): uid=" + trimUid
                    + " retry in " + ((BROADCAST_MAP_RF_MIN_INTERVAL_MS - (now - lastRf)) / 1000) + "s");
            return;
        }
        if (isDuplicateLocalRelay(event)) return;

        lastBroadcastMapRfRelayByUid.put(trimUid, now);
        Log.d(TAG, "Broadcast map RF chirp: type=" + event.getType() + " uid=" + trimUid);
        new Thread(() -> sendCotOverRadioNoRetry(event)).start();
    }

    private void maybeRelaySharedMapItem(MapEvent event) {
        if (event == null || btManager == null || !btManager.isConnected()) return;

        String eventType = event.getType();
        if (!MapEvent.ITEM_PERSIST.equals(eventType)
                && !MapEvent.ITEM_SHARED.equals(eventType)) {
            return;
        }

        Class<?> from = event.getFrom();
        if (from == null
                || !ContactPresenceDropdown.class.getName().equals(from.getName())) {
            return;
        }

        Bundle extras = event.getExtras();
        if (extras == null || !extras.getBoolean("dontSend", false)) {
            return;
        }
        if (extras.getBoolean("internal", true)) {
            return;
        }
        String[] toUIDs = extras.getStringArray("toUIDs");
        if (toUIDs != null && toUIDs.length > 0) {
            return;
        }

        MapItem item = event.getItem();
        if (item == null) return;

        CotEvent cotEvent;
        try {
            cotEvent = CotEventFactory.createCotEvent(item);
        } catch (Exception e) {
            Log.w(TAG, "Failed to build CoT for shared map item uid=" + item.getUID(), e);
            return;
        }
        if (cotEvent == null || !isRelayableMapCotType(cotEvent.getType())) {
            return;
        }

        String uid = cotEvent.getUID();
        if (uid != null && shouldSkipOutboundRelayWasInboundInject(uid)) return;
        if (isDuplicateLocalRelay(cotEvent)) return;

        markMapItemSendable(uid);
        Log.d(TAG, "Shared map item broadcast relay: type=" + cotEvent.getType()
                + " uid=" + uid + " via=" + eventType);
        new Thread(() -> sendCotOverRadio(cotEvent)).start();
    }

    /**
     * Full {@code b-t-f} with contact {@code hierarchy} — slotted to reduce RF collisions.
     */
    public void scheduleSlottedGroupContactCotRelay(CotEvent event) {
        if (event == null || btManager == null || !btManager.isConnected()) {
            return;
        }
        RfTxArbitrator.get().setSlottedTxPending(true);
        slottedCoTScheduler.scheduleGroupContactCot(pluginContext, () -> {
            Log.i(TAG, "Group/contact-list CoT TX: uid=" + event.getUID());
            sendCotOverRadio(event);
            // RF links can drop one fragment; send one delayed duplicate for reliability.
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (btManager != null && btManager.isConnected()) {
                    Log.i(TAG, "Group/contact-list CoT TX (redundant): uid=" + event.getUID());
                    sendCotOverRadio(event);
                }
            }, GROUP_CONTACT_COT_REDUNDANT_TX_DELAY_MS);
        });
    }

    /**
     * Optional RF -> TAK uplink path.
     * Active only when SA Relay and RF-to-TAK uplink are both enabled.
     */
    private void maybeRelayInboundRadioCotToTak(CotEvent event) {
        if (event == null) return;
        if (!isRfToTakUplinkEnabled()) return;
        // GeoChat events are already delivered via GeoChatService — dispatching them again
        // through CotMapComponent causes duplicate conversation entries and extra notifications.
        if (isGeoChatCotType(event.getType())) return;
        try {
            CotMapComponent.getExternalDispatcher().dispatch(event);
            Log.d(TAG, "RF -> TAK uplink dispatched: type=" + event.getType()
                    + " uid=" + event.getUID());
        } catch (Exception e) {
            Log.w(TAG, "RF -> TAK uplink failed: " + e.getMessage());
        }
    }

    private void maybeRelayLocalCotBroadcast(Intent intent) {
        if (intent == null) return;
        if (btManager == null || !btManager.isConnected()) return;

        final String action = intent.getAction();
        if (action == null) return;

        String cotXml = intent.getStringExtra("xml");
        if (cotXml == null || cotXml.isEmpty()) {
            cotXml = intent.getStringExtra("cotXml");
        }
        if (cotXml == null || cotXml.isEmpty()) {
            cotXml = intent.getStringExtra("cot");
        }

        CotEvent event = null;
        if (cotXml != null && !cotXml.isEmpty()) {
            try {
                event = CotEvent.parse(cotXml);
            } catch (Exception ignored) {
            }
        }
        if (event == null) {
            String uid = intent.getStringExtra("uid");
            if (uid != null && !uid.isEmpty() && mapView != null) {
                MapItem item = mapView.getRootGroup().deepFindUID(uid);
                if (item != null) {
                    try {
                        event = CotEventFactory.createCotEvent(item);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (event == null) return;

        final CotEvent relayEvent = event;
        if (isRelayableMapCotType(relayEvent.getType())) {
            markMapItemSendable(relayEvent.getUID());
        }
        if (!shouldRelayLocalMapCot(relayEvent, action)) return;

        String uid = relayEvent.getUID();
        if (uid != null && shouldSkipOutboundRelayWasInboundInject(uid)) return;
        if (isDuplicateLocalRelay(relayEvent)) return;

        Log.d(TAG, "Local map CoT relay over RF: action=" + action
                + " type=" + relayEvent.getType() + " uid=" + uid);
        new Thread(() -> sendCotOverRadio(relayEvent)).start();
    }

    private void runOnMapView(Runnable action) {
        if (action == null || mapView == null) {
            return;
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            mapView.post(action);
        }
    }

    public void markMapItemSendable(String uid) {
        if (uid == null || uid.trim().isEmpty() || mapView == null) {
            return;
        }
        if (!isRadioConnected()) {
            return;
        }
        final String trimmed = uid.trim();
        runOnMapView(() -> {
            try {
                com.atakmap.android.maps.MapItem item =
                        mapView.getRootGroup().deepFindUID(trimmed);
                if (item == null) {
                    return;
                }
                if (!item.getMetaBoolean("sendable", false)) {
                    item.setMetaBoolean("sendable", true);
                    Log.d(TAG, "Marked map item sendable uid=" + trimmed
                            + " type=" + item.getType());
                }
            } catch (Exception e) {
                Log.w(TAG, "markMapItemSendable failed uid=" + trimmed, e);
            }
        });
    }

    public void refreshSendableMapItems() {
        if (!isRadioConnected() || mapView == null) {
            return;
        }
        runOnMapView(() -> {
            try {
                refreshSendableMapItems(mapView.getRootGroup());
            } catch (Exception e) {
                Log.w(TAG, "refreshSendableMapItems failed", e);
            }
        });
    }

    private void refreshSendableMapItems(com.atakmap.android.maps.MapGroup group) {
        if (group == null) {
            return;
        }
        for (com.atakmap.android.maps.MapItem item : group.getItems()) {
            if (item == null) {
                continue;
            }
            String type = item.getType();
            if (type != null && isRelayableMapCotType(type)) {
                if (!item.getMetaBoolean("sendable", false)) {
                    item.setMetaBoolean("sendable", true);
                }
            }
        }
        for (com.atakmap.android.maps.MapGroup child : group.getChildGroups()) {
            refreshSendableMapItems(child);
        }
    }

    private boolean shouldRelayLocalMapCot(CotEvent event, String action) {
        if (event == null) return false;
        String type = event.getType();
        if (type == null || type.isEmpty()) return false;

        if ("b-t-f".equals(type) || "b-t-f-r".equals(type) || "b-t-f-d".equals(type)) {
            return false;
        }

        if (isSelfPliBeacon(event)) {
            return false;
        }

        if ("com.atakmap.android.maps.COT_DELETED".equals(action)) {
            return true;
        }
        return isRelayableMapCotType(type);
    }

    private boolean isDuplicateLocalRelay(CotEvent event) {
        if (event == null) return true;
        String uid = event.getUID();
        String type = event.getType();
        String key = (uid == null ? "nouid" : uid) + "|" + (type == null ? "notype" : type);
        long now = System.currentTimeMillis();
        Long until = recentLocalRelayKeys.get(key);
        if (until != null && now < until) {
            return true;
        }
        recentLocalRelayKeys.put(key, now + LOCAL_RELAY_DEDUPE_MS);
        return false;
    }

    /**
     * UID for GeoChat {@code chatgrp}/{@code __chat} "local endpoint" when ingesting inbound DMs from
     * a background thread. {@link MapView#getDeviceUid()} may return NULL off the UI thread unless
     * {@link MapView#getMapView()} is initialized — fall back to self marker UID.
     */
    private String resolveLocalAtakUidForChatGrp(String peerSenderUid,
            String dmConversationId) {
        String local = tryResolveAtakSelfUidForChatGrp(mapView);
        if (local == null && dmConversationId != null
                && dmConversationId.startsWith(ANDROID_UID_PREFIX)) {
            Log.w(TAG, "injectChatCot: could not resolve local ATAK UID for chatgrp.uid1;"
                    + " GeoChat ACK/DM pairing may fail (conversation=" + dmConversationId + ")");
        }
        if (local != null && peerSenderUid != null && peerSenderUid.equals(local)) {
            Log.w(TAG, "injectChatCot: device uid equals peer sender " + local
                    + " — chatgrp may still be invalid");
        }
        return local;
    }

    private static String tryResolveAtakSelfUidForChatGrp(MapView instanceMapView) {
        MapView mv = null;
        try {
            mv = MapView.getMapView();
        } catch (Exception ignored) {
        }
        if (mv == null && instanceMapView != null) {
            mv = instanceMapView;
        }
        try {
            String id = MapView.getDeviceUid();
            if (!isBlank(id)) {
                return id.trim();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mv != null) {
                com.atakmap.android.maps.Marker sm = mv.getSelfMarker();
                if (sm != null) {
                    String id = sm.getUID();
                    if (!isBlank(id)) {
                        return id.trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Stop and clean up.
     */
    public void dispose() {
        // Unregister PreSendProcessor — no unregister API, just null it out
        preSendProcessor = null;
        if (localCotBroadcastReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(localCotBroadcastReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregister localCotBroadcastReceiver", e);
            }
            localCotBroadcastReceiver = null;
        }
        if (sharedMapItemListener != null && mapView != null) {
            try {
                mapView.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_PERSIST, sharedMapItemListener);
                mapView.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_SHARED, sharedMapItemListener);
            } catch (Exception e) {
                Log.w(TAG, "unregister sharedMapItemListener", e);
            }
            sharedMapItemListener = null;
        }
        if (outboundCommsLogger != null) {
            try {
                CommsMapComponent.getInstance()
                        .unregisterCommsLogger(outboundCommsLogger);
            } catch (Exception e) {
                Log.w(TAG, "unregisterCommsLogger", e);
            }
            outboundCommsLogger = null;
        }
        cotRetryExecutor.shutdownNow();
        pendingOutboundCots.clear();
        btechContactUids.clear();
        btechIdToUid.clear();
        saRelayLastSentByUid.clear();
        recentLocalRelayKeys.clear();
        Log.d(TAG, "CotBridge disposed");
    }
}
