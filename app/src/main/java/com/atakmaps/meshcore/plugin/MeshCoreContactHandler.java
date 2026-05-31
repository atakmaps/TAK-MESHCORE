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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MeshCoreContactHandler extends
        ContactConnectorManager.ContactConnectorHandler {

    /** Must match ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND. */
    public static final String PLUGIN_GEOCHAT_ACTION =
            "com.atakmaps.meshcore.plugin.action.PLUGIN_CONTACT_GEOCHAT_SEND";

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
        return "UV-PRO";
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

        Log.i("UVPro.Handler", "getFeature feature=" + feature
                + " uid=" + contactUID + " address=" + connectorAddress);

        if (feature == ContactConnectorManager.ConnectorFeature.NotificationCount) {
            // ATAK sums counts across connector queries; only the plugin connector may report
            // unread — all other addresses (including null) must return 0 or the UI shows 2.
            if (connectorAddress == null
                    || !PLUGIN_GEOCHAT_ACTION.equals(connectorAddress)) {
                Log.i("UVPro.Handler", "NotificationCount uid=" + contactUID + " addr="
                        + connectorAddress + " -> 0 (plugin-only)");
                return 0;
            }
            Set<String> keys = unreadKeysByUid.get(contactUID != null ? contactUID.trim() : "");
            int n = keys == null ? 0 : keys.size();
            Log.i("UVPro.Handler", "NotificationCount uid=" + contactUID + " addr=" + connectorAddress + " -> " + n);
            return n;
        }

        return null;
    }

    @Override
    public String getDescription() {
        return "UV-PRO Contact Handler";
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
            Log.w("UVPro.Handler", "promoteMeshFavoriteContact failed", e);
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
            Log.w("UVPro.Handler", "applyMeshContactConnectors failed", e);
        }
    }

    public static String formatMeshFavoriteName(String currentName, String uid) {
        String base = currentName != null ? currentName.trim() : "";
        if (base.isEmpty()) {
            base = uid != null ? uid.trim() : "node";
        }
        if (base.startsWith("#")) {
            base = base.substring(1);
        }
        if (base.toLowerCase().endsWith("-mesh")) {
            base = base.substring(0, base.length() - 5);
        }
        base = base.trim();
        if (base.isEmpty()) {
            base = "node";
        }
        return base + "-mesh";
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
