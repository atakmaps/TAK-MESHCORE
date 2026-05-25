package com.atakmaps.meshcore.plugin.chat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.fragment.app.Fragment;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.android.preference.AtakPreferences;

import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.aprs.AprsMessageTransmitter;
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
import java.util.HashSet;
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
 *   - Packages as UVPro chat packets and sends to radio
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

    /** Runs after ATAK delivers chat to the UI (fragment may not exist yet on first callback). */
    private ChatManagerMapComponent.ChatMessageListener atakChatMessageListener;

    /**
     * Conversations with pending unread messages. Some ATAK paths open GeoChat without
     * broadcasting OPEN_GEOCHAT/markmessageread; we poll for a visible ConversationFragment
     * and clear unread as soon as the user is actually viewing the thread.
     */
    private final Set<String> pendingUnreadConversationUids = ConcurrentHashMap.newKeySet();
    private volatile boolean unreadVisibilityPollRunning;
    private static final Set<String> aprsConversationUids = ConcurrentHashMap.newKeySet();

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
     * @param radioPacketMessageId TYPE_CHAT payload id ({@code putInt}); 0 if unknown (APRS path).
     */
    public void injectRadioMessage(String fromCallsign, String toCallsign,
                                   String message, int radioPacketMessageId) {
        if (cotBridge == null) {
            Log.w(TAG, "CotBridge not set — cannot inject chat");
            return;
        }
        if (message == null || message.isEmpty()) return;

        // Gateway envelope: preserve full TAK destination identifiers across 6-byte RF room limits.
        String gatewayToUid = null;
        String gatewayRoom = null;
        GatewayWrapped gw = parseGatewayWrappedMessage(message);
        if (gw != null) {
            message = gw.message;
            gatewayToUid = gw.toUid;
            gatewayRoom = gw.chatRoom;
            Log.d(TAG, "Inbound RF gateway envelope toUid=" + gatewayToUid
                    + " room=" + gatewayRoom);
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
        if (gatewayToUid != null && !gatewayToUid.isEmpty()) {
            chatRoom = gatewayToUid;
        } else if (gatewayRoom != null && !gatewayRoom.isEmpty()) {
            chatRoom = gatewayRoom;
        }

        // Direct DM: thread id must be the *remote* peer's ANDROID-* UID. Packets include a
        // 6-byte "room" (RF destination) that is often THIS operator's callsign (e.g. JUNIOR).
        // Local operator is rarely in btechIdToUid MapView.getDeviceUid() is ANDROID-1729… not
        // ANDROID-JUNIOR — so resolveBtechUidForId("JUNIOR") is often NULL and we incorrectly
        // used sender ANDROID-VETTE as both ends (GeoChat.ANDROID-VETTE.ANDROID-VETTE).
        // Detect RF dest == configured local callsign (or its AX.25 form) FIRST, then use sender UID.
        if (!"All Chat Rooms".equalsIgnoreCase(chatRoom)) {
            String destUid = cotBridge.resolveBtechUidForId(chatRoom);
            String senderUid = cotBridge.resolveBtechUidForId(fromCallsign);
            // APRS senders are intentionally not registered as ATAK Contacts; synthesize an
            // ANDROID-* UID so GeoChat can thread and badge them like normal conversations.
            if ((senderUid == null || senderUid.isEmpty()) && fromCallsign != null) {
                senderUid = syntheticAndroidUid(fromCallsign);
            }
            if (senderUid != null && senderUid.startsWith(ANDROID_UID_PREFIX)) {
                ensurePluginChatContact(fromCallsign, senderUid);
                if (radioPacketMessageId == 0) {
                    markAprsContactUid(senderUid);
                }
                cotBridge.registerBtechContactUid(senderUid);
                if (fromCallsign != null && !fromCallsign.trim().isEmpty()) {
                    cotBridge.registerBtechContactId(fromCallsign, senderUid);
                }
                cotBridge.registerBtechContactId(
                        senderUid.substring(ANDROID_UID_PREFIX.length()), senderUid);
            }
            String selfUid = null;
            try {
                selfUid = MapView.getDeviceUid();
            } catch (Exception ignored) {
            }

            boolean peerThreadResolved = false;
            boolean keepGroupThread = isLikelyGroupConversationThread(chatRoom);
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
        if (chatRoom != null && chatRoom.startsWith("ANDROID-")) {
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

        cotBridge.injectChatCot(fromCallsign, message, chatRoom,
                radioPacketMessageId);
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
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
     * Ensure an ATAK contact exists for APRS GeoChat routing, then return the contact UID.
     */
    /**
     * One ANDROID-* (or existing) UID per callsign — avoids duplicate JESTER/SMOKEY rows when
     * GeoChat uses device UID, link UID, and callsign forms interchangeably.
     */
    public static String resolveCanonicalPeerUid(String callsignRaw, String... candidateUids) {
        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        try {
            Contacts contacts = Contacts.getInstance();
            if (!callsign.isEmpty()) {
                Contact byCallsign = contacts.getFirstContactWithCallsign(callsign);
                if (byCallsign != null && !byCallsign.getUID().isEmpty()) {
                    return byCallsign.getUID();
                }
                return syntheticAndroidUid(callsign);
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveCanonicalPeerUid callsign lookup failed", e);
        }
        if (candidateUids != null) {
            for (String raw : candidateUids) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String uid = raw.trim();
                try {
                    Contact c = Contacts.getInstance().getContactByUuid(uid);
                    if (c != null) {
                        return c.getUID();
                    }
                } catch (Exception ignored) {
                }
                if (uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
                    return uid.toUpperCase(Locale.US);
                }
            }
        }
        return "";
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
                Contact byCallsign = contacts.getFirstContactWithCallsign(callsign);
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
            c.addConnector(new PluginConnector(ACTION_PLUGIN_CONTACT_GEOCHAT_SEND));
            // Keep ATAK send-list compatibility without forcing CoT send path selection.
            c.addConnector(new IpConnector((String) null));

            if (mv != null) {
                try {
                    AtakPreferences prefs = new AtakPreferences(mv.getContext());
                    prefs.set("contact.connector.default." + c.getUID(),
                            PluginConnector.CONNECTOR_TYPE);
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
                com.atakmap.android.contact.Connector conn =
                        ic.getConnector(PluginConnector.CONNECTOR_TYPE);
                if (!(conn instanceof PluginConnector)
                        || !ACTION_PLUGIN_CONTACT_GEOCHAT_SEND.equals(conn.getConnectionString())) {
                    ic.addConnector(new PluginConnector(ACTION_PLUGIN_CONTACT_GEOCHAT_SEND));
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
            c.addConnector(new PluginConnector(ACTION_PLUGIN_CONTACT_GEOCHAT_SEND));
            c.addConnector(new IpConnector((String) null));
            if (mv != null) {
                try {
                    AtakPreferences prefs = new AtakPreferences(mv.getContext());
                    prefs.set("contact.connector.default." + c.getUID(),
                            PluginConnector.CONNECTOR_TYPE);
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

    public static void markAprsContactUid(String contactUid) {
        if (contactUid == null) {
            return;
        }
        String uid = contactUid.trim().toUpperCase(Locale.US);
        if (uid.isEmpty()) {
            return;
        }
        if (!uid.startsWith(ANDROID_UID_PREFIX)) {
            uid = ANDROID_UID_PREFIX + uid;
        }
        aprsConversationUids.add(uid);
    }

    private static boolean isAprsContactUid(String contactUid) {
        if (contactUid == null) {
            return false;
        }
        String uid = contactUid.trim().toUpperCase(Locale.US);
        if (uid.isEmpty()) {
            return false;
        }
        if (!uid.startsWith(ANDROID_UID_PREFIX)) {
            uid = ANDROID_UID_PREFIX + uid;
        }
        return aprsConversationUids.contains(uid);
    }

    /**
     * True when an inbound APRS message addressee targets this device/operator.
     * Accepts direct local callsign forms and APRS-configured callsign+SSID forms.
     */
    public boolean shouldAcceptAprsDestination(String toCallsignRaw) {
        String to = normalizeAprsDestination(toCallsignRaw);
        if (to.isEmpty()) {
            return false;
        }
        if ("ALL".equals(to) || to.startsWith("BLN")) {
            return true;
        }

        Set<String> accepted = new HashSet<>();
        addAprsDestinationVariants(accepted, localCallsign);
        try {
            if (mapView != null && mapView.getSelfMarker() != null) {
                addAprsDestinationVariants(accepted,
                        mapView.getSelfMarker().getMetaString("callsign", null));
            }
        } catch (Exception ignored) {
        }
        try {
            String aprsBase = SettingsFragment.getAprsCallsign(pluginContext);
            int aprsSsid = SettingsFragment.getAprsSsid(pluginContext);
            addAprsDestinationVariants(accepted, aprsBase);
            if (aprsBase != null && !aprsBase.trim().isEmpty() && aprsSsid > 0 && aprsSsid <= 15) {
                addAprsDestinationVariants(accepted, aprsBase.trim() + "-" + aprsSsid);
            }
        } catch (Exception ignored) {
        }
        return accepted.contains(to);
    }

    private static void addAprsDestinationVariants(Set<String> out, String raw) {
        String n = normalizeAprsDestination(raw);
        if (n.isEmpty()) {
            return;
        }
        out.add(n);
        try {
            String radio = com.atakmaps.meshcore.plugin.util.CallsignUtil.toRadioCallsign(n);
            String rn = normalizeAprsDestination(radio);
            if (!rn.isEmpty()) {
                out.add(rn);
            }
        } catch (Exception ignored) {
        }
        int dash = n.indexOf('-');
        if (dash > 0) {
            String base = normalizeAprsDestination(n.substring(0, dash));
            if (!base.isEmpty()) {
                out.add(base);
                try {
                    String radioBase = com.atakmaps.meshcore.plugin.util.CallsignUtil.toRadioCallsign(base);
                    String rb = normalizeAprsDestination(radioBase);
                    if (!rb.isEmpty()) {
                        out.add(rb);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String normalizeAprsDestination(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toUpperCase(Locale.US);
        if (s.isEmpty()) {
            return "";
        }
        if (s.startsWith(ANDROID_UID_PREFIX)) {
            s = s.substring(ANDROID_UID_PREFIX.length());
        }
        s = s.replaceAll("[^A-Z0-9\\-]", "");
        if (s.length() > 9) {
            s = s.substring(0, 9);
        }
        return s;
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

        if (isAprsContactUid(conversationId)) {
            if (!SettingsFragment.isValidAprsCallsign(SettingsFragment.getAprsCallsign(pluginContext))) {
                postAprsCallsignWarning();
                return true;
            }
            String to = AprsMessageTransmitter.normalizeAddressee(conversationId);
            if (to.isEmpty()) {
                Log.w(TAG, "APRS relay blocked (invalid chat destination): " + conversationId);
                return true;
            }
            boolean ok = AprsMessageTransmitter.sendMessage(pluginContext, btManager, to, msg);
            if (!ok) {
                Log.w(TAG, "APRS relay failed for chat destination: " + to);
            }
            return true;
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
        }
        sendChatOverRadio(localCallsign, room, outbound, lineUid);
        return true;
    }

    private void postAprsCallsignWarning() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    android.widget.Toast.makeText(pluginContext,
                            "Set a valid APRS callsign in Edit APRS Settings first.",
                            android.widget.Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.w(TAG, "Could not show APRS callsign warning", e);
        }
    }

    /**
     * For inbound APRS messages with message IDs ({@code {...}}), send {@code ackNN}.
     */
    public boolean sendAprsAckIfRequested(String toCallsignRaw, String messageIdRaw) {
        String id = messageIdRaw != null ? messageIdRaw.trim() : "";
        if (id.isEmpty()) {
            return false;
        }
        if (id.length() > 5) {
            id = id.substring(0, 5);
        }
        boolean ok = AprsMessageTransmitter.sendAcknowledgement(
                pluginContext, btManager, toCallsignRaw, id);
        if (ok) {
            Log.d(TAG, "Auto-sent APRS ack id=" + id + " to " + toCallsignRaw);
        } else {
            Log.d(TAG, "APRS ack not sent id=" + id + " to " + toCallsignRaw);
        }
        return ok;
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
            relayOutboundGeoChatCot(event);

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

        String lineUid = resolveOutboundGeoChatLineUid(event);
        if (skipIfDuplicateOutboundGeoChatLine(lineUid)) {
            return;
        }

        if (GeoChatContactListHelper.requiresFullCotRelay(event)) {
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

    private static String wrapGatewayMessage(String toUid, String chatRoom, String message) {
        String uid = toUid != null ? toUid.trim() : "";
        String room = chatRoom != null ? chatRoom.trim() : "";
        return GW_PREFIX + uid + "|" + room + "|" + message;
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
        String toUid = rest.substring(0, p1).trim();
        String room = rest.substring(p1 + 1, p2).trim();
        String body = rest.substring(p2 + 1);
        if (body.isEmpty()) return null;
        return new GatewayWrapped(toUid, room, body);
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
        final String toUid;
        final String chatRoom;
        final String message;

        GatewayWrapped(String toUid, String chatRoom, String message) {
            this.toUid = toUid;
            this.chatRoom = chatRoom;
            this.message = message;
        }
    }

    /**
     * Notify peer over RF that their chat frame was received (GeoChat delivered).
     */
    public void sendRadioChatAck(int wireMessageId, byte ackKind) {
        sendRadioChatAck(wireMessageId, ackKind, 0);
    }

    private void sendRadioChatAck(int wireMessageId, byte ackKind, int deferAttempt) {
        if (!relayOutgoing || btManager == null || !btManager.isConnected()) {
            return;
        }
        if (com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator.get().shouldDeferRfChatAck()) {
            if (deferAttempt < 24) {
                android.os.Handler h = new android.os.Handler(
                        android.os.Looper.getMainLooper());
                h.postDelayed(() -> sendRadioChatAck(wireMessageId, ackKind, deferAttempt + 1),
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
                int notifyId = ("uvpro_fail_" + wireMid).hashCode() & 0x7FFFFFFF;
                NotificationUtil.getInstance().postNotification(
                        notifyId,
                        NotificationUtil.RED,
                        "Message Not Delivered",
                        "UV-PRO",
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
