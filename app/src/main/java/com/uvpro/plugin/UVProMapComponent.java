package com.uvpro.plugin;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.chat.ChatBridge;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.cot.CotBridge;
import com.uvpro.plugin.crypto.EncryptionManager;
import com.uvpro.plugin.protocol.PacketRouter;

/**
 * MeshCore-only map component.
 */
public class UVProMapComponent extends DropDownMapComponent {

    private static final String TAG = "MeshCore";

    public static final String PLUGIN_PACKAGE = "com.uvpro.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.uvpro.plugin.BEACON_INTERVAL_CHANGED";

    private Context pluginContext;
    private MapView mapView;

    private BtConnectionManager btConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private UVProDropDownReceiver dropDownReceiver;
    private EncryptionManager encryptionManager;

    public static void applyUpdateServerTrustEarly(Context pluginContext) {
        // Intentionally no-op for MeshCore-only plugin.
    }

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        this.pluginContext = context;
        this.mapView = view;

        String callsign = "UNKNOWN";
        try {
            if (view != null && view.getSelfMarker() != null) {
                callsign = view.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve ATAK callsign", e);
        }

        cotBridge = new CotBridge(context, view);
        cotBridge.setLocalCallsign(callsign);
        chatBridge = new ChatBridge(context, view);
        chatBridge.setLocalCallsign(callsign);
        chatBridge.setCotBridge(cotBridge);
        cotBridge.setChatBridge(chatBridge);

        contactTracker = new ContactTracker(cotBridge);
        packetRouter = new PacketRouter(cotBridge, chatBridge, contactTracker);
        encryptionManager = new EncryptionManager();
        cotBridge.setEncryptionManager(encryptionManager);
        packetRouter.setEncryptionManager(encryptionManager);

        btConnectionManager = new BtConnectionManager(context, packetRouter);
        cotBridge.setBtManager(btConnectionManager);
        chatBridge.setBtManager(btConnectionManager);

        dropDownReceiver = new UVProDropDownReceiver(
                view, pluginContext, btConnectionManager, contactTracker);
        packetRouter.setPacketCountListener(dropDownReceiver);

        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(UVProDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(dropDownReceiver, filter);

        view.postDelayed(() -> autoConnectLastMesh(context), 3500L);
        Log.i(TAG, "MeshCore plugin initialized");
    }

    private void autoConnectLastMesh(Context context) {
        try {
            if (btConnectionManager == null
                    || btConnectionManager.isConnected()
                    || btConnectionManager.isConnecting()) {
                return;
            }
            String tgt = BluetoothDeviceRegistry.getMeshConnectTargetAddress(context);
            if (tgt == null || tgt.isEmpty()) {
                Log.d(TAG, "Auto-connect mesh: no saved address");
                return;
            }
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                Log.d(TAG, "Auto-connect mesh: Bluetooth unavailable");
                return;
            }
            android.bluetooth.BluetoothDevice device = adapter.getRemoteDevice(tgt);
            Log.i(TAG, "Auto-connecting to MeshCore target: " + tgt);
            btConnectionManager.connect(device);
        } catch (Exception e) {
            Log.w(TAG, "Auto-connect mesh failed: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        try {
            if (dropDownReceiver != null) {
                dropDownReceiver.dispose();
                dropDownReceiver = null;
            }
        } catch (Exception ignored) {
        }
        if (btConnectionManager != null) {
            btConnectionManager.disconnect();
            btConnectionManager = null;
        }
        packetRouter = null;
        cotBridge = null;
        chatBridge = null;
        contactTracker = null;
        encryptionManager = null;
        mapView = null;
        pluginContext = null;
    }
}
