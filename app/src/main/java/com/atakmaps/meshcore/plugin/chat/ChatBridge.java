package com.atakmaps.meshcore.plugin.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.fragment.app.Fragment;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.comms.NetConnectString;
import com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.android.preference.AtakPreferences;

import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.protocol.MeshCorePacket;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;
import com.atakmaps.meshcore.plugin.MeshCoreContactHandler;

import com.atakmap.android.util.NotificationUtil;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bridges ATAK GeoChat messages with the radio link.
 *
 * Inbound (radio → ATAK):
 *   - Receives chat messages from PacketRouter
 *   - Uses CotBridge to inject GeoChat CoT events
 *
 * Outbound (ATAK → radio):
 *   - Listens for GeoChat send intents from ATAK
 *   - Packages as MeshCore chat packets and sends to radio
 *
 * GeoChat in ATAK uses CoT events with type "b-t-f" (bits-text-free).
 * The actual message text is in detail/remarks inner text.
 */
public class ChatBridge {

    private static final String TAG = "MeshCore.ChatBridge";

    /** ATAK broadcasts some GeoChat sends with this intent (extras vary). */
    private static final String ACTION_CHAT_SEND =
            "com.atakmap.android.chat.SEND_MESSAGE";
    /** RF payload wrapper for non-radio destination gatewaying (B -> A -> TAK). */
    private static final String GW_PREFIX = "__UVGW__|";
    private static final String ANDROID_UID_PREFIX = "ANDROID-";
    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";

    /**
     * Broadcast action for outbound GeoChat to a contact whose delivery path uses
     * {@link com.atakmap.android.contact.PluginConnector}. ATAK invokes
     * {@code new Intent(connector.getConnectionString())...putExtra(\"MESSAGE\", bundle)}
     * (see ChatManagerMapComponent.sendMessageToDests) — so the action must be predictable
     * for our listener, not an opaque placeholder string.
     */
    public static final String ACTION_PLUGIN_CONTACT_GEOCHAT_SEND =
            "com.atakmaps.meshcore.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

    private final Context pluginContext;
    private final MapView mapView;
    private CotBridge cotBridge;
    private BtConnectionManager btManager;
    private EncryptionManager encryptionManager;
    private String localCallsign = "OPENRL";

    /** Whether to relay outgoing chat to radio */
    private boolean relayOutgoing = true;

    private BroadcastReceiver chatReceiver;
    private BroadcastReceiver chatMarkReadReceiver;
    private BroadcastReceiver chatOpenReceiver;
    private BroadcastReceiver chatClosedReceiver;

    /**
     * Track the currently-open GeoChat conversation (if any). If the conversation is open,
     * inbound messages should not increment the contacts badge because the user is already
     * viewing the chat.
     */
    private volatile String openConversationId;

    /**
     * ATAK marks lines read in {@code ConversationFragment} via {@code markAllRead} →
     * {@code Contact.setUnreadCount} without always broadcasting {@code markmessageread}.
     * When native unread drops from &gt; 0 to 0, clear our plugin badge for that ANDROID-* uid.
     */
    private final ConcurrentHashMap<String, Integer> lastAtakUnreadByUid =
            new ConcurrentHashMap<>();
    private Contacts.OnContactsChangedListener contactsUnreadSyncListener;

    /**
     * Dedup cache for inbound native MeshCore DMs. MeshCore retransmits until ACK'd, so the
     * same (pubKeyPrefix, mid) pair can arrive multiple times in quick succession. We drop
     * any re-delivery within MESH_DM_DEDUP_TTL_MS of the first injection.
     * Key: "PREFIX|mid"  Value: expiry epoch ms
     */
    private static final long MESH_DM_DEDUP_TTL_MS = 60_000L;
    private final ConcurrentHashMap<String, Long> recentlyInjectedMeshDmKeys =
            new ConcurrentHashMap<>();

    /** Runs after ATAK delivers chat to the UI (fragment may not exist yet on first callback). */
    private ChatManagerMapComponent.ChatMessageListener atakChatMessageListener;

    /**
     * Conversations with pending unread messages. Some ATAK paths open GeoChat without
     * broadcasting OPEN_GEOCHAT/markmessageread; we poll for a visible ConversationFragment
     * and clear unread as soon as the user is actually viewing the thread.
     */
    private final Set<String> pendingUnreadConversationUids = ConcurrentHashMap.newKeySet();
    private volatile boolean unreadVisibilityPollRunning;

    private volatile boolean disposed;

    /**
     * Wire {@link MeshCorePacket} chat {@code messageId} → sender's local GeoChat line UID
     * (from {@code COT_PLACED}), used to apply RF {@link MeshCorePacket#TYPE_ACK} receipts.
     */
    private final ConcurrentHashMap<Integer, String> outboundWireMidToLocalLineUid =
            new ConcurrentHashMap<>();
    private static final int MAX_OUTBOUND_ACK_ENTRIES = 384;

    /**
     * Inbound wire mid ACKs waiting for the user to open the conversation (READ receipt).
     * Key: ANDROID-* conversation UID on this device.
     * Value: set of wire message ids received but not yet READ-acked over RF.
     */
    private final ConcurrentHashMap<String, Set<Integer>> pendingReadAcksByConversation =
            new ConcurrentHashMap<>();

    // --- Outbound retry / delivery-failure tracking ---

    /** Retry a send if no DELIVERED ACK received within this window. */
    private static final long RETRY_INTERVAL_MS = 2 * 60 * 1000L; // 2 minutes

    /** Number of retransmit attempts before declaring failure. */
    private static final int MAX_CHAT_RETRIES = 3;

    /** Tracks unacknowledged outbound messages for retry and failure notification. */
    private final ConcurrentHashMap<Integer, PendingOutboundChat> pendingOutboundChats =
            new ConcurrentHashMap<>();

    /**
     * Messages that exhausted all retries. Keyed by peer callsign (bare, no "ANDROID-" prefix).
     * Re-sent automatically when the peer's beacon or ping is received.
     */
    private final ConcurrentHashMap<String, java.util.Queue<PendingOutboundChat>> failedOutboundChatsByPeer =
            new ConcurrentHashMap<>();

    /** Single-thread executor for retry watchdog scheduling. */
    private final ScheduledExecutorService retryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MeshCore-ChatRetry");
                t.setDaemon(true);
                return t;
            });

    /** Tracks all parameters needed to retransmit an unacknowledged outbound chat. */
    private static final class PendingOutboundChat {
        final int wireMid;
        final String sender;
        final String room;
        final String message;
        final String geoChatLineUid; // may be null
        volatile int retryCount;

        PendingOutboundChat(int wireMid, String sender, String room, String message, String geoChatLineUid) {
            this.wireMid = wireMid;
            this.sender = sender;
            this.room = room;
            this.message = message;
            this.geoChatLineUid = geoChatLineUid;
            this.retryCount = 0;
        }
    }

    /**
     * Skip sending the same GeoChat line twice when both PreSend and COT_PLACED (or CommsLogger)
     * observe one user action.
     */
    private final Object outboundGeoChatDedupeLock = new Object();
    private String lastOutboundGeoChatDedupeUid;
    private long lastOutboundGeoChatDedupeMs;

    private volatile boolean loggedPluginChatBundleKeysMissingUid;

    public ChatBridge(Context pluginContext, MapView mapView) {
        this.pluginContext = pluginContext;
        this.mapView = mapView;
    }

    public void setCotBridge(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    public void setBtManager(BtConnectionManager btManager) {
        this.btManager = btManager;
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    public void setLocalCallsign(String callsign) {
        this.localCallsign = callsign;
    }

    public void setRelayOutgoing(boolean relay) {
        this.relayOutgoing = relay;
    }

    /**
     * Inject a message received from the radio into ATAK as GeoChat.
     *
     * @param fromCallsign         Sender callsign (may be AX.25-truncated)
     * @param toCallsign           Destination (callsign or room name) from the wire
     * @param message              Message body
     * @param radioPacketMessageId TYPE_CHAT payload id ({@code putInt}); 0 if unknown.
     */
    public boolean injectRadioMessage(String fromCallsign, String toCallsign,
                                   String message, int radioPacketMessageId) {
        if (cotBridge == null) {
            Log.w(TAG, "CotBridge not set — cannot inject chat");
            return false;
        }
        if (message == null || message.isEmpty()) return false;

        // Gateway envelope: wireDest|displayCallsign|lineUid|message (UV-PRO compatible).
        String gatewayWireDest = null;
        String gatewayDisplayCallsign = null;
        String gatewayLineUid = null;
        GatewayWrapped gw = parseGatewayWrappedMessage(message);
        if (gw != null) {
            message = gw.message;
            gatewayWireDest = gw.wireDest;
            gatewayDisplayCallsign = gw.displayCallsign;
            gatewayLineUid = gw.lineUid;
            Log.d(TAG, "Inbound RF gateway envelope wireDest=" + gatewayWireDest
                    + " displayCallsign=" + gatewayDisplayCallsign
                    + (gatewayLineUid != null && !gatewayLineUid.isEmpty()
                    ? " lineUid=" + gatewayLineUid : ""));
        }

        // Display: the transport truncates the sender callsign to 6 chars (e.g. JESTER_15 -> JSTR15).
        // Resolve it back to the full callsign of a known peer so the UI shows/threads the full name.
        String resolvedFrom = resolveFullCallsignForWireForm(fromCallsign);
        if (resolvedFrom != null && !resolvedFrom.isEmpty()) {
            Log.d(TAG, "Inbound chat sender " + fromCallsign + " -> full callsign " + resolvedFrom);
            fromCallsign = resolvedFrom;
        }

        // Determine chat room — if destination is a specific callsign,
        // use direct chat. Otherwise use broadcast.
        String chatRoom;
        if (toCallsign == null || toCallsign.isEmpty()
                || "ALL".equalsIgnoreCase(toCallsign)
                || "BLN".equalsIgnoreCase(toCallsign.substring(0,
                Math.min(3, toCallsign.length())))) {
            chatRoom = "All Chat Rooms";
        } else {
            chatRoom = toCallsign.trim();
        }
        if (gatewayDisplayCallsign != null && !gatewayDisplayCallsign.isEmpty()) {
            chatRoom = gatewayDisplayCallsign;
        } else if (gatewayWireDest != null && !gatewayWireDest.isEmpty()) {
            chatRoom = gatewayWireDest;
        }

        String lineSenderUid = parseGeoChatSenderUid(gatewayLineUid);
        String btechUid = cotBridge != null ? cotBridge.resolveBtechUidForId(fromCallsign) : null;
        String senderUid;
        if (ContactMergeUtil.isMeshNodeUid(fromCallsign)) {
            senderUid = fromCallsign.trim();
        } else if (lineSenderUid != null && !lineSenderUid.isEmpty()) {
            senderUid = ContactMergeUtil.resolveCanonicalPeerUid(fromCallsign, lineSenderUid, btechUid);
        } else {
            senderUid = ContactMergeUtil.resolveCanonicalPeerUid(fromCallsign, btechUid);
        }

        // Direct DM: thread id must be the *remote* peer's UID.
        // 6-byte "room" (RF destination) that is often THIS operator's callsign (e.g. JUNIOR).
        // Local operator is rarely in btechIdToUid MapView.getDeviceUid() is ANDROID-1729… not
        // ANDROID-JUNIOR — so resolveBtechUidForId("JUNIOR") is often NULL and we incorrectly
        // used sender ANDROID-VETTE as both ends (GeoChat.ANDROID-VETTE.ANDROID-VETTE).
        // Detect RF dest == configured local callsign (or its AX.25 form) FIRST, then use sender UID.
        if (!"All Chat Rooms".equalsIgnoreCase(chatRoom)) {
            boolean keepGroupThread = isLikelyGroupConversationThread(chatRoom);
            boolean destinationLooksSelf = inboundRfDestinationLooksLikeSelf(
                    gatewayWireDest, gatewayDisplayCallsign, chatRoom, toCallsign);

            // Direct RF chat is not routed/hopped: if this packet is explicitly addressed to
            // another peer, do not inject it locally or mutate Contacts.
            if (!keepGroupThread && !destinationLooksSelf) {
                String destUid = cotBridge.resolveBtechUidForId(chatRoom);
                String selfUid = null;
                try {
                    selfUid = MapView.getDeviceUid();
                } catch (Exception ignored) {
                }
                Log.d(TAG, "Inbound DM ignored (not for this device): from=" + fromCallsign
                        + " room=" + chatRoom + " destUid=" + destUid + " selfUid=" + selfUid);
                return false;
            }

            if ((senderUid == null || senderUid.isEmpty()) && fromCallsign != null
                    && !ContactMergeUtil.isMeshNodeUid(fromCallsign)) {
                senderUid = syntheticAndroidUid(fromCallsign);
            }
            if (senderUid != null && !senderUid.isEmpty()
                    && (senderUid.startsWith(ANDROID_UID_PREFIX)
                    || ContactMergeUtil.isMeshNodeUid(senderUid))) {
                if (ContactMergeUtil.isMeshNodeUid(senderUid)) {
                    MeshCoreContactHandler.ensureMeshInboundChatContact(
                            extractMeshPublicKeyCandidate(senderUid));
                } else {
                    ensurePluginChatContact(fromCallsign, senderUid);
                    ContactMergeUtil.collapseDuplicateContactsForCallsign(fromCallsign, senderUid);
                }
                cotBridge.registerBtechContactUid(senderUid);
                if (fromCallsign != null && !fromCallsign.trim().isEmpty()) {
                    cotBridge.registerBtechContactId(fromCallsign, senderUid);
                }
            }
            String selfUid = null;
            try {
                selfUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }
            String destUid = cotBridge.resolveBtechUidForId(chatRoom);

            boolean peerThreadResolved = false;
            if (rfDestinationLooksLikeSelf(chatRoom.trim())
                    && senderUid != null && !senderUid.isEmpty()
                    && (selfUid == null || !selfUid.equals(senderUid))) {
                Log.d(TAG, "Inbound DM: RF destination is local callsign \"" + chatRoom
                        + "\" — thread → remote peer " + senderUid);
                chatRoom = senderUid;
                peerThreadResolved = true;
            }

            if (!peerThreadResolved && !keepGroupThread && selfUid != null && destUid != null
                    && selfUid.equals(destUid)
                    && senderUid != null && !selfUid.equals(senderUid)) {
                Log.d(TAG, "Inbound DM: destination is self — thread id → remote " + senderUid
                        + " (RF room was " + chatRoom + ")");
                chatRoom = senderUid;
            } else if (!peerThreadResolved && !keepGroupThread
                    && senderUid != null && !senderUid.isEmpty()
                    && (selfUid == null || !selfUid.equals(senderUid))) {
                Log.d(TAG, "Inbound DM: thread id " + chatRoom + " → " + senderUid
                        + " (match contact chat)");
                chatRoom = senderUid;
            } else if (!peerThreadResolved && destUid != null && !destUid.isEmpty()
                    && (selfUid == null || !selfUid.equals(destUid))) {
                chatRoom = destUid;
            }
        }

        Log.d(TAG, "Injecting radio message (mid=" + radioPacketMessageId + "): "
                + fromCallsign + " → " + chatRoom + ": " + message);

        if (GeoChatContactListHelper.isContactListUpdateMessage(message)
                && radioPacketMessageId > 0) {
            Log.w(TAG, "Received compact [UPDATED CONTACTS] without hierarchy — "
                    + "group sync requires full CoT over RF; ask sender to update plugin");
        }

        // Maintain a plugin unread counter for Contacts icon badge.
        // (Native GeoChat unread tracking is not reliably reflected for plugin contacts on all builds.)
        if (chatRoom != null && (chatRoom.startsWith(ANDROID_UID_PREFIX)
                || chatRoom.startsWith(MESH_NODE_UID_PREFIX))) {
            // Track wire mid for READ ACK once the user opens the conversation.
            if (radioPacketMessageId > 0) {
                addPendingReadAck(chatRoom, radioPacketMessageId);
            }
            String open = openConversationId;
            if (open != null && open.equals(chatRoom)) {
                // Conversation is open; treat as already-seen.
                clearUnreadLocal(chatRoom);
            } else {
                MeshCoreContactHandler.incrementUnreadOnce(chatRoom, radioPacketMessageId, message);
                pendingUnreadConversationUids.add(chatRoom);
                startUnreadVisibilityPollIfNeeded();
            }
        }

        String senderDisplay = ContactMergeUtil.displayCallsignForContact(fromCallsign, senderUid);
        cotBridge.injectChatCot(senderDisplay, message, chatRoom,
                radioPacketMessageId, gatewayLineUid, senderUid);
        return true;
    }

    private boolean inboundRfDestinationLooksLikeSelf(String gatewayWireDest,
                                                      String gatewayDisplayCallsign,
                                                      String chatRoom,
                                                      String wireToCallsign) {
        String selfUid = null;
        try {
            selfUid = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        if (gatewayWireDest != null && selfUid != null
                && selfUid.equalsIgnoreCase(gatewayWireDest.trim())) {
            return true;
        }
        if (gatewayWireDest != null && !gatewayWireDest.trim().isEmpty()
                && rfDestinationLooksLikeSelf(gatewayWireDest.trim())) {
            return true;
        }
        if (gatewayDisplayCallsign != null && !gatewayDisplayCallsign.trim().isEmpty()
                && rfDestinationLooksLikeSelf(gatewayDisplayCallsign.trim())) {
            return true;
        }
        if (chatRoom != null && selfUid != null
                && chatRoom.trim().equalsIgnoreCase(selfUid)) {
            return true;
        }
        if (chatRoom != null && !chatRoom.trim().isEmpty()
                && rfDestinationLooksLikeSelf(chatRoom.trim())) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(chatRoom)) {
            return true;
        }
        if (wireToCallsign != null && !wireToCallsign.trim().isEmpty()
                && rfDestinationLooksLikeSelf(wireToCallsign.trim())) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(gatewayWireDest)) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(gatewayDisplayCallsign)) {
            return true;
        }
        if (inboundDestinationMatchesLocalMeshPubKey(wireToCallsign)) {
            return true;
        }
        return false;
    }

    /** Sender UID from {@code GeoChat.{senderUid}.{thread}.{suffix}} on RF gateway relay. */
    static String parseGeoChatSenderUid(String geoChatLineUid) {
        if (geoChatLineUid == null || geoChatLineUid.trim().isEmpty()) {
            return null;
        }
        String trimmed = geoChatLineUid.trim();
        if (!trimmed.startsWith("GeoChat.")) {
            return null;
        }
        String rest = trimmed.substring("GeoChat.".length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        rest = rest.substring(0, lastDot);
        int prevDot = rest.lastIndexOf('.');
        if (prevDot <= 0 || prevDot >= rest.length() - 1) {
            return null;
        }
        String senderUid = rest.substring(0, prevDot).trim();
        return senderUid.isEmpty() ? null : senderUid;
    }

    public static void setMergeRoutingBridge(CotBridge bridge) {
        ContactMergeUtil.setMergeRoutingBridge(bridge);
    }

    public static void collapseAllCallsignAliasDuplicates() {
        ContactMergeUtil.collapseAllCallsignAliasDuplicates();
    }

    private boolean inboundDestinationMatchesLocalMeshPubKey(String rawDestination) {
        String candidate = extractMeshPublicKeyCandidate(rawDestination);
        if (candidate.isEmpty()) {
            return false;
        }
        String local = getLocalMeshPublicKey();
        return !local.isEmpty() && candidate.equalsIgnoreCase(local);
    }

    private String getLocalMeshPublicKey() {
        if (btManager == null) {
            return "";
        }
        String key = btManager.getSelfPubKeyHex();
        if (key == null) {
            return "";
        }
        String trimmed = key.trim().toUpperCase(Locale.US);
        return isLikelyMeshPublicKey(trimmed) ? trimmed : "";
    }

    private static String extractMeshPublicKeyCandidate(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase(Locale.US);
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith(MESH_NODE_UID_PREFIX)) {
            String suffix = value.substring(MESH_NODE_UID_PREFIX.length()).trim();
            return isLikelyMeshPublicKey(suffix) ? suffix : "";
        }
        if (value.startsWith(MESH_RPTR_UID_PREFIX)) {
            String suffix = value.substring(MESH_RPTR_UID_PREFIX.length()).trim();
            return isLikelyMeshPublicKey(suffix) ? suffix : "";
        }
        return isLikelyMeshPublicKey(value) ? value : "";
    }

    private static boolean isLikelyMeshPublicKey(String key) {
        if (key == null || key.length() != 64) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'F')
                    || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * AX.25 wire destination: prefer a MeshCore pubkey extracted from the UID/room hints (or a raw
     * 64-hex string) before falling back to a 6-char radio callsign derived from the ATAK callsign.
     */
    private String resolveRfWireDestination(String toUidHint, String displayCallsignHint) {
        String meshKey = extractMeshPublicKeyCandidate(toUidHint);
        if (meshKey.isEmpty()) {
            meshKey = extractMeshPublicKeyCandidate(displayCallsignHint);
        }
        if (!meshKey.isEmpty()) {
            return meshKey;
        }
        String hint = displayCallsignHint != null ? displayCallsignHint.trim() : "";
        if (hint.isEmpty() && toUidHint != null) {
            hint = toUidHint.trim();
        }
        if (hint.isEmpty()) {
            return "";
        }
        String upper = hint.toUpperCase(Locale.US);
        if (upper.startsWith(ANDROID_UID_PREFIX)) {
            hint = upper.substring(ANDROID_UID_PREFIX.length());
        }
        String radio = CallsignUtil.toRadioCallsign(hint);
        if (radio != null && !radio.trim().isEmpty()) {
            return radio.trim().toUpperCase(Locale.US);
        }
        return upper.length() > 6 ? upper.substring(0, 6) : upper;
    }

    /**
     * Send an outbound ATAK GeoChat to a MeshCore node as a native pubkey-to-pubkey DM
     * ({@code CMD_SEND_TXT_MSG}) instead of the proprietary {@code 0xFF01} AX.25 channel datagram
     * (which native MeshCore clients reject as "Unhandled"). Returns true if handled here.
     */
    private boolean trySendNativeMeshDm(String destUidHint, String roomHint, String message) {
        if (btManager == null || !btManager.isConnected()) {
            return false;
        }
        if (message == null || message.trim().isEmpty()) {
            return false;
        }
        String pubKey = extractMeshPublicKeyCandidate(destUidHint);
        if (pubKey.isEmpty()) {
            pubKey = extractMeshPublicKeyCandidate(roomHint);
        }
        if (pubKey.isEmpty()) {
            return false;
        }
        boolean ok = btManager.sendContactTextMessage(pubKey, message.trim());
        if (ok) {
            Log.d(TAG, "Outbound GeoChat sent as native MeshCore DM pubkeyPrefix="
                    + pubKey.substring(0, Math.min(12, pubKey.length())) + " len=" + message.trim().length());
        }
        return ok;
    }

    /**
     * Inject an inbound native MeshCore DM ({@code RESP_CONTACT_MSG}) into ATAK GeoChat. The native
     * frame carries only the sender's 6-byte pubkey prefix, so resolve the full
     * {@code MESHCORE-NODE-<pubkey>} contact when known; otherwise thread under the prefix.
     */
    public boolean injectInboundMeshDm(String senderPubKeyPrefixHex, String text) {
        if (cotBridge == null || text == null || text.trim().isEmpty()) {
            return false;
        }
        String prefix = senderPubKeyPrefixHex == null
                ? "" : senderPubKeyPrefixHex.trim().toUpperCase(Locale.US);
        if (prefix.isEmpty()) {
            return false;
        }
        String senderUid = resolveExistingMeshContactUidByPubKeyPrefix(prefix);
        if (senderUid == null || senderUid.trim().isEmpty()) {
            senderUid = MeshCoreContactHandler.ensureMeshInboundChatContact(prefix);
        }
        if (senderUid == null || senderUid.trim().isEmpty()) {
            Log.w(TAG, "Dropping inbound native MeshCore DM from unknown prefix=" + prefix
                    + " (no map marker or contact)");
            return false;
        }
        String fromCallsign = meshNodeDisplayForInboundPrefix(prefix, senderUid);
        cotBridge.registerBtechContactUid(senderUid);
        cotBridge.registerBtechContactId(fromCallsign, senderUid);
        int mid = (prefix + "|" + text.trim()).hashCode() & 0x7fffffff;
        if (mid == 0) {
            mid = 1;
        }
        // MeshCore retransmits until ACK'd — drop re-deliveries of the same wire message.
        String dedupKey = prefix + "|" + mid;
        long now = System.currentTimeMillis();
        Long expiry = recentlyInjectedMeshDmKeys.get(dedupKey);
        if (expiry != null && now < expiry) {
            Log.d(TAG, "Dropping duplicate inbound MeshCore DM mid=" + mid
                    + " from " + fromCallsign + " (dedup TTL active)");
            return false;
        }
        recentlyInjectedMeshDmKeys.put(dedupKey, now + MESH_DM_DEDUP_TTL_MS);
        // Prune expired entries to avoid unbounded growth.
        recentlyInjectedMeshDmKeys.entrySet().removeIf(e -> now >= e.getValue());
        Log.d(TAG, "Inbound native MeshCore DM from " + fromCallsign
                + " (" + senderUid + ") len=" + text.trim().length());
        return injectRadioMessage(fromCallsign, localCallsign, text.trim(), mid);
    }

    private String resolveExistingMeshContactUidByPubKeyPrefix(String prefixUpper) {
        try {
            java.util.List<Contact> all = Contacts.getInstance().getAllContacts();
            if (all != null) {
                for (Contact c : all) {
                    if (c == null) {
                        continue;
                    }
                    String uid = c.getUID();
                    if (uid == null) {
                        continue;
                    }
                    String u = uid.toUpperCase(Locale.US);
                    if ((u.startsWith(MESH_NODE_UID_PREFIX) || u.startsWith(MESH_RPTR_UID_PREFIX))
                            && extractMeshPublicKeyCandidate(u).startsWith(prefixUpper)) {
                        return uid;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String meshNodeDisplayForUid(String uid) {
        try {
            Contact c = Contacts.getInstance().getContactByUuid(uid);
            if (c != null && c.getName() != null && !c.getName().trim().isEmpty()) {
                return c.getName().trim();
            }
        } catch (Exception ignored) {
        }
        return uid;
    }

    private String meshNodeDisplayForInboundPrefix(String prefixUpper, String senderUid) {
        String byUid = meshNodeDisplayForUid(senderUid);
        if (byUid != null && !byUid.trim().isEmpty()) {
            String clean = byUid.trim();
            String upper = clean.toUpperCase(Locale.US);
            if (!upper.startsWith(MESH_NODE_UID_PREFIX) && !upper.startsWith(MESH_RPTR_UID_PREFIX)) {
                return clean;
            }
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                java.util.List<MapItem> items = mv.getRootGroup().deepFindItems("type", "a-f-G-U-C");
                if (items != null) {
                    for (MapItem item : items) {
                        if (item == null) {
                            continue;
                        }
                        String uid = item.getUID();
                        if (uid == null) {
                            continue;
                        }
                        String candidate = extractMeshPublicKeyCandidate(uid);
                        if (!candidate.isEmpty() && candidate.startsWith(prefixUpper)) {
                            String call = item.getMetaString("callsign", item.getTitle());
                            if (call != null) {
                                call = call.trim();
                                if (!call.isEmpty()) {
                                    return call;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return MESH_NODE_UID_PREFIX + prefixUpper;
    }

    /** True if the RF payload "chat room" equals this operator's callsign or its AX.25 form. */
    private boolean rfDestinationLooksLikeSelf(String room) {
        if (room == null || room.isEmpty() || localCallsign == null) {
            return false;
        }
        String r = room.trim();
        String loc = localCallsign.trim();
        if (loc.isEmpty()) {
            return false;
        }
        if (loc.equalsIgnoreCase(r)) {
            return true;
        }
        try {
            if (CallsignUtil.toRadioCallsign(loc).equalsIgnoreCase(r)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            com.atakmap.android.maps.PointMapItem selfMarker = mapView.getSelfMarker();
            if (selfMarker != null) {
                String m = selfMarker.getMetaString("callsign", null);
                if (m != null) {
                    if (m.trim().equalsIgnoreCase(r)) {
                        return true;
                    }
                    try {
                        if (CallsignUtil.toRadioCallsign(m.trim()).equalsIgnoreCase(r)) {
                            return true;
                        }
                    } catch (Exception ignored2) {
                    }
                    if (sixCharWireForm(m.trim()).equalsIgnoreCase(r)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // The chat packet carries the destination as the first 6 chars of the full callsign
        // (see MeshCorePacket.createChatPacket), so a full local callsign like "SMOKEY_15"
        // must still match the truncated wire room "SMOKEY".
        if (sixCharWireForm(loc).equalsIgnoreCase(r)) {
            return true;
        }
        if (r.startsWith(ANDROID_UID_PREFIX)) {
            String bare = r.substring(ANDROID_UID_PREFIX.length());
            if (loc.equalsIgnoreCase(bare)) {
                return true;
            }
            try {
                if (CallsignUtil.toRadioCallsign(loc).equalsIgnoreCase(bare)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * The RF chat transport truncates callsigns to a 6-char field
     * ({@link com.atakmaps.meshcore.plugin.protocol.MeshCorePacket#createChatPacket} uses
     * {@code (s + "      ").substring(0,6)}). This reproduces that wire form so a full callsign
     * (e.g. "SMOKEY_15") can be matched against the truncated value carried on the wire ("SMOKEY").
     */
    private static String sixCharWireForm(String s) {
        if (s == null) {
            return "";
        }
        return (s + "      ").substring(0, 6).trim();
    }

    /**
     * Map a 6-char / vowel-compressed RF callsign back to a known full callsign so the UI can
     * display (and thread) the full callsign instead of the truncated transport form. The full
     * callsign is already known from the peer's position/advert contact. Returns null if no match.
     */
    private String resolveFullCallsignForWireForm(String wireCallsign) {
        if (wireCallsign == null || wireCallsign.trim().isEmpty()) {
            return null;
        }
        String w = wireCallsign.trim();
        try {
            java.util.List<Contact> all = Contacts.getInstance().getAllContacts();
            if (all != null) {
                for (Contact c : all) {
                    if (!(c instanceof IndividualContact)) {
                        continue;
                    }
                    String name = c.getName();
                    if (name == null || name.trim().isEmpty()) {
                        continue;
                    }
                    String full = name.trim();
                    if (full.length() <= w.length()) {
                        continue;
                    }
                    try {
                        if (CallsignUtil.toRadioCallsign(full).equalsIgnoreCase(w)
                                || sixCharWireForm(full).equalsIgnoreCase(w)) {
                            return full;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean isLikelyGroupConversationThread(String threadIdRaw) {
        if (threadIdRaw == null) {
            return false;
        }
        String threadId = threadIdRaw.trim();
        if (threadId.isEmpty() || "All Chat Rooms".equalsIgnoreCase(threadId)) {
            return false;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(threadId);
            if (c instanceof com.atakmap.android.contact.GroupContact) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return threadId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    public static String syntheticAndroidUid(String callsign) {
        if (callsign == null) {
            return "";
        }
        String c = callsign.trim().toUpperCase(Locale.US);
        if (c.isEmpty()) {
            return "";
        }
        if (c.startsWith("ANDROID-")) {
            return c;
        }
        c = c.replaceAll("[^A-Z0-9\\-]", "");
        if (c.isEmpty()) {
            return "";
        }
        return "ANDROID-" + c;
    }

    /**
     * Ensure an ATAK contact exists for GeoChat routing, then return the contact UID.
     */
    /**
     * One ANDROID-* (or existing) UID per callsign — avoids duplicate JESTER/SMOKEY rows when
     * GeoChat uses device UID, link UID, and callsign forms interchangeably.
     */
    public static String resolveCanonicalPeerUid(String callsignRaw, String... candidateUids) {
        return ContactMergeUtil.resolveCanonicalPeerUid(callsignRaw, candidateUids);
    }

    public static String ensurePluginChatContact(String callsignRaw, String preferredUid) {
        String uid = preferredUid;
        if (uid == null || uid.trim().isEmpty()) {
            uid = syntheticAndroidUid(callsignRaw);
        } else {
            uid = uid.trim().toUpperCase(Locale.US);
        }
        if (uid.isEmpty()) {
            return "";
        }

        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        if (callsign.isEmpty() && uid.startsWith(ANDROID_UID_PREFIX)) {
            callsign = uid.substring(ANDROID_UID_PREFIX.length());
        }
        if (callsign.isEmpty()) {
            callsign = uid;
        }

        try {
            Contacts contacts = Contacts.getInstance();
            if (!callsign.isEmpty()) {
                Contact byCallsign = ContactMergeUtil.findContactByCallsignVariants(contacts, callsign);
                if (byCallsign instanceof IndividualContact) {
                    return byCallsign.getUID();
                }
            }
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                return uid;
            }
            if (existing != null) {
                Log.w(TAG, "Cannot ensure plugin chat contact; non-individual UID exists: " + uid);
                return uid;
            }

            MapItem item = null;
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                item = mv.getRootGroup().deepFindUID(uid);
            }

            IndividualContact c = new IndividualContact(callsign, uid, item);
            // All contacts in MeshcoreAtak communicate over BLE mesh — there is no WiFi/network
            // fallback path. MeshSendMessageConnector (radio icon) is the sole plugin connector;
            // PluginConnector is never added. GeoChatConnector is required for openConversation()
            // to find a valid stcp seed without AIOOBE, and our handler intercepts its
            // NotificationCount query (returning 0) so native GeoChat does not double-count.
            c.addConnector(new MeshSendMessageConnector());
            c.addConnector(new com.atakmap.android.chat.GeoChatConnector(
                    MeshCoreContactHandler.buildNativeConnectorSeed(callsign)));
            // Keep ATAK send-list compatibility without forcing CoT send path selection.
            c.addConnector(new IpConnector((String) null));

            if (mv != null) {
                try {
                    AtakPreferences prefs = new AtakPreferences(mv.getContext());
                    prefs.set("contact.connector.default." + c.getUID(),
                            com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set default connector for " + uid, e);
                }
            }
            contacts.addContact(c);
            return uid;
        } catch (Exception e) {
            Log.e(TAG, "ensurePluginChatContact failed callsign=" + callsignRaw
                    + " uid=" + uid, e);
            return "";
        }
    }

    /**
     * Ensures the exact destination UID exists in Contacts (for ATAK send lookup by UID).
     * Unlike {@link #ensurePluginChatContact(String, String)}, this does not canonicalize by
     * callsign first, because group-send uses exact toUID matching.
     */
    public static String ensurePluginChatContactExactUid(String callsignRaw, String preferredUid) {
        String uid = preferredUid != null ? preferredUid.trim().toUpperCase(Locale.US) : "";
        if (uid.isEmpty()) {
            return "";
        }
        // Mesh node / repeater UIDs are already canonical — just ensure the contact exists.
        if (uid.startsWith(MESH_NODE_UID_PREFIX) || uid.startsWith(MESH_RPTR_UID_PREFIX)) {
            MeshCoreContactHandler.ensureMeshInboundChatContact(
                    uid.substring(MESH_NODE_UID_PREFIX.length()));
            return uid;
        }
        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        if (callsign.isEmpty() && uid.startsWith(ANDROID_UID_PREFIX)) {
            callsign = uid.substring(ANDROID_UID_PREFIX.length());
        }
        if (callsign.isEmpty()) {
            callsign = uid;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                // Strip any stale PluginConnector left by an older build.
                try { ic.removeConnector(PluginConnector.CONNECTOR_TYPE); } catch (Exception ignored) {}
                if (ic.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                    ic.addConnector(new MeshSendMessageConnector());
                }
                if (ic.getConnector(com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE) == null) {
                    ic.addConnector(new com.atakmap.android.chat.GeoChatConnector(
                            MeshCoreContactHandler.buildNativeConnectorSeed(callsign)));
                }
                if (ic.getConnector(IpConnector.CONNECTOR_TYPE) == null) {
                    ic.addConnector(new IpConnector((String) null));
                }
                return uid;
            }
            if (existing != null) {
                return uid;
            }
            MapItem item = null;
            MapView mv = MapView.getMapView();
            if (mv != null && mv.getRootGroup() != null) {
                item = mv.getRootGroup().deepFindUID(uid);
            }
            IndividualContact c = new IndividualContact(callsign, uid, item);
            c.addConnector(new MeshSendMessageConnector());
            c.addConnector(new com.atakmap.android.chat.GeoChatConnector(
                    MeshCoreContactHandler.buildNativeConnectorSeed(callsign)));
            c.addConnector(new IpConnector((String) null));
            if (mv != null) {
                try {
                    AtakPreferences prefs = new AtakPreferences(mv.getContext());
                    prefs.set("contact.connector.default." + c.getUID(),
                            com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to set default connector for exact uid " + uid, e);
                }
            }
            contacts.addContact(c);
            return uid;
        } catch (Exception e) {
            Log.e(TAG, "ensurePluginChatContactExactUid failed callsign=" + callsignRaw
                    + " uid=" + preferredUid, e);
            return "";
        }
    }

    /**
     * Open ATAK's native GeoChat conversation for a known contact UID.
     */
    public static boolean openNativeChatConversation(String contactUid) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return false;
        }
        try {
            Contact c = Contacts.getInstance().getContactByUuid(contactUid.trim());
            if (c instanceof IndividualContact) {
                ChatManagerMapComponent.getInstance().openConversation((IndividualContact) c, true);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "openNativeChatConversation failed uid=" + contactUid, e);
        }
        return false;
    }

    /**
     * Register broadcast receiver to intercept outgoing ATAK chat
     * and relay to radio.
     */
    public void startOutgoingRelay() {
        chatReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleOutgoingChat(intent);
            }
        };

        // Register for GeoChat send events.
        // Some ATAK builds emit chat sends via SEND_MESSAGE (intent extras),
        // and/or via COT_PLACED (with CoT XML).
        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction("com.atakmap.android.maps.COT_PLACED");
        filter.addAction(ACTION_CHAT_SEND);
        filter.addAction(ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
        AtakBroadcast.getInstance().registerReceiver(chatReceiver, filter);

        // Track currently open GeoChat conversation to suppress unread badge increments when
        // the user is actively viewing that conversation.
        try {
            chatOpenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!"com.atakmap.android.OPEN_GEOCHAT".equals(intent.getAction())) return;
                    // ATAK (ChatManagerMapComponent) puts conversationId inside the
                    // parcelable "message" bundle, not as top-level intent extras — without
                    // this, opening GeoChat from the main chat menu never set openConversationId
                    // and the Contacts badge stayed stuck until Contacts pane opened chat.
                    String convo = null;
                    android.os.Bundle msgBundle = getOpenGeoChatMessageBundle(intent);
                    if (msgBundle != null) {
                        convo = msgBundle.getString("conversationId");
                        if (convo == null || convo.isEmpty()) {
                            convo = msgBundle.getString("chatroom");
                        }
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("conversationId");
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("chatroom");
                    }
                    if (convo == null || convo.isEmpty()) {
                        convo = intent.getStringExtra("id");
                    }
                    if (convo != null && !convo.isEmpty()) {
                        openConversationId = convo;
                        if (convo.startsWith("ANDROID-")) {
                            clearUnreadLocal(convo);
                            scheduleClearUnreadWhenGeoChatFragmentVisible(convo);
                        }
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter openF =
                    new AtakBroadcast.DocumentedIntentFilter();
            openF.addAction("com.atakmap.android.OPEN_GEOCHAT");
            AtakBroadcast.getInstance().registerReceiver(chatOpenReceiver, openF);
        } catch (Exception ignored) {
        }

        try {
            chatClosedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String a = intent.getAction();
                    if (!"com.atakmap.chat.chatroom_closed".equals(a)
                            && !"CHAT_ROOM_DROPDOWN_CLOSED".equals(a)) {
                        return;
                    }
                    openConversationId = null;
                }
            };
            AtakBroadcast.DocumentedIntentFilter closedF =
                    new AtakBroadcast.DocumentedIntentFilter();
            closedF.addAction("com.atakmap.chat.chatroom_closed");
            closedF.addAction("CHAT_ROOM_DROPDOWN_CLOSED");
            AtakBroadcast.getInstance().registerReceiver(chatClosedReceiver, closedF);
        } catch (Exception ignored) {
        }

        // Clear plugin badge when ATAK marks a message read (chat menu path, not only Contacts).
        try {
            chatMarkReadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    if (!"com.atakmap.chat.markmessageread".equals(intent.getAction())) return;
                    android.os.Bundle b = intent.getBundleExtra("chat_bundle");
                    if (b == null) return;
                    String convo = b.getString("conversationId");
                    if (convo == null || convo.isEmpty()) return;
                    if (convo.startsWith("ANDROID-")) {
                        clearUnreadLocal(convo);
                    }
                    Integer wireMid = extractWireMidFromMarkReadBundle(b);
                    String uid = convo.trim();
                    if (wireMid != null && cotBridge != null && cotBridge.isBtechContactUid(uid)) {
                        sendRadioChatAck(wireMid, MeshCorePacket.ACK_KIND_READ);
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter markRead =
                    new AtakBroadcast.DocumentedIntentFilter();
            markRead.addAction("com.atakmap.chat.markmessageread");
            AtakBroadcast.getInstance().registerReceiver(chatMarkReadReceiver, markRead);
        } catch (Exception ignored) {
        }

        try {
            contactsUnreadSyncListener = new Contacts.OnContactsChangedListener() {
                @Override
                public void onContactsSizeChange(Contacts contacts) {
                }

                @Override
                public void onContactChanged(String contactUid) {
                    if (contactUid == null || !contactUid.startsWith("ANDROID-")) {
                        return;
                    }
                    try {
                        Contact c = Contacts.getInstance().getContactByUuid(contactUid);
                        if (c == null) {
                            return;
                        }
                        int now = c.getUnreadCount();
                        Integer prev = lastAtakUnreadByUid.put(contactUid, now);
                        if (prev != null && prev > 0 && now == 0) {
                            Log.d(TAG, "ATAK native unread cleared for " + contactUid
                                    + " — clearing plugin Contacts badge");
                            clearUnreadLocal(contactUid);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "contacts unread sync", e);
                    }
                }
            };
            Contacts.getInstance().addListener(contactsUnreadSyncListener);
        } catch (Exception e) {
            Log.w(TAG, "Could not register Contacts unread sync listener", e);
        }

        registerAtakChatMessageListenerWhenReady(0);

        Log.d(TAG, "Outgoing chat relay started");
    }

    private void registerAtakChatMessageListenerWhenReady(final int attempt) {
        if (disposed || atakChatMessageListener != null) {
            return;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (disposed || atakChatMessageListener != null) {
                    return;
                }
                try {
                    ChatManagerMapComponent cmmc = ChatManagerMapComponent.getInstance();
                    if (cmmc != null) {
                        atakChatMessageListener =
                                new ChatManagerMapComponent.ChatMessageListener() {
                                    @Override
                                    public void chatMessageReceived(android.os.Bundle bundle) {
                                        maybeClearPluginUnreadWhenGeoChatUiShows(bundle);
                                    }
                                };
                        cmmc.addChatMessageListener(atakChatMessageListener);
                        Log.d(TAG, "Registered ChatManagerMapComponent.ChatMessageListener");
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ChatManagerMapComponent listener registration", e);
                }
                if (!disposed && attempt < 12 && mapView != null) {
                    mapView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            registerAtakChatMessageListenerWhenReady(attempt + 1);
                        }
                    }, 500L);
                }
            }
        };
        if (mapView != null) {
            mapView.post(r);
        } else {
            r.run();
        }
    }

    /**
     * When ATAK has finished routing a chat line, clear our Contacts badge if that
     * conversation's {@link com.atakmap.android.chat.ConversationFragment} is on-screen
     * (main GeoChat path — {@code Contact.getUnreadCount} often stays 0 for plugin UIDs).
     */
    private void maybeClearPluginUnreadWhenGeoChatUiShows(android.os.Bundle messageBundle) {
        if (disposed || messageBundle == null) return;
        String convo = messageBundle.getString("conversationId");
        if (convo == null || !convo.startsWith("ANDROID-")) {
            return;
        }
        postClearUnreadIfFragmentVisible(convo, 0);
        postClearUnreadIfFragmentVisible(convo, 120);
        postClearUnreadIfFragmentVisible(convo, 400);
    }

    private void postClearUnreadIfFragmentVisible(final String conversationId, long delayMs) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                if (disposed) {
                    return;
                }
                try {
                    if (isGeoChatConversationFragmentVisible(conversationId)) {
                        clearUnreadLocal(conversationId);
                        Log.d(TAG, "Plugin unread cleared (GeoChat fragment visible) " + conversationId);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "clear unread fragment check", e);
                }
            }
        };
        if (mapView != null) {
            mapView.postDelayed(r, delayMs);
        }
    }

    /** After OPEN_GEOCHAT, fragment creation can lag; poll briefly until it is resumed. */
    private void scheduleClearUnreadWhenGeoChatFragmentVisible(String conversationId) {
        postClearUnreadIfFragmentVisible(conversationId, 0);
        postClearUnreadIfFragmentVisible(conversationId, 80);
        postClearUnreadIfFragmentVisible(conversationId, 250);
        postClearUnreadIfFragmentVisible(conversationId, 700);
    }

    /**
     * ATAK keeps {@code ChatManagerMapComponent.fragmentMap} (conversationId → fragment).
     * Not a public API — reflect once per check, catch failures across ATAK versions.
     */
    private boolean isGeoChatConversationFragmentVisible(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return false;
        }
        try {
            Field f = ChatManagerMapComponent.class.getDeclaredField("fragmentMap");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Object> fm = (Map<String, Object>) f.get(null);
            if (fm == null) {
                return false;
            }
            Object o = fm.get(conversationId);
            if (!(o instanceof Fragment)) {
                return false;
            }
            Fragment fr = (Fragment) o;
            return fr.isResumed() && fr.isVisible();
        } catch (Throwable t) {
            return false;
        }
    }

    private void clearUnreadLocal(String conversationId) {
        if (conversationId == null) return;
        pendingUnreadConversationUids.remove(conversationId);
        MeshCoreContactHandler.clearUnread(conversationId);
        if (pendingUnreadConversationUids.isEmpty()) {
            unreadVisibilityPollRunning = false;
        }
        drainAndSendReadAcks(conversationId);
    }

    /**
     * Clear all plugin-side unread counts and GeoChat notification badges for every mesh contact
     * (MESHCORE-NODE-* and MESHCORE-RPTR-*). Called when the user taps "Clear All Mesh Contacts"
     * so the contacts icon badge resets even though GeoChat history still holds old entries.
     */
    public void clearUnreadForAllMeshContacts() {
        // Snapshot keys to avoid CME
        for (String uid : new java.util.ArrayList<>(pendingUnreadConversationUids)) {
            if (uid.startsWith(MESH_NODE_UID_PREFIX) || uid.startsWith(MESH_RPTR_UID_PREFIX)) {
                clearUnreadLocal(uid);
            }
        }
        // Also sweep MeshCoreContactHandler's own store for any UIDs we may have missed.
        MeshCoreContactHandler.clearAllMeshUnread();
        // Flush dedup cache so re-connects don't silently drop the first new message.
        recentlyInjectedMeshDmKeys.clear();
    }

    /** Record an inbound wire mid that should be READ-acked when the user opens this conversation. */
    private void addPendingReadAck(String conversationUid, int wireMid) {
        Set<Integer> existing = pendingReadAcksByConversation.get(conversationUid);
        if (existing == null) {
            Set<Integer> fresh = ConcurrentHashMap.newKeySet();
            existing = pendingReadAcksByConversation.putIfAbsent(conversationUid, fresh);
            if (existing == null) existing = fresh;
        }
        existing.add(wireMid);
    }

    /** Send READ ACKs over RF for all pending wire mids for this conversation, then clear them. */
    private void drainAndSendReadAcks(String conversationId) {
        if (conversationId == null || !conversationId.startsWith("ANDROID-")) return;
        if (cotBridge == null || !cotBridge.isBtechContactUid(conversationId)) return;
        Set<Integer> mids = pendingReadAcksByConversation.remove(conversationId);
        if (mids == null || mids.isEmpty()) return;
        for (Integer mid : mids) {
            Log.d(TAG, "Sending READ ACK mid=" + mid + " conversation=" + conversationId);
            sendRadioChatAck(mid, MeshCorePacket.ACK_KIND_READ);
        }
    }

    private void startUnreadVisibilityPollIfNeeded() {
        if (disposed || unreadVisibilityPollRunning || mapView == null) {
            return;
        }
        unreadVisibilityPollRunning = true;
        mapView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (disposed) {
                    unreadVisibilityPollRunning = false;
                    return;
                }
                if (pendingUnreadConversationUids.isEmpty()) {
                    unreadVisibilityPollRunning = false;
                    return;
                }
                try {
                    // Iterate snapshot to avoid concurrent modification churn.
                    for (String uid : pendingUnreadConversationUids.toArray(new String[0])) {
                        if (uid == null) continue;
                        if (isGeoChatConversationFragmentVisible(uid)) {
                            clearUnreadLocal(uid);
                            Log.d(TAG, "Plugin unread cleared (poll visible) " + uid);
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "unread visibility poll", e);
                }
                // Keep polling while there are pending unread threads.
                if (!pendingUnreadConversationUids.isEmpty()) {
                    mapView.postDelayed(this, 500L);
                } else {
                    unreadVisibilityPollRunning = false;
                }
            }
        }, 200L);
    }

    private static android.os.Bundle getMessageBundleExtra(Intent intent) {
        if (intent == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra("MESSAGE",
                        android.os.Bundle.class);
            }
            return intent.getParcelableExtra("MESSAGE");
        } catch (Exception e) {
            return null;
        }
    }

    /** Bundle from {@code com.atakmap.android.OPEN_GEOCHAT} (key {@code "message"}). */
    private static android.os.Bundle getOpenGeoChatMessageBundle(Intent intent) {
        if (intent == null) return null;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra("message", android.os.Bundle.class);
            }
            android.os.Parcelable p = intent.getParcelableExtra("message");
            return p instanceof android.os.Bundle ? (android.os.Bundle) p : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Outbound GeoChat for plugin-connector contacts: {@code MESSAGE} bundle from
     * ChatManager → GeoChatService (conversationId / message keys).
     *
     * @return true if handled (relay attempted for a matching BTECH destination)
     */
    boolean relayPluginGeoChatMessageBundle(android.os.Bundle b) {
        if (cotBridge == null || b == null) return false;

        String conversationId = b.getString("conversationId");
        String msg = b.getString("message");
        if (msg == null || msg.isEmpty()) {
            return false;
        }

        // ACTION_PLUGIN_CONTACT_GEOCHAT_SEND is emitted only for destinations carrying
        // our PluginConnector; do not gate on conversationId (group UUIDs are valid).
        if (GeoChatContactListHelper.bundleIsGroupContactSync(b)) {
            Log.i(TAG, "Plugin GeoChat bundle contains paths; relaying compact RF chat fallback");
        }

        String lineUid = extractGeoChatLineUidFromBundle(b);
        if (lineUid == null) {
            maybeLogPluginGeoChatBundleKeysMissingUid(b);
        }
        if (skipIfDuplicateOutboundGeoChatLine(lineUid)) {
            return true;
        }

        if (isLikelyGroupConversationThread(conversationId)) {
            String wrapped = wrapGatewayMessage("", conversationId, msg);
            Log.i(TAG, "Plugin GeoChat group bundle → compact gateway relay lineUid=" + lineUid);
            sendChatOverRadio(localCallsign, conversationId, wrapped, lineUid);
            return true;
        }

        String room = "All Chat Rooms";
        if (conversationId != null) {
            String cid = conversationId.trim();
            if (!cid.isEmpty() && !"All Chat Rooms".equalsIgnoreCase(cid)) {
                if (cid.startsWith("ANDROID-")) {
                    room = cid.substring("ANDROID-".length());
                } else {
                    room = cid;
                }
            }
        }

        Log.d(TAG, "Relay outgoing plugin-contact GeoChat to radio room=" + room
                + " lineUid=" + lineUid);
        String outbound = msg;
        if (isLikelyGroupConversationThread(conversationId)) {
            // TYPE_CHAT room is 6 bytes on-wire; preserve full group conversationId in payload.
            outbound = wrapGatewayMessage("", conversationId, msg);
        } else if (!"All Chat Rooms".equalsIgnoreCase(room) && !outbound.startsWith(GW_PREFIX)) {
            // Mesh-node DM: send as a native MeshCore contact message (pubkey DM), not the
            // proprietary 0xFF01 AX.25 datagram that native clients reject.
            if (trySendNativeMeshDm(conversationId, room, msg)) {
                return true;
            }
        }
        sendChatOverRadio(localCallsign, room, outbound, lineUid);
        return true;
    }

    /**
     * Handle an outgoing chat intent from ATAK.
     */
    private void handleOutgoingChat(Intent intent) {
        if (!relayOutgoing) return;
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (intent == null) return;

        try {
            final String action = intent.getAction();

            // Path plugin-contact GeoChat (ChatManager sends Intent(action=connectionString, MESSAGE=bundle)).
            if (ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(action)) {
                android.os.Bundle messageBundle = getMessageBundleExtra(intent);
                if (messageBundle != null) {
                    if (relayPluginGeoChatMessageBundle(messageBundle)) {
                        return;
                    }
                }
            }

            // Path A: chat send intent with explicit extras (preferred if present).
            if (ACTION_CHAT_SEND.equals(action)) {
                String message = intent.getStringExtra("message");
                String chatRoom = intent.getStringExtra("chatroom");
                String toUid = intent.getStringExtra("toUID");
                if (toUid == null) toUid = intent.getStringExtra("toUid");
                if (toUid == null) toUid = intent.getStringExtra("uid");
                if (chatRoom == null) chatRoom = intent.getStringExtra("room");
                if (toUid != null && !toUid.trim().isEmpty()) {
                    String trimmed = toUid.trim();
                    if (trimmed.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
                        String cs = trimmed.substring(ANDROID_UID_PREFIX.length());
                        ensurePluginChatContactExactUid(cs, trimmed);
                    }
                }

                // Log intent shape for field discovery (keep it short).
                try {
                    android.os.Bundle extras = intent.getExtras();
                    if (extras != null) {
                        StringBuilder keys = new StringBuilder();
                        for (String k : extras.keySet()) {
                            if (keys.length() > 0) keys.append(",");
                            keys.append(k);
                        }
                        Log.d(TAG, "SEND_MESSAGE extras keys: " + keys);
                    }
                } catch (Exception ignored) {
                }

                if (message == null || message.isEmpty()) {
                    // Some builds use "text" instead of "message"
                    message = intent.getStringExtra("text");
                }
                if (chatRoom == null || chatRoom.isEmpty()) {
                    chatRoom = "All Chat Rooms";
                }

                // Only relay when the destination is a plugin-created contact.
                // SEND_MESSAGE extras vary by build; ANDROID-VETTE1 must match callsign VETTE1.
                boolean shouldRelay =
                        cotBridge != null && cotBridge.isBtechOutboundChatDestination(toUid,
                        chatRoom);
                if (!shouldRelay && cotBridge != null) {
                    String[] fallback =
                            {"destUID", "destinationUID", "recipientUID",
                                    "recipient", "destination", "to",
                                    "toCallsign"};
                    for (String k : fallback) {
                        String v = intent.getStringExtra(k);
                        if (cotBridge.isBtechOutboundChatDestination(v, null)) {
                            shouldRelay = true;
                            break;
                        }
                    }
                }
                boolean gatewayRelay = false;
                if (!shouldRelay) {
                    // Final fallback: if the destination contact carries MeshSendMessageConnector,
                    // this is a mesh contact and must always be relayed over BLE mesh regardless
                    // of whether a GPS beacon has been received this session.
                    if (toUid != null && !toUid.trim().isEmpty()) {
                        try {
                            Contact dest = Contacts.getInstance().getContactByUuid(toUid.trim());
                            if (dest == null) dest = Contacts.getInstance().getContactByUuid(toUid.trim().toUpperCase(java.util.Locale.US));
                            if (dest instanceof IndividualContact) {
                                com.atakmap.android.contact.Connector mc =
                                        ((IndividualContact) dest).getConnector(
                                                MeshSendMessageConnector.CONNECTOR_TYPE);
                                if (mc != null && ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(mc.getConnectionString())) {
                                    shouldRelay = true;
                                    Log.d(TAG, "Relay fallback: MeshSendMessageConnector found on " + toUid);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
                if (!shouldRelay) {
                    gatewayRelay = isGatewayRelayEnabled();
                    if (!gatewayRelay) return;
                }
                if (message == null || message.isEmpty()) return;

                String lineUid = extractGeoChatLineUidFromIntent(intent);
                Log.d(TAG, "Relaying outgoing chat (SEND_MESSAGE) to radio: " + message
                        + " lineUid=" + lineUid);
                if (gatewayRelay) {
                    if (isLikelyGroupConversationThread(chatRoom)) {
                        // Group messages are already relayed as full b-t-f by CotBridge paths.
                        // Gateway compact chat here causes duplicate/random individual threads.
                        Log.d(TAG, "Skipping gateway compact relay for group thread " + chatRoom);
                        return;
                    }
                    String wrapped = wrapGatewayMessage(toUid, chatRoom, message);
                    String rfRoom = toUid != null && !toUid.isEmpty()
                            ? toUid
                            : chatRoom;
                    sendChatOverRadio(localCallsign, rfRoom, wrapped, lineUid);
                } else {
                    sendChatOverRadio(localCallsign, chatRoom, message, lineUid);
                }
                return;
            }

            String cotXml = intent.getStringExtra("xml");
            if (cotXml == null) return;

            CotEvent event = CotEvent.parse(cotXml);
            if (event == null) return;

            // Only relay GeoChat CoT messages when the destination is a plugin-created
            // (radio) contact. This prevents relaying all chat over radio when the user
            // chats with network contacts.
            if (cotBridge != null && !cotBridge.shouldRelayGeoChatToRadio(event)) {
                return;
            }

            // Extract message from remarks
            CotDetail detail = event.getDetail();
            if (detail == null) return;

            String message = null;
            String chatRoom = "All Chat Rooms";

            // Find remarks element for the message text
            CotDetail remarks = detail.getFirstChildByName(0, "remarks");
            if (remarks != null) {
                message = remarks.getInnerText();
            }

            // Find __chat element for room info
            CotDetail chat = detail.getFirstChildByName(0, "__chat");
            if (chat != null) {
                String room = chat.getAttribute("chatroom");
                if (room != null && !room.isEmpty()) {
                    chatRoom = room;
                }
            }

            if (message == null || message.isEmpty()) {
                return;
            }

        String lineUid = resolveOutboundGeoChatLineUid(event);
        if (skipIfDuplicateOutboundGeoChatLine(lineUid)) {
            return;
        }

        Log.d(TAG, "Relaying outgoing chat (COT intent) to radio: " + message
                + " lineUid=" + lineUid);
        relayOutboundGeoChatCotAsCompact(event);

        } catch (Exception e) {
            Log.e(TAG, "Error handling outgoing chat", e);
        }
    }

    /**
     * Outbound GeoChat to radio contacts: full slotted CoT for group/contact-list sync,
     * otherwise compact {@code TYPE_CHAT} for ACK correlation.
     */
    public void relayOutboundGeoChatCot(CotEvent event) {
        if (!relayOutgoing) {
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (event == null || !"b-t-f".equals(event.getType())) {
            return;
        }
        if (cotBridge == null || !cotBridge.shouldRelayGeoChatToRadio(event)) {
            return;
        }

        if (GeoChatContactListHelper.requiresFullCotRelay(event)) {
            String lineUid = resolveOutboundGeoChatLineUid(event);
            Log.i(TAG, "Group/contact-list GeoChat → full CoT (brief stagger) uid=" + lineUid);
            cotBridge.scheduleSlottedGroupContactCotRelay(event);
            return;
        }

        relayOutboundGeoChatCotAsCompact(event);
    }

    /**
     * PreSend / CommsLogger path: one compact TYPE_CHAT per outbound b-t-f with a registered
     * wire id for RF ACK correlation (delivered / read ticks).
     */
    public void relayOutboundGeoChatCotAsCompact(CotEvent event) {
        if (!relayOutgoing) {
            return;
        }
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (event == null || !"b-t-f".equals(event.getType())) {
            return;
        }
        if (cotBridge == null || !cotBridge.shouldRelayGeoChatToRadio(event)) {
            return;
        }

        String lineUid = resolveOutboundGeoChatLineUid(event);
        if (skipIfDuplicateOutboundGeoChatLine(lineUid)) {
            return;
        }

        CotDetail detail = event.getDetail();
        if (detail == null) {
            return;
        }

        String message = null;
        String chatRoom = "All Chat Rooms";

        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks != null) {
            message = remarks.getInnerText();
        }

        CotDetail chat = detail.getFirstChildByName(0, "__chat");
        if (chat == null) {
            chat = detail.getFirstChildByName(0, "chat");
        }
        if (chat != null) {
            String room = chat.getAttribute("chatroom");
            if (room != null && !room.isEmpty()) {
                chatRoom = room;
            }
        }

        if (message == null || message.isEmpty()) {
            return;
        }

        if (!"All Chat Rooms".equalsIgnoreCase(chatRoom)
                && !isLikelyGroupConversationThread(chatRoom)
                && !message.startsWith(GW_PREFIX)) {
            // Mesh-node DM: send as a native MeshCore contact message (pubkey DM), not the
            // proprietary 0xFF01 AX.25 datagram that native clients reject.
            if (trySendNativeMeshDm(chatRoom, chatRoom, message)) {
                return;
            }
        }

        Log.d(TAG, "Relay outbound GeoChat (compact PreSend/CommsLogger) room=" + chatRoom
                + " lineUid=" + lineUid);
        sendChatOverRadio(localCallsign, chatRoom, message, lineUid);
    }

    /**
     * Send a chat message over the radio link.
     */
    public void sendChatOverRadio(String sender, String room, String message) {
        sendChatOverRadio(sender, room, message, (String) null);
    }

    /**
     * @param originatingGeoChatLine when non-null (e.g. from {@code COT_PLACED}), associates the
     *                                 wire {@code messageId} with this GeoChat line UID for RF receipts.
     */
    public void sendChatOverRadio(String sender, String room, String message,
                                  CotEvent originatingGeoChatLine) {
        sendChatOverRadio(sender, room, message,
                originatingGeoChatLine == null ? null
                        : resolveOutboundGeoChatLineUid(originatingGeoChatLine));
    }

    /**
     * @param geoChatLineUidOrNull GeoChat line UID ({@code GeoChat....}); when set, RF ACKs can
     *                             update delivered/read state for that line.
     */
    public void sendChatOverRadio(String sender, String room, String message,
                                  String geoChatLineUidOrNull) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Not connected — cannot send chat");
            return;
        }

        try {
            int wireMid = MeshCorePacket.allocateChatWireMessageId();
            if (geoChatLineUidOrNull != null && geoChatLineUidOrNull.startsWith("GeoChat.")) {
                outboundWireMidToLocalLineUid.put(wireMid, geoChatLineUidOrNull.trim());
                trimOutboundAckMap();
            }

            MeshCorePacket packet = MeshCorePacket.createChatPacket(
                    com.atakmaps.meshcore.plugin.util.CallsignUtil.toRadioCallsign(sender),
                    room, wireMid, message);

            byte[] packetBytes = packet.encode();
            // Encrypt if enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encryption failed — aborting chat send");
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(
                    localCallsign, 0, packetBytes);
            byte[] ax25 = frame.encode();

            Log.d(TAG, "Sending chat over radio: " + ax25.length + " bytes");
            btManager.sendKissFrame(ax25);

            // Register for retry watchdog — cancelled when DELIVERED ACK arrives.
            PendingOutboundChat pending =
                    new PendingOutboundChat(wireMid, sender, room, message, geoChatLineUidOrNull);
            pendingOutboundChats.put(wireMid, pending);
            Log.d(TAG, "Outbound pending registered mid=" + wireMid + " room=" + room);
            scheduleRetryCheck(wireMid);
        } catch (Exception e) {
            Log.e(TAG, "Error sending chat over radio", e);
        }
    }

    /**
     * Re-apply {@link MeshSendMessageConnector} as the default connector for an ATAK peer
     * contact after {@code GeoChatService.onCotEvent} — which can overwrite the pref with its
     * own routing choice, reverting the icon to the generic plug.
     */
    public static void repairAtakPeerConnectorDefault(String uid) {
        if (uid == null || uid.trim().isEmpty()) return;
        try {
            String u = uid.trim();
            Contacts contacts = Contacts.getInstance();
            Contact c = contacts.getContactByUuid(u);
            if (!(c instanceof IndividualContact)) return;
            IndividualContact ic = (IndividualContact) c;
            if (ic.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                ic.addConnector(new MeshSendMessageConnector());
            }
            MapView mv = MapView.getMapView();
            if (mv == null) return;
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            prefs.set("contact.connector.default." + u, com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
            prefs.set("contact.connector.default." + u.toUpperCase(java.util.Locale.US),
                    com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
            prefs.set("contact.connector.default." + u.toLowerCase(java.util.Locale.US),
                    com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
            // Do NOT call ic.dispatchChangeEvent() here — doing so after GeoChatService has
            // just inserted a new message causes ATAK to reload the conversation and add the
            // incoming message a second time, producing duplicate notifications.
        } catch (Exception ignored) {
        }
    }

    /**
     * Build a minimal stcp NetConnectString seed for a GeoChatConnector.
     * This gives ATAK enough address info to render the chat bubble icon and thread
     * conversations correctly for ANDROID-* (WiFi-reachable ATAK) contacts.
     */
    private static NetConnectString buildGeoChatSeed(String callsign) {
        try {
            NetConnectString ncs = new NetConnectString("stcp", "127.0.0.1", 4242);
            String cs = callsign != null ? callsign.trim().toUpperCase(java.util.Locale.US) : "";
            if (!cs.isEmpty()) {
                ncs.setCallsign(cs);
            }
            return ncs;
        } catch (Exception e) {
            return new NetConnectString("stcp", "127.0.0.1", 4242);
        }
    }

    /**
     * Wraps an outbound GeoChat message with routing context for gateway relay.
     * Format: {@code __UVGW__|wireDest|displayCallsign|lineUid|message}
     */
    private static String wrapGatewayMessage(String wireDest, String displayCallsign,
                                             String lineUid, String message) {
        String wire = wireDest != null ? wireDest.trim() : "";
        String display = displayCallsign != null ? displayCallsign.trim() : "";
        String line = lineUid != null ? lineUid.trim() : "";
        return GW_PREFIX + wire + "|" + display + "|" + line + "|" + message;
    }

    /** Legacy 3-field overload; lineUid is left empty. */
    private static String wrapGatewayMessage(String wireDest, String displayCallsign,
                                             String message) {
        return wrapGatewayMessage(wireDest, displayCallsign, null, message);
    }

    private static GatewayWrapped parseGatewayWrappedMessage(String message) {
        if (message == null || !message.startsWith(GW_PREFIX)) {
            return null;
        }
        String rest = message.substring(GW_PREFIX.length());
        int p1 = rest.indexOf('|');
        if (p1 < 0) return null;
        int p2 = rest.indexOf('|', p1 + 1);
        if (p2 < 0) return null;
        String wireDest = rest.substring(0, p1).trim();
        String afterWire = rest.substring(p1 + 1);
        // Detect 4-field format (wireDest|displayCallsign|lineUid|message)
        // vs legacy 3-field (toUid|chatRoom|message).
        int p3 = afterWire.indexOf('|', p2 - p1);
        String displayCallsign;
        String lineUid = "";
        String body;
        if (p3 >= 0) {
            // 4-field: wireDest | displayCallsign | lineUid | message
            displayCallsign = afterWire.substring(0, p3 - p1 - 1).trim();
            String afterDisplay = afterWire.substring(p3 - p1);
            int p4 = afterDisplay.indexOf('|');
            if (p4 >= 0) {
                lineUid = afterDisplay.substring(0, p4).trim();
                body = afterDisplay.substring(p4 + 1);
            } else {
                body = afterDisplay;
            }
        } else {
            // Legacy 3-field: map chatRoom to displayCallsign
            displayCallsign = afterWire.substring(0, p2 - p1 - 1).trim();
            body = rest.substring(p2 + 1);
        }
        if (body.isEmpty()) return null;
        return new GatewayWrapped(wireDest, displayCallsign, lineUid, body);
    }

    private boolean isGatewayRelayEnabled() {
        try {
            return SettingsFragment.isSaRelayEnabled(pluginContext)
                    && SettingsFragment.isRfToTakUplinkEnabled(pluginContext);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static final class GatewayWrapped {
        final String wireDest;
        final String displayCallsign;
        final String lineUid;
        final String message;

        GatewayWrapped(String wireDest, String displayCallsign, String lineUid, String message) {
            this.wireDest = wireDest;
            this.displayCallsign = displayCallsign;
            this.lineUid = lineUid;
            this.message = message;
        }
    }

    /**
     * Wait for follow-up RF traffic (sender CoT relay, OPENRL guard, etc.) to clear
     * before keying a compact chat ACK — reduces mesh drops from on-channel collisions.
     */
    private static final long RF_CHAT_ACK_DELIVERED_DELAY_MS = 5000L;
    private static final long RF_CHAT_ACK_READ_DELAY_MS = 5000L;

    /**
     * Notify peer over RF that their chat frame was received (GeoChat delivered/read).
     */
    public void sendRadioChatAck(int wireMessageId, byte ackKind) {
        if (!relayOutgoing || btManager == null || !btManager.isConnected()) {
            return;
        }
        long delayMs = ackKind == MeshCorePacket.ACK_KIND_READ
                ? RF_CHAT_ACK_READ_DELAY_MS
                : RF_CHAT_ACK_DELIVERED_DELAY_MS;
        Log.d(TAG, "Scheduling radio chat ACK kind=" + ackKind + " mid=" + wireMessageId
                + " in " + (delayMs / 1000) + "s");
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.postDelayed(() -> sendRadioChatAckWhenReady(wireMessageId, ackKind, 0), delayMs);
    }

    private void sendRadioChatAckWhenReady(int wireMessageId, byte ackKind, int deferAttempt) {
        if (disposed || !relayOutgoing || btManager == null || !btManager.isConnected()) {
            return;
        }
        if (com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator.get().shouldDeferRfChatAck()) {
            if (deferAttempt < 24) {
                android.os.Handler h = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                h.postDelayed(() -> sendRadioChatAckWhenReady(wireMessageId, ackKind,
                                deferAttempt + 1),
                        400L);
            } else {
                Log.w(TAG, "Chat ACK deferred too long; dropping mid=" + wireMessageId);
            }
            return;
        }
        transmitRadioChatAckNow(wireMessageId, ackKind);
    }

    private void transmitRadioChatAckNow(int wireMessageId, byte ackKind) {
        try {
            MeshCorePacket packet =
                    MeshCorePacket.createChatAckPacket(wireMessageId, ackKind);
            byte[] packetBytes = packet.encode();
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.w(TAG, "Chat ACK encrypt failed mid=" + wireMessageId);
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(
                    localCallsign, 0, packetBytes);
            btManager.sendKissFrame(frame.encode());
            Log.d(TAG, "Sent radio chat ACK kind=" + ackKind + " mid=" + wireMessageId);
        } catch (Exception e) {
            Log.e(TAG, "sendRadioChatAck failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Outbound retry helpers
    // -------------------------------------------------------------------------

    private void scheduleRetryCheck(int wireMid) {
        if (retryExecutor.isShutdown()) {
            Log.w(TAG, "Retry executor shut down — cannot schedule retry for mid=" + wireMid);
            return;
        }
        long intervalMs = SettingsFragment.getRetryIntervalMs(pluginContext);
        Log.d(TAG, "Retry watchdog scheduled mid=" + wireMid + " in " + (intervalMs / 1000) + "s");
        retryExecutor.schedule(() -> onRetryTimer(wireMid), intervalMs, TimeUnit.MILLISECONDS);
    }

    private void onRetryTimer(int wireMid) {
        try {
            if (disposed) return;
            PendingOutboundChat pending = pendingOutboundChats.get(wireMid);
            if (pending == null) {
                Log.d(TAG, "Retry timer fired mid=" + wireMid + " — already ACK'd, nothing to do");
                return;
            }
            int maxRetries = SettingsFragment.getMaxChatRetries(pluginContext);
            if (pending.retryCount < maxRetries) {
                pending.retryCount++;
                Log.d(TAG, "No DELIVERED ACK for mid=" + wireMid
                        + " — retransmitting (attempt " + pending.retryCount + "/" + maxRetries + ")");
                retransmitChat(wireMid, pending);
                scheduleRetryCheck(wireMid);
            } else {
                Log.w(TAG, "Retry limit reached mid=" + wireMid + " after " + pending.retryCount
                        + " attempts — declaring failure");
                pendingOutboundChats.remove(wireMid);
                outboundWireMidToLocalLineUid.remove(wireMid);
                notifyDeliveryFailed(wireMid, pending);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in retry timer for mid=" + wireMid, e);
        }
    }

    private void retransmitChat(int wireMid, PendingOutboundChat pending) {
        if (btManager == null || !btManager.isConnected()) {
            Log.w(TAG, "Retry skipped — not connected mid=" + wireMid);
            return;
        }
        try {
            MeshCorePacket packet = MeshCorePacket.createChatPacket(
                    CallsignUtil.toRadioCallsign(pending.sender),
                    pending.room, wireMid, pending.message);
            byte[] packetBytes = packet.encode();
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Retry encrypt failed mid=" + wireMid);
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(localCallsign, 0, packetBytes);
            btManager.sendKissFrame(frame.encode());
        } catch (Exception e) {
            Log.e(TAG, "Retry transmit failed mid=" + wireMid, e);
        }
    }

    private void notifyDeliveryFailed(int wireMid, PendingOutboundChat pending) {
        Log.w(TAG, "Delivery failed after " + pending.retryCount + " retries mid=" + wireMid
                + " room=" + pending.room);
        String peer = pending.room;
        if (peer.startsWith("ANDROID-")) {
            peer = peer.substring("ANDROID-".length());
        }
        final String peerKey = peer.trim().toUpperCase();
        // Stash so we can auto-resend when the peer comes back online.
        failedOutboundChatsByPeer
                .computeIfAbsent(peerKey, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(pending);

        final String peerDisplay = peer;
        final int retriesMade = pending.retryCount;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                // AlertDialog — stays on screen until user taps OK.
                android.app.AlertDialog.Builder builder =
                        new android.app.AlertDialog.Builder(
                                com.atakmap.android.maps.MapView.getMapView().getContext());
                builder.setTitle("Message Not Delivered");
                builder.setMessage("Message to " + peerDisplay
                        + " will be delivered when user is rediscovered.");
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setPositiveButton("OK", null);
                builder.setCancelable(false);
                builder.show();
            } catch (Exception e) {
                Log.e(TAG, "AlertDialog for delivery failure failed", e);
                // Fallback toast if dialog can't be shown.
                try {
                    android.widget.Toast.makeText(pluginContext,
                            "Message to " + peerDisplay + " will be delivered when user is rediscovered.",
                            android.widget.Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {}
            }
            try {
                // Persistent system notification in the shade as a record.
                int notifyId = ("meshcore_fail_" + wireMid).hashCode() & 0x7FFFFFFF;
                NotificationUtil.getInstance().postNotification(
                        notifyId,
                        NotificationUtil.RED,
                        "Message Not Delivered",
                        "MeshCore",
                        "Message to " + peerDisplay + " will be delivered when user is rediscovered.");
            } catch (Exception e) {
                Log.e(TAG, "Failed to post delivery failure notification", e);
            }
        });
    }

    /**
     * Apply RF GeoChat ACK on this device (updates sent-line ticks via ATAK receipts).
     */
    public void handleIncomingRadioChatAck(int wireMessageId, byte kind) {
        String lineUid = outboundWireMidToLocalLineUid.get(wireMessageId);
        if (lineUid == null) {
            Log.d(TAG, "RF chat ACK mid=" + wireMessageId + " kind=" + kind
                    + " — no outbound mapping");
            return;
        }
        if (cotBridge == null) {
            return;
        }
        if (kind == MeshCorePacket.ACK_KIND_DELIVERED) {
            // Cancel retry watchdog — message reached the recipient.
            PendingOutboundChat removed = pendingOutboundChats.remove(wireMessageId);
            if (removed != null) {
                Log.d(TAG, "DELIVERED ACK cancelled retry watchdog mid=" + wireMessageId);
            }
            cotBridge.injectGeoChatReceipt(lineUid, false);
        } else if (kind == MeshCorePacket.ACK_KIND_READ) {
            cotBridge.injectGeoChatReceipt(lineUid, true);
        }
    }

    private static String resolveOutboundGeoChatLineUid(CotEvent event) {
        if (event == null) {
            return null;
        }
        String u = event.getUID();
        if (u != null && u.startsWith("GeoChat.")) {
            return u.trim();
        }
        try {
            CotDetail d = event.getDetail();
            if (d == null) {
                return null;
            }
            CotDetail chat = d.getFirstChildByName(0, "__chat");
            if (chat == null) {
                chat = d.getFirstChildByName(0, "chat");
            }
            if (chat != null) {
                String mid = chat.getAttribute("messageId");
                if (mid != null && mid.startsWith("GeoChat.")) {
                    return mid.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String extractGeoChatLineUidFromBundle(android.os.Bundle b) {
        if (b == null) {
            return null;
        }
        String[] keys = {"messageId", "MessageId", "chatMessageUid", "lineUid", "chatLineUid",
                "cotUid", "cotUID", "geoChatUid", "GeoChatUid", "cotEventUid", "eventUid", "uid"};
        for (String k : keys) {
            String v = b.getString(k);
            if (v != null && v.startsWith("GeoChat.")) {
                return v.trim();
            }
        }
        String xml = b.getString("xml");
        if (xml == null) {
            xml = b.getString("cotXml");
        }
        if (xml == null) {
            xml = b.getString("cot");
        }
        if (xml != null && !xml.isEmpty()) {
            try {
                CotEvent e = CotEvent.parse(xml);
                return resolveOutboundGeoChatLineUid(e);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String extractGeoChatLineUidFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String[] extraKeys = {"messageId", "MessageId", "chatMessageUid", "lineUid", "chatLineUid",
                "cotUid", "cotUID", "geoChatUid", "cotEventUid", "eventUid", "uid"};
        for (String k : extraKeys) {
            String v = intent.getStringExtra(k);
            if (v != null && v.startsWith("GeoChat.")) {
                return v.trim();
            }
        }
        String xml = intent.getStringExtra("xml");
        if (xml != null && !xml.isEmpty()) {
            try {
                CotEvent e = CotEvent.parse(xml);
                String u = resolveOutboundGeoChatLineUid(e);
                if (u != null) {
                    return u;
                }
            } catch (Exception ignored) {
            }
        }
        return extractGeoChatLineUidFromBundle(getMessageBundleExtra(intent));
    }

    /** @return true if this relay should be skipped (already sent for the same line recently). */
    private boolean skipIfDuplicateOutboundGeoChatLine(String lineUid) {
        if (lineUid == null || !lineUid.startsWith("GeoChat.")) {
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (outboundGeoChatDedupeLock) {
            if (lineUid.equals(lastOutboundGeoChatDedupeUid)
                    && (now - lastOutboundGeoChatDedupeMs) < 3000L) {
                Log.d(TAG, "Skip duplicate outbound GeoChat relay " + lineUid);
                return true;
            }
            lastOutboundGeoChatDedupeUid = lineUid;
            lastOutboundGeoChatDedupeMs = now;
            return false;
        }
    }

    private void maybeLogPluginGeoChatBundleKeysMissingUid(android.os.Bundle b) {
        if (b == null || loggedPluginChatBundleKeysMissingUid) {
            return;
        }
        loggedPluginChatBundleKeysMissingUid = true;
        try {
            StringBuilder keys = new StringBuilder();
            for (String k : b.keySet()) {
                if (keys.length() > 0) {
                    keys.append(",");
                }
                keys.append(k);
            }
            Log.d(TAG, "PLUGIN MESSAGE bundle (no GeoChat.* uid) keys: " + keys);
        } catch (Exception ignored) {
        }
    }

    private void trimOutboundAckMap() {
        while (outboundWireMidToLocalLineUid.size() > MAX_OUTBOUND_ACK_ENTRIES) {
            Iterator<Integer> it = outboundWireMidToLocalLineUid.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            outboundWireMidToLocalLineUid.remove(it.next());
        }
    }

    /**
     * Recover wire chat {@code messageId} embedded in {@link CotBridge} inbound GeoChat UIDs.
     */
    static Integer recoverWireMidFromGeoChatUid(String geoChatUid) {
        if (geoChatUid == null || !geoChatUid.startsWith("GeoChat.")) {
            return null;
        }
        int lastDot = geoChatUid.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= geoChatUid.length() - 1) {
            return null;
        }
        try {
            long uniq = Long.parseLong(geoChatUid.substring(lastDot + 1));
            long wireUnsig = (uniq >>> 32) & 0xffffffffL;
            if (wireUnsig == 0L) {
                return null;
            }
            return (int) wireUnsig;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer extractWireMidFromMarkReadBundle(android.os.Bundle b) {
        if (b == null) {
            return null;
        }
        String[] keys = {"messageId", "chatMessageUid", "cotUid", "chatUid", "uid"};
        for (String k : keys) {
            Integer mid = recoverWireMidFromGeoChatUid(b.getString(k));
            if (mid != null) {
                return mid;
            }
        }
        return null;
    }

    /**
     * Called whenever a beacon (GPS position) or ping is received from a radio peer.
     *
     * Two effects:
     *  1. Any pending (unacknowledged, in-retry) messages for that peer are sent immediately
     *     and their retry counter is reset, giving the message a fresh set of attempts now
     *     that the peer is known to be reachable.
     *  2. Any messages that previously exhausted all retries (and showed the "will be delivered
     *     when user is rediscovered" dialog) are re-queued and sent now.
     *
     * @param callsign  Bare radio callsign (no "ANDROID-" prefix), as received from the wire.
     */
    public void onPeerActivity(String callsign) {
        if (callsign == null || callsign.isEmpty()) return;
        final String key = callsign.trim().toUpperCase();

        // 1. Pending in-retry messages → send immediately and reset retry counter.
        for (Map.Entry<Integer, PendingOutboundChat> entry : pendingOutboundChats.entrySet()) {
            PendingOutboundChat pending = entry.getValue();
            String roomKey = pending.room;
            if (roomKey.startsWith("ANDROID-")) roomKey = roomKey.substring("ANDROID-".length());
            if (!key.equals(roomKey.trim().toUpperCase())) continue;

            Log.d(TAG, "Peer activity for " + key
                    + " — sending pending mid=" + entry.getKey() + " immediately");
            pending.retryCount = 0;
            retransmitChat(entry.getKey(), pending);
            // Leave in pendingOutboundChats — ACK will remove it; watchdog retries if needed.
        }

        // 2. Previously-failed messages → resend as fresh transmissions.
        java.util.Queue<PendingOutboundChat> failed = failedOutboundChatsByPeer.remove(key);
        if (failed != null && !failed.isEmpty()) {
            Log.d(TAG, "Peer " + key + " rediscovered — resending " + failed.size() + " failed message(s)");
            for (PendingOutboundChat f : failed) {
                sendChatOverRadio(f.sender, f.room, f.message, f.geoChatLineUid);
            }
        }
    }

    /**
     * Clean up resources.
     */
    public void dispose() {
        disposed = true;
        retryExecutor.shutdownNow();
        pendingOutboundChats.clear();
        failedOutboundChatsByPeer.clear();
        outboundWireMidToLocalLineUid.clear();
        pendingReadAcksByConversation.clear();
        pendingUnreadConversationUids.clear();
        recentlyInjectedMeshDmKeys.clear();
        unreadVisibilityPollRunning = false;
        if (chatReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat receiver", e);
            }
            chatReceiver = null;
        }
        if (chatMarkReadReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatMarkReadReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering mark-read receiver", e);
            }
            chatMarkReadReceiver = null;
        }
        if (chatOpenReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatOpenReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering open-chat receiver", e);
            }
            chatOpenReceiver = null;
        }
        if (chatClosedReceiver != null) {
            try {
                AtakBroadcast.getInstance()
                        .unregisterReceiver(chatClosedReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering chat-closed receiver", e);
            }
            chatClosedReceiver = null;
        }
        if (contactsUnreadSyncListener != null) {
            try {
                Contacts.getInstance().removeListener(contactsUnreadSyncListener);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering Contacts listener", e);
            }
            contactsUnreadSyncListener = null;
        }
        if (atakChatMessageListener != null) {
            try {
                ChatManagerMapComponent cmmc = ChatManagerMapComponent.getInstance();
                if (cmmc != null) {
                    cmmc.removeChatMessageListener(atakChatMessageListener);
                }
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering ChatManager listener", e);
            }
            atakChatMessageListener = null;
        }
        lastAtakUnreadByUid.clear();
        Log.d(TAG, "ChatBridge disposed");
    }
}
