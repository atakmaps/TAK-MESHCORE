package com.atakmaps.meshcore.plugin;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmaps.meshcore.plugin.bluetooth.BluetoothDeviceRegistry;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
import com.atakmaps.meshcore.plugin.contacts.ContactTracker;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.protocol.PacketRouter;
import com.atakmaps.meshcore.plugin.ui.MeshStatusOverlay;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

/**
 * MeshCore-only map component.
 */
public class MeshCoreMapComponent extends DropDownMapComponent {

    private static final String TAG = "MeshCore";
    private static final long POST_CONNECT_BEACON_DELAY_MS = 30_000L;

    public static final String PLUGIN_PACKAGE = "com.atakmaps.meshcore.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.atakmaps.meshcore.plugin.BEACON_INTERVAL_CHANGED";

    private Context pluginContext;
    private MapView mapView;

    private BtConnectionManager btConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private MeshCoreDropDownReceiver dropDownReceiver;
    private EncryptionManager encryptionManager;
    private final Runnable postConnectBeaconRunnable = this::sendPostConnectBeacon;
    private final Runnable waitForPositionBeaconRunnable = this::waitForPositionThenScheduleBeacon;

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
        try {
            com.atakmap.android.contact.ContactConnectorManager mgr =
                    com.atakmap.android.cot.CotMapComponent.getInstance()
                            .getContactConnectorMgr();
            mgr.addContactHandler(new com.atakmaps.meshcore.plugin.MeshCoreContactHandler(context));
        } catch (Exception e) {
            Log.e(TAG, "Contact handler registration failed", e);
        }
        packetRouter = new PacketRouter(cotBridge, chatBridge, contactTracker);
        encryptionManager = new EncryptionManager();
        cotBridge.setEncryptionManager(encryptionManager);
        chatBridge.setEncryptionManager(encryptionManager);
        packetRouter.setEncryptionManager(encryptionManager);

        btConnectionManager = new BtConnectionManager(context, packetRouter);
        btConnectionManager.addListener(new BtConnectionManager.ConnectionListener() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                MeshStatusOverlay.setConnected(true);
                schedulePostConnectBeacon();
            }

            @Override
            public void onDisconnected(String reason) {
                MeshStatusOverlay.setConnected(false);
                cancelPostConnectBeacon();
            }

            @Override
            public void onError(String error) {
                MeshStatusOverlay.setConnected(false);
            }

            @Override
            public void onDeviceFound(android.bluetooth.BluetoothDevice device) {
            }

            @Override
            public void onScanComplete() {
            }
        });
        cotBridge.setBtManager(btConnectionManager);
        chatBridge.setBtManager(btConnectionManager);

        dropDownReceiver = new MeshCoreDropDownReceiver(
                view, pluginContext, btConnectionManager, contactTracker, cotBridge);
        packetRouter.setPacketCountListener(dropDownReceiver);

        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(MeshCoreDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(dropDownReceiver, filter);
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "MeshCore Settings",
                        "MeshCore plugin configuration",
                        SettingsFragment.TOOL_SETTINGS_KEY,
                        MeshCoreTool.toolbarIcon(context),
                        new SettingsFragment(context)));
        view.post(() -> MeshStatusOverlay.install(pluginContext));
        MeshStatusOverlay.setConnected(btConnectionManager.isConnected());
        contactTracker.start();
        chatBridge.setRelayOutgoing(true);
        chatBridge.startOutgoingRelay();
        cotBridge.setRelayOutgoingSa(false);
        cotBridge.startOutgoingRelay();

        view.postDelayed(() -> autoConnectLastMesh(context), 3500L);
        Log.i(TAG, "MeshCore plugin initialized");
    }

    private void schedulePostConnectBeacon() {
        if (mapView == null) {
            return;
        }
        mapView.removeCallbacks(postConnectBeaconRunnable);
        mapView.removeCallbacks(waitForPositionBeaconRunnable);
        if (hasValidSelfPosition()) {
            mapView.postDelayed(postConnectBeaconRunnable, POST_CONNECT_BEACON_DELAY_MS);
            Log.d(TAG, "Scheduled post-connect beacon in 30 seconds");
            return;
        }
        mapView.postDelayed(waitForPositionBeaconRunnable, 2_000L);
        Log.d(TAG, "Post-connect beacon waiting for valid self position");
    }

    private void waitForPositionThenScheduleBeacon() {
        if (mapView == null || btConnectionManager == null || !btConnectionManager.isConnected()) {
            return;
        }
        if (hasValidSelfPosition()) {
            mapView.postDelayed(postConnectBeaconRunnable, POST_CONNECT_BEACON_DELAY_MS);
            Log.d(TAG, "Valid self position acquired; post-connect beacon in 30 seconds");
            return;
        }
        mapView.postDelayed(waitForPositionBeaconRunnable, 2_000L);
    }

    private void cancelPostConnectBeacon() {
        if (mapView == null) {
            return;
        }
        mapView.removeCallbacks(postConnectBeaconRunnable);
        mapView.removeCallbacks(waitForPositionBeaconRunnable);
    }

    private void sendPostConnectBeacon() {
        try {
            if (mapView == null || btConnectionManager == null || cotBridge == null) {
                return;
            }
            if (!btConnectionManager.isConnected()) {
                Log.d(TAG, "Skipping post-connect beacon: no longer connected");
                return;
            }
            com.atakmap.android.maps.MapItem self = mapView.getSelfMarker();
            if (!(self instanceof com.atakmap.android.maps.PointMapItem)) {
                Log.w(TAG, "Skipping post-connect beacon: self marker unavailable");
                return;
            }
            com.atakmap.coremap.maps.coords.GeoPoint gp =
                    ((com.atakmap.android.maps.PointMapItem) self).getPoint();
            if (gp == null || !gp.isValid() || !isValidCoordinate(gp.getLatitude(), gp.getLongitude())) {
                Log.w(TAG, "Skipping post-connect beacon: self position still invalid");
                return;
            }
            cotBridge.sendPositionOverRadio(
                    gp.getLatitude(),
                    gp.getLongitude(),
                    gp.getAltitude(),
                    0f,
                    0f,
                    -1);
            Log.i(TAG, "Sent post-connect beacon at 30s");
        } catch (Exception e) {
            Log.w(TAG, "Post-connect beacon failed: " + e.getMessage());
        }
    }

    private boolean hasValidSelfPosition() {
        if (mapView == null) {
            return false;
        }
        PointMapItem self = mapView.getSelfMarker();
        if (self == null) {
            return false;
        }
        GeoPoint gp = self.getPoint();
        return gp != null && gp.isValid() && isValidCoordinate(gp.getLatitude(), gp.getLongitude());
    }

    private boolean isValidCoordinate(double lat, double lon) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return false;
        }
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
            return false;
        }
        return !(Math.abs(lat) < 0.000001 && Math.abs(lon) < 0.000001);
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
        ToolsPreferenceFragment.unregister(SettingsFragment.TOOL_SETTINGS_KEY);
        cancelPostConnectBeacon();
        MeshStatusOverlay.uninstall();
        if (chatBridge != null) {
            chatBridge.dispose();
        }
        if (cotBridge != null) {
            cotBridge.dispose();
        }
        if (contactTracker != null) {
            contactTracker.stop();
        }
        if (btConnectionManager != null) {
            btConnectionManager.disconnect();
            btConnectionManager.shutdown();
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
