package com.atakmaps.meshcore.plugin.contacts;

import android.content.Context;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmaps.meshcore.plugin.MeshCoreContactHandler;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
import com.atakmaps.meshcore.plugin.protocol.PositionRequester;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Adds Ping to the radial contact connector submenu via {@link ContactRadialMenuFactory}.
 * Keeps ATAK's stock friendly radial menu intact.
 */
public final class ContactRadialMenuUtil {

    private static final String TAG = "MeshCore.RadialMenu";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";

    public static final String ACTION_RADIAL_PING_CONTACT =
            "com.atakmaps.meshcore.plugin.action.RADIAL_PING_CONTACT";

    private static volatile ContactRadialMenuFactory menuFactory;
    private static volatile String pingActionResource;

    private ContactRadialMenuUtil() {
    }

    public static void init(Context pluginContext) {
        if (pluginContext == null) {
            return;
        }
        synchronized (ContactRadialMenuUtil.class) {
            if (menuFactory != null) {
                return;
            }
            try {
                pingActionResource = encodeAssetAsBase64Menu(pluginContext,
                        "actions/radial_ping_contact.xml");
                menuFactory = new ContactRadialMenuFactory(pluginContext, pingActionResource);
                registerFactoryWhenReady();
                Log.i(TAG, "Contact radial Ping factory ready");
            } catch (Exception e) {
                Log.e(TAG, "Failed to init contact radial Ping factory", e);
            }
        }
    }

    private static void registerFactoryWhenReady() {
        ContactRadialMenuFactory factory = menuFactory;
        if (factory == null) {
            return;
        }
        Runnable register = () -> {
            factory.register();
            repairBrokenRadialMenus();
        };
        if (MapMenuReceiver.getInstance() != null) {
            register.run();
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv != null) {
            mv.post(register);
        }
    }

    public static void applyPingCapableRadialMenu(MapItem item, IndividualContact contact) {
        if (item == null || contact == null) {
            return;
        }
        String uid = contact.getUID();
        if (uid != null && uid.startsWith(MESH_RPTR_UID_PREFIX)) {
            return;
        }
        if (MeshCoreContactHandler.resolvePingTargetCallsign(contact).isEmpty()) {
            clearPingCapable(item);
            return;
        }
        markPingCapable(item);
    }

    public static void applyPingCapableRadialMenu(MapItem item, String contactUid) {
        if (item == null || contactUid == null || contactUid.trim().isEmpty()) {
            return;
        }
        String uid = contactUid.trim();
        Contact c = Contacts.getInstance().getContactByUuid(uid);
        if (c instanceof IndividualContact) {
            applyPingCapableRadialMenu(item, (IndividualContact) c);
            return;
        }
        if (uid.startsWith(MESH_RPTR_UID_PREFIX)) {
            return;
        }
        if (uid.startsWith("MESHCORE-NODE-") || uid.startsWith("ANDROID-")) {
            markPingCapable(item);
        }
    }

    static void clearBrokenCustomMenu(MapItem item) {
        if (item == null) {
            return;
        }
        try {
            String menu = item.getMetaString("menu", null);
            if (menu != null && menu.startsWith("base64:")) {
                item.removeMetaData("menu");
            }
        } catch (Exception e) {
            Log.w(TAG, "clearBrokenCustomMenu failed uid=" + item.getUID(), e);
        }
    }

    private static void markPingCapable(MapItem item) {
        try {
            clearBrokenCustomMenu(item);
            item.setMetaBoolean(ContactRadialMenuFactory.META_PING_CAPABLE, true);
            item.setMetaBoolean("sendable", true);
            item.setMetaString("endpoint", ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
        } catch (Exception e) {
            Log.w(TAG, "markPingCapable failed uid=" + item.getUID(), e);
        }
    }

    private static void clearPingCapable(MapItem item) {
        try {
            item.removeMetaData(ContactRadialMenuFactory.META_PING_CAPABLE);
        } catch (Exception ignored) {
        }
    }

    public static void repairBrokenRadialMenus() {
        MapView mv = MapView.getMapView();
        if (mv == null || mv.getRootGroup() == null) {
            return;
        }
        try {
            repairRecursive(mv.getRootGroup());
        } catch (Exception e) {
            Log.w(TAG, "repairBrokenRadialMenus failed", e);
        }
    }

    private static void repairRecursive(com.atakmap.android.maps.MapGroup group) {
        for (MapItem item : group.getItems()) {
            clearBrokenCustomMenu(item);
        }
        for (com.atakmap.android.maps.MapGroup child : group.getChildGroups()) {
            repairRecursive(child);
        }
    }

    public static void handleRadialPingContact(Context context, String contactUid) {
        if (contactUid == null || contactUid.trim().isEmpty()) {
            return;
        }
        Contact c = Contacts.getInstance().getContactByUuid(contactUid.trim());
        if (!(c instanceof IndividualContact)) {
            Toast.makeText(context, "No contact for ping", Toast.LENGTH_SHORT).show();
            return;
        }
        IndividualContact ic = (IndividualContact) c;
        String uid = ic.getUID();
        if (uid != null && uid.startsWith(MESH_RPTR_UID_PREFIX)) {
            Toast.makeText(context, "Repeaters do not support ping", Toast.LENGTH_LONG).show();
            return;
        }
        String atakTarget = MeshCoreContactHandler.resolvePingTargetCallsign(ic);
        if (atakTarget == null || atakTarget.isEmpty()) {
            Toast.makeText(context, "Could not resolve contact callsign", Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = PositionRequester.requestPosition(context, uid, atakTarget);
        Toast.makeText(context,
                ok ? "Ping sent to " + atakTarget : "Ping failed (radio not connected)",
                Toast.LENGTH_LONG).show();
    }

    private static String encodeAssetAsBase64Menu(Context ctx, String assetPath) throws Exception {
        return encodeXmlAsBase64Menu(readAssetUtf8(ctx, assetPath));
    }

    private static String encodeXmlAsBase64Menu(String xml) {
        byte[] encoded = Base64.encode(
                xml.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP | Base64.URL_SAFE);
        return "base64://" + new String(encoded, StandardCharsets.UTF_8);
    }

    private static String readAssetUtf8(Context ctx, String assetPath) throws Exception {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(assetPath);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    out.write(buffer, 0, read);
                }
            }
            return new String(out.toByteArray(), FileSystemUtils.UTF8_CHARSET);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
