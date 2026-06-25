package com.atakmaps.meshcore.plugin;

import android.util.Log;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactConnectorManager;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmaps.meshcore.plugin.chat.ContactMergeUtil;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.contacts.MeshFavoriteConnector;
import com.atakmaps.meshcore.plugin.contacts.MeshRequestPositionConnector;
import com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector;
import com.atakmaps.meshcore.plugin.contacts.PositionOnlyConnector;
import com.atakmaps.meshcore.plugin.mesh.MeshNodeCachePolicy;
import com.atakmaps.meshcore.plugin.protocol.PositionRequester;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;

import android.content.Context;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MeshCoreContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    /** Must match ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND. */
    public static final String PLUGIN_GEOCHAT_ACTION =
            "com.atakmaps.meshcore.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";

    private final android.content.Context pluginContext;

    /**
     * Unread counter store for plugin contacts. ATAK queries this via
     * {@link #getFeature} with {@code ConnectorFeature.NotificationCount} to drive UI badges.
     *
     * NOTE: ATAK may query NotificationCount for multiple connectors for the same UID
     * (e.g. plugin connector + null/default). Return a count only for the plugin connector
     * address to avoid double-counting in the UI.
     */
    private static final int MAX_UNREAD_KEYS_PER_UID = 128;
    private static final Map<String, Set<String>> unreadKeysByUid = new ConcurrentHashMap<>();

    public MeshCoreContactHandler(android.content.Context pluginContext) {
        this.pluginContext = pluginContext;
    }

    public static void incrementUnreadOnce(String contactUid, int radioPacketMessageId,
                                           String messageText) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;

        String key;
        if (radioPacketMessageId != 0) {
            key = "mid:" + radioPacketMessageId;
        } else if (messageText != null && !messageText.isEmpty()) {
            key = "fp:" + Integer.toHexString(messageText.hashCode());
        } else {
            key = "t:" + System.currentTimeMillis();
        }

        Set<String> keys = unreadKeysByUid.computeIfAbsent(uid,
                k -> ConcurrentHashMap.newKeySet());
        keys.add(key);
        if (keys.size() > MAX_UNREAD_KEYS_PER_UID) {
            keys.clear();
            keys.add(key);
        }
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void clearUnread(String contactUid) {
        if (contactUid == null) return;
        String uid = contactUid.trim();
        if (uid.isEmpty()) return;
        unreadKeysByUid.remove(uid);
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    public static void clearAllUnread() {
        unreadKeysByUid.clear();
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    /** Clear unread counts only for MESHCORE-NODE-* and MESHCORE-RPTR-* UIDs. */
    public static void clearAllMeshUnread() {
        unreadKeysByUid.keySet().removeIf(uid ->
                uid.startsWith("MESHCORE-NODE-") || uid.startsWith("MESHCORE-RPTR-"));
        try {
            Contacts.getInstance().updateTotalUnreadCount();
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean isSupported(String type) {
        return FileSystemUtils.isEquals(type, PluginConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, GeoChatConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, MeshFavoriteConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, MeshSendMessageConnector.CONNECTOR_TYPE)
                || FileSystemUtils.isEquals(type, MeshRequestPositionConnector.CONNECTOR_TYPE);
    }

    @Override
    public boolean hasFeature(
            ContactConnectorManager.ConnectorFeature feature) {
        return true;
    }

    @Override
    public String getName() {
        return "MeshCore";
    }

    @Override
    public boolean handleContact(String connectorType, String contactUID,
            String connectorAddress) {

        Contact contact = Contacts.getInstance().getContactByUuid(contactUID);

        if (contact instanceof IndividualContact) {
            IndividualContact ic = (IndividualContact) contact;

            if (FileSystemUtils.isEquals(connectorType, MeshFavoriteConnector.CONNECTOR_TYPE)) {
                if (promoteMeshFavoriteContactByUid(ic.getUID(), ic.getName())) {
                    Toast.makeText(pluginContext,
                            "Favorited " + ic.getName(),
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }

            if (FileSystemUtils.isEquals(connectorType,
                    MeshRequestPositionConnector.CONNECTOR_TYPE)) {
                String uid = ic.getUID();
                if (uid != null && uid.startsWith(MESH_RPTR_UID_PREFIX)) {
                    Toast.makeText(pluginContext,
                            "Repeaters do not support position requests",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                String atakTarget = resolvePingTargetCallsign(ic);
                if (atakTarget == null || atakTarget.isEmpty()) {
                    Toast.makeText(pluginContext,
                            "Could not resolve contact callsign",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                boolean ok = PositionRequester.requestPosition(
                        pluginContext, uid, atakTarget);
                Toast.makeText(pluginContext,
                        ok ? "Ping sent to " + atakTarget
                                : "Ping failed (MeshCore not connected / WiFi/TAK unavailable)",
                        Toast.LENGTH_LONG).show();
                return true;
            }

            if (FileSystemUtils.isEquals(connectorType, GeoChatConnector.CONNECTOR_TYPE)
                    || FileSystemUtils.isEquals(connectorType, MeshSendMessageConnector.CONNECTOR_TYPE)) {
                // Clear plugin unread badge, send markmessageread to clear the native
                // GeoChatConnector badge, then open the DM conversation window directly.
                clearUnread(contactUID);
                try {
                    android.content.Intent markRead = new android.content.Intent(
                            "com.atakmap.chat.markmessageread");
                    markRead.putExtra("conversationId", contactUID);
                    com.atakmap.android.ipc.AtakBroadcast.getInstance().sendBroadcast(markRead);
                } catch (Exception ignored) {
                }
                ChatManagerMapComponent.getInstance().openConversation(ic, false);
                Log.i("BTRelay", "Contact selected for chat: " + contactUID);
                return true;
            }
        }

        return true;
    }

    @Override
    public Object getFeature(String connectorType,
            ContactConnectorManager.ConnectorFeature feature,
            String contactUID, String connectorAddress) {

        Log.i("MeshCore.Handler", "getFeature feature=" + feature
                + " uid=" + contactUID + " address=" + connectorAddress);

        if (feature == ContactConnectorManager.ConnectorFeature.NotificationCount) {
            // Gate on the connection STRING only (Darksteal pattern). ATAK calls this handler
            // for every supported connector type; returning 0 for all addresses except
            // PLUGIN_GEOCHAT_ACTION prevents the native GeoChatConnectorHandler from also
            // returning a count for the same contact (ATAK sums across handlers).
            // MeshSendMessageConnector.getConnectionString() == PLUGIN_GEOCHAT_ACTION, so it
            // is the sole badge source for MESHCORE-* contacts.
            //
            // For ANDROID-* contacts (ATAK peers via mesh), return 0 here so the native
            // GeoChatConnector (which ATAK manages automatically) is the sole badge source.
            // This prevents the two-badge "Geo Chat: 1 + Send Message: 1" double-count.
            if (connectorAddress == null || !PLUGIN_GEOCHAT_ACTION.equals(connectorAddress)) {
                Log.i("MeshCore.Handler", "NotificationCount uid=" + contactUID + " addr="
                        + connectorAddress + " -> 0 (non-plugin)");
                return 0;
            }
            String uid = contactUID != null ? contactUID.trim() : "";
            // For all mesh and ATAK contacts, let ATAK's native GeoChatConnector be the sole
            // badge source. Returning our own count here doubles the badge because ATAK's
            // built-in GeoChatConnectorHandler runs alongside this handler and also returns a
            // count for the same contact ("Geo Chat: 1 + Send Message: 1" = 2).
            // ATAK natively tracks unread via conversationId notification and clears it when
            // the user opens the conversation — no plugin-side counter needed.
            Log.i("MeshCore.Handler", "NotificationCount uid=" + contactUID
                    + " addr=" + connectorAddress + " -> 0 (native badge only)");
            return 0;
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "MeshCore Contact Handler";
    }

    /**
     * When a native MeshCore app sends a pubkey DM, ensure a GeoChat-capable contact exists
     * even if the operator has not favorited the node yet (map marker may exist from advert).
     */
    public static String ensureMeshInboundChatContact(String senderPubKeyPrefixHex) {
        if (senderPubKeyPrefixHex == null || senderPubKeyPrefixHex.trim().isEmpty()) {
            return "";
        }
        String prefix = senderPubKeyPrefixHex.trim().toUpperCase(Locale.US);
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            MapItem mapItem = findMapItemByPubKeyPrefix(mv, prefix);
            String uid = mapItem != null ? mapItem.getUID() : (MESH_NODE_UID_PREFIX + prefix);
            if (uid == null || uid.trim().isEmpty()) {
                return "";
            }
            uid = uid.trim();
            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);
            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                applyMeshInboundConnectors(ic);
                return uid;
            }
            // When no advert has been received, use the first 8 hex chars of the pubkey
            // prefix as the display name (e.g. "B5CA4888-MESH") so the contact is uniquely
            // identifiable rather than the generic "NODE-MESH" fallback.
            String nameHint = (mapItem == null && prefix.length() >= 8)
                    ? prefix.substring(0, 8) : null;
            String displayName = formatMeshFavoriteName(nameHint, uid, mapItem);
            IndividualContact created = new IndividualContact(
                    displayName,
                    uid,
                    mapItem,
                    buildNativeConnectorSeed(displayName));
            applyMeshInboundConnectors(created);
            contacts.addContact(created);
            contacts.updateTotalUnreadCount();
            Log.i("MeshCore.Handler", "Created inbound mesh chat contact " + displayName + " uid=" + uid);
            return uid;
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "ensureMeshInboundChatContact failed prefix=" + prefix, e);
            return "";
        }
    }

    private static MapItem findMapItemByPubKeyPrefix(com.atakmap.android.maps.MapView mv,
                                                     String prefixUpper) {
        if (mv == null || mv.getRootGroup() == null || prefixUpper == null || prefixUpper.isEmpty()) {
            return null;
        }
        try {
            java.util.List<MapItem> items = mv.getRootGroup().deepFindItems("type", "a-f-G-U-C");
            if (items == null) {
                return null;
            }
            for (MapItem item : items) {
                if (item == null) {
                    continue;
                }
                String uid = item.getUID();
                if (uid == null) {
                    continue;
                }
                String u = uid.toUpperCase(Locale.US);
                if ((u.startsWith(MESH_NODE_UID_PREFIX) || u.startsWith(MESH_RPTR_UID_PREFIX))
                        && u.contains(prefixUpper)) {
                    return item;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void applyMeshInboundConnectors(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        try {
            contact.removeConnector(com.atakmaps.meshcore.plugin.contacts.PositionOnlyConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        // PluginConnector is NOT added to inbound mesh contacts. Our getFeature(NotificationCount)
        // only returns non-zero for PluginConnector address queries. Without PluginConnector on
        // the contact, ATAK never calls our handler for a mesh contact's unread count — the single
        // notification comes exclusively from GeoChatConnector's native ATAK tracking instead.
        // Actively remove any PluginConnector left by a prior plugin build.
        try {
            contact.removeConnector(PluginConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        try {
            if (contact.getConnector(MeshFavoriteConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshFavoriteConnector());
            }
            if (contact.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshSendMessageConnector());
            }
            if (contact.getConnector(MeshRequestPositionConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshRequestPositionConnector());
            }
            // GeoChatConnector must be present for ATAK to honour the default-connector pref
            // and for openConversation() to find a valid stcp seed. It also provides the single
            // native unread badge (cleared automatically when the user opens the conversation).
            if (contact.getConnector(com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new com.atakmap.android.chat.GeoChatConnector(
                        buildNativeConnectorSeed(contact.getName())));
            }
            writeDefaultConnectorPref(contact.getUID(), GeoChatConnector.CONNECTOR_TYPE);
            // Do NOT call contact.dispatchChangeEvent() here — it is async and will fire
            // after the caller's geoChatService.onCotEvent() insert, causing a double-add
            // of the incoming message to ConversationListAdapter.
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "applyMeshInboundConnectors failed", e);
        }
    }

    public static boolean promoteMeshFavoriteContactByUid(String contactUid, String currentName) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return false;
        }
        try {
            final String uid = contactUid.trim();
            final Contacts contacts = Contacts.getInstance();
            final String favoriteName = formatMeshFavoriteName(currentName, uid);
            Contact existing = contacts.getContactByUuid(uid);
            MapItem item = existing instanceof IndividualContact
                    ? ((IndividualContact) existing).getMapItem()
                    : null;

            if (existing != null) {
                contacts.removeContact(existing);
            }
            IndividualContact favored = new IndividualContact(
                    favoriteName,
                    uid,
                    item,
                    buildNativeConnectorSeed(favoriteName));
            applyMeshContactConnectors(favored);
            contacts.addContact(favored);
            contacts.updateTotalUnreadCount();
            String pubKeyPrefix = pubKeyPrefixFromMeshUid(uid);
            if (pubKeyPrefix != null) {
                try {
                    com.atakmap.android.maps.MapView mv =
                            com.atakmap.android.maps.MapView.getMapView();
                    if (mv != null) {
                        markMeshMapCacheFavorite(mv.getContext(), pubKeyPrefix, true);
                    }
                } catch (Exception ignored) {
                }
            }
            return true;
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "promoteMeshFavoriteContact failed", e);
            return false;
        }
    }

    private static void applyMeshContactConnectors(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        try {
            contact.removeConnector(PositionOnlyConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        try {
            if (contact.getConnector(MeshFavoriteConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshFavoriteConnector());
            }
            if (contact.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshSendMessageConnector());
            }
            if (contact.getConnector(MeshRequestPositionConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshRequestPositionConnector());
            }
            if (contact.getConnector(GeoChatConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new GeoChatConnector(
                        buildNativeConnectorSeed(contact.getName())));
            }
            writeDefaultConnectorPref(contact.getUID(), GeoChatConnector.CONNECTOR_TYPE);
            // Do NOT call contact.dispatchChangeEvent() here — async reload causes double-add.
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "applyMeshContactConnectors failed", e);
        }
    }

    /**
     * Resolves the ATAK callsign for a directed ping to this specific contact.
     */
    public static String resolvePingTargetCallsign(IndividualContact contact) {
        if (contact == null) {
            return "";
        }
        String uid = contact.getUID();
        String contactName = contact.getName();

        CotBridge bridge = ContactMergeUtil.getMergeRoutingBridge();
        if (bridge != null && uid != null && !uid.trim().isEmpty()) {
            String registered = bridge.resolveRegisteredCallsignForUid(uid, contactName);
            if (registered != null && !registered.trim().isEmpty()) {
                return stripMeshSuffix(registered.trim());
            }
        }

        String fromName = stripMeshSuffix(contactName);
        if (!fromName.isEmpty()) {
            return fromName;
        }

        com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
        if (mv != null && mv.getRootGroup() != null && uid != null) {
            MapItem item = mv.getRootGroup().deepFindUID(uid);
            if (item != null) {
                String mapCall = item.getMetaString("callsign", item.getTitle());
                if (mapCall != null && !mapCall.trim().isEmpty()) {
                    return stripMeshSuffix(mapCall.trim());
                }
            }
        }

        String geoChatCallsign = geoChatCallsignFromContact(contact);
        if (!geoChatCallsign.isEmpty()) {
            return stripMeshSuffix(geoChatCallsign);
        }

        if (uid != null && uid.startsWith("ANDROID-")) {
            String bare = uid.substring("ANDROID-".length());
            if (!CotBridge.isOpaqueDeviceUid(bare)) {
                return stripMeshSuffix(bare);
            }
        }
        return "";
    }

    /**
     * Resolves a 6-character radio callsign for directed ping / position request.
     */
    public static String resolveRadioCallsignForContact(IndividualContact contact) {
        String atakTarget = resolvePingTargetCallsign(contact);
        if (atakTarget.isEmpty()) {
            return "";
        }
        return CallsignUtil.toRadioCallsign(atakTarget);
    }

    private static String stripMeshSuffix(String raw) {
        if (raw == null) {
            return "";
        }
        String base = raw.trim();
        if (base.isEmpty()) {
            return "";
        }
        if (base.toUpperCase(Locale.US).endsWith("-MESH")) {
            base = base.substring(0, base.length() - 5).trim();
        }
        return base;
    }

    private static String geoChatCallsignFromContact(IndividualContact contact) {
        if (contact == null) {
            return "";
        }
        try {
            com.atakmap.android.contact.Connector conn =
                    contact.getConnector(GeoChatConnector.CONNECTOR_TYPE);
            if (!(conn instanceof GeoChatConnector)) {
                return "";
            }
            String cs = conn.getConnectionString();
            if (cs == null || cs.trim().isEmpty()) {
                return "";
            }
            int lastColon = cs.lastIndexOf(':');
            if (lastColon >= 0 && lastColon + 1 < cs.length()) {
                return cs.substring(lastColon + 1).trim();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static String formatMeshFavoriteName(String currentName, String uid) {
        return formatMeshFavoriteName(currentName, uid, null);
    }

    public static String uidForDeviceContact(BtConnectionManager.MeshDeviceContact contact) {
        if (contact == null || contact.pubKeyHex == null || contact.pubKeyHex.length() < 12) {
            return "";
        }
        String prefix = contact.pubKeyHex.substring(0, 12).toUpperCase(Locale.US);
        if (contact.type == 0x02) {
            return MESH_RPTR_UID_PREFIX + prefix;
        }
        return MESH_NODE_UID_PREFIX + prefix;
    }

    public static boolean isMeshFavoriteByPubKey(@Nullable String pubKeyHex) {
        if (pubKeyHex == null || pubKeyHex.length() < 12) {
            return false;
        }
        String prefix = pubKeyHex.substring(0, 12).toUpperCase(Locale.US);
        try {
            Contacts contacts = Contacts.getInstance();
            String[] uids = {
                    MESH_NODE_UID_PREFIX + prefix,
                    MESH_RPTR_UID_PREFIX + prefix
            };
            for (String uid : uids) {
                Contact existing = contacts.getContactByUuid(uid);
                if (existing instanceof IndividualContact) {
                    IndividualContact ic = (IndividualContact) existing;
                    if (ic.getConnector(MeshFavoriteConnector.CONNECTOR_TYPE) != null) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void markMeshMapCacheFavorite(Context context, String pubKeyHex,
                                                boolean favorite) {
        if (context == null || pubKeyHex == null || pubKeyHex.trim().isEmpty()) {
            return;
        }
        MeshNodeCachePolicy.markFavorite(context, "meshcore_mesh_node_cache_v1",
                pubKeyHex, favorite);
        MeshNodeCachePolicy.markFavorite(context, "meshcore_mesh_repeater_cache_v1",
                pubKeyHex, favorite);
    }

    @Nullable
    private static String pubKeyPrefixFromMeshUid(@Nullable String uid) {
        if (uid == null) {
            return null;
        }
        String trimmed = uid.trim().toUpperCase(Locale.US);
        String suffix = null;
        if (trimmed.startsWith(MESH_NODE_UID_PREFIX)) {
            suffix = trimmed.substring(MESH_NODE_UID_PREFIX.length());
        } else if (trimmed.startsWith(MESH_RPTR_UID_PREFIX)) {
            suffix = trimmed.substring(MESH_RPTR_UID_PREFIX.length());
        }
        if (suffix == null || suffix.length() < 12) {
            return null;
        }
        return suffix.substring(0, 12);
    }

    public static boolean favoriteDeviceContact(BtConnectionManager bt,
                                                BtConnectionManager.MeshDeviceContact contact) {
        if (contact == null) {
            return false;
        }
        String uid = uidForDeviceContact(contact);
        if (uid.isEmpty()) {
            return false;
        }
        boolean atakOk = promoteMeshFavoriteContactByUid(uid, contact.name);
        boolean deviceOk = bt != null && bt.addOrUpdateDeviceContactFavorite(contact);
        return atakOk || deviceOk;
    }

    private static String formatMeshFavoriteName(String currentName, String uid, MapItem item) {
        String base = normalizeMeshBaseName(currentName);
        if (base.isEmpty() && item != null) {
            String mapCallsign = item.getMetaString("callsign", item.getTitle());
            base = normalizeMeshBaseName(mapCallsign);
        }
        if (base.isEmpty()) {
            base = normalizeMeshBaseName(uid);
        }
        if (base.isEmpty()) {
            base = "NODE";
        }
        return base + "-MESH";
    }

    private static String normalizeMeshBaseName(String raw) {
        if (raw == null) {
            return "";
        }
        String base = raw.trim();
        if (base.startsWith("#")) {
            base = base.substring(1);
        }
        base = base.trim();
        if (base.isEmpty()) {
            return "";
        }
        String upper = base.toUpperCase(Locale.US);
        if (upper.startsWith(MESH_NODE_UID_PREFIX) || upper.startsWith(MESH_RPTR_UID_PREFIX)) {
            return "";
        }
        if (upper.endsWith("-MESH")) {
            upper = upper.substring(0, upper.length() - 5).trim();
        }
        return upper;
    }

    /**
     * Secondary contact-card actions for established RF ATAK peers (beacon / BTECH registry),
     * not mesh-flood synthesized {@code MESHCORE-*} contacts.
     */
    public static void applyEstablishedContactConnectors(IndividualContact contact) {
        if (contact == null) {
            return;
        }
        String uid = contact.getUID();
        if (uid == null || uid.trim().isEmpty()) {
            return;
        }
        String u = uid.trim();
        String upper = u.toUpperCase(Locale.US);
        if (upper.startsWith(MESH_NODE_UID_PREFIX) || upper.startsWith(MESH_RPTR_UID_PREFIX)) {
            return;
        }
        if (!isEstablishedRfAtakContact(u)) {
            return;
        }
        try {
            if (contact.getConnector(MeshRequestPositionConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshRequestPositionConnector());
            }
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "applyEstablishedContactConnectors failed", e);
        }
    }

    private static boolean isEstablishedRfAtakContact(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String u = uid.trim();
        if (u.toUpperCase(Locale.US).startsWith("ANDROID-")) {
            return true;
        }
        CotBridge bridge = ContactMergeUtil.getMergeRoutingBridge();
        return bridge != null && bridge.isBtechContactUid(u);
    }

    public static NetConnectString buildNativeConnectorSeed(String callsign) {
        // Use a loopback address so ATAK's isMulticast() parser never throws
        // ArrayIndexOutOfBoundsException on the "*" wildcard host.  The port 4242
        // is a conventional placeholder; real sends are intercepted by ChatBridge
        // before any TCP connection attempt is made.
        NetConnectString ncs = new NetConnectString("stcp", "127.0.0.1", 4242);
        if (callsign != null && !callsign.trim().isEmpty()) {
            ncs.setCallsign(callsign.trim().toUpperCase());
        }
        return ncs;
    }

    /**
     * Repair connector stacks for all existing MESHCORE-* contacts.
     * Called on plugin connect so contacts created by a prior plugin version (which used
     * PluginConnector as default) are immediately updated to show the radio icon.
     */
    public static void repairAllMeshContactConnectors() {
        try {
            Contacts contacts = Contacts.getInstance();
            if (contacts == null) return;
            int repaired = 0;
            for (Contact c : contacts.getAllContacts()) {
                if (!(c instanceof IndividualContact)) continue;
                String uid = c.getUID();
                if (uid == null) continue;
                String u = uid.toUpperCase(Locale.US);
                if (u.startsWith(MESH_NODE_UID_PREFIX) || u.startsWith(MESH_RPTR_UID_PREFIX)) {
                    applyMeshInboundConnectors((IndividualContact) c);
                    repaired++;
                }
            }
            if (repaired > 0) {
                Log.i("MeshCore.Handler", "repairAllMeshContactConnectors repaired=" + repaired);
            }
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "repairAllMeshContactConnectors failed", e);
        }
    }

    private static void writeDefaultConnectorPref(String contactUid, String connectorType) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv == null || contactUid == null || contactUid.trim().isEmpty()) {
                return;
            }
            AtakPreferences prefs = new AtakPreferences(mv.getContext());
            String uid = contactUid.trim();
            prefs.set("contact.connector.default." + uid, connectorType);
            prefs.set("contact.connector.default." + uid.toUpperCase(), connectorType);
            prefs.set("contact.connector.default." + uid.toLowerCase(), connectorType);
        } catch (Exception ignored) {
        }
    }
}
