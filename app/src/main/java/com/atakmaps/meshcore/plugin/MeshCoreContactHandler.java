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
import com.atakmaps.meshcore.plugin.contacts.MeshFavoriteConnector;
import com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector;
import com.atakmaps.meshcore.plugin.contacts.PositionOnlyConnector;

import android.widget.Toast;

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
                || FileSystemUtils.isEquals(type, MeshSendMessageConnector.CONNECTOR_TYPE);
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

            if (FileSystemUtils.isEquals(connectorType, GeoChatConnector.CONNECTOR_TYPE)) {
                // Reachable: defer to ATAK's default GeoChatConnectorHandler.
                return false;
            }

            // Open chat UI
            ChatManagerMapComponent.getInstance().openConversation(
                    ic, true);

            // User is viewing this contact; clear unread badge.
            clearUnread(contactUID);
            Log.i("BTRelay", "Contact selected for chat: " + contactUID);
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
            // Only the PluginConnector address should carry our unread count.
            // MeshSendMessageConnector shares the same connection string (ACTION_PLUGIN_CONTACT_GEOCHAT_SEND)
            // so we must also gate on connector type to avoid doubling the badge.
            if (!PluginConnector.CONNECTOR_TYPE.equals(connectorType)
                    || connectorAddress == null
                    || !PLUGIN_GEOCHAT_ACTION.equals(connectorAddress)) {
                Log.i("MeshCore.Handler", "NotificationCount uid=" + contactUID + " addr="
                        + connectorAddress + " -> 0 (plugin-only)");
                return 0;
            }
            Set<String> keys = unreadKeysByUid.get(contactUID != null ? contactUID.trim() : "");
            int n = keys == null ? 0 : keys.size();
            Log.i("MeshCore.Handler", "NotificationCount uid=" + contactUID + " addr=" + connectorAddress + " -> " + n);
            return n;
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
        // Actively remove GeoChatConnector — it causes ATAK's native GeoChat unread system to
        // show a second notification badge alongside our plugin counter.
        try {
            contact.removeConnector(com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
        } catch (Exception ignored) {
        }
        try {
            if (contact.getConnector(PluginConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new PluginConnector(PLUGIN_GEOCHAT_ACTION));
            }
            if (contact.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new MeshSendMessageConnector());
            }
            writeDefaultConnectorPref(contact.getUID(), MeshSendMessageConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
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
            if (contact.getConnector(GeoChatConnector.CONNECTOR_TYPE) == null) {
                contact.addConnector(new GeoChatConnector(
                        buildNativeConnectorSeed(contact.getName())));
            }
            writeDefaultConnectorPref(contact.getUID(), MeshSendMessageConnector.CONNECTOR_TYPE);
            contact.dispatchChangeEvent();
        } catch (Exception e) {
            Log.w("MeshCore.Handler", "applyMeshContactConnectors failed", e);
        }
    }

    public static String formatMeshFavoriteName(String currentName, String uid) {
        return formatMeshFavoriteName(currentName, uid, null);
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

    private static NetConnectString buildNativeConnectorSeed(String callsign) {
        NetConnectString ncs = new NetConnectString("stcp", "*", -1);
        if (callsign != null && !callsign.trim().isEmpty()) {
            ncs.setCallsign(callsign.trim().toUpperCase());
        }
        return ncs;
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
