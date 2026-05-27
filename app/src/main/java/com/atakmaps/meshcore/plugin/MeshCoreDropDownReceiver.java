package com.atakmaps.meshcore.plugin;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MetaDataHolder2;
import com.atakmap.android.maps.MapView;
import com.atakmaps.meshcore.plugin.beacon.SmartBeacon;
import com.atakmaps.meshcore.plugin.beacon.SmartBeaconSettingsDialog;
import com.atakmaps.meshcore.plugin.bluetooth.BluetoothDeviceRegistry;
import com.atakmaps.meshcore.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.contacts.ContactTracker;
import com.atakmaps.meshcore.plugin.contacts.RadioContact;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.protocol.PacketRouter;
import com.atakmaps.meshcore.plugin.ui.MeshStatusOverlay;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * MeshCore-only dropdown UI.
 */
public class MeshCoreDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener,
        BtConnectionManager.ConnectionListener,
        ContactTracker.ContactListener,
        PacketRouter.PacketCountListener {

    public static final String SHOW_PLUGIN = "com.atakmaps.meshcore.plugin.SHOW_PLUGIN";
    private static final String TAG = "MeshCore.UI";
    private static final int MAX_LOG_LINES = 50;
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int EDIT_SELECTION_STROKE_DP = 3;

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final ContactTracker contactTracker;
    private final CotBridge cotBridge;

    private View rootView;
    private View statusDot;
    private TextView statusText;
    private TextView deviceName;
    private TextView callsignText;
    private TextView contactsText;
    private TextView packetsText;
    private TextView logText;
    private TextView favoritesLabel;
    private HorizontalScrollView favoritesScroll;
    private LinearLayout favoritesStrip;
    private TextView connectModeHint;
    private Button btnScan;
    private Button btnDisconnect;
    private Button btnSettings;
    private Button btnSendBeacon;
    private Button btnBeaconSettings;
    private Button btnPluginSettings;
    private Button btnSmartBeaconSettings;
    private Switch switchSmartBeacon;
    private Switch switchMeshEnableGps;
    private Button btnUpdateGpsFromMeshcore;

    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private final LinkedList<String> logLines = new LinkedList<>();
    private int txCount = 0;
    private int rxCount = 0;
    private boolean scanFoundAnyDevice = false;

    private ValueAnimator connectPulseAnimator;
    private GradientDrawable connectPulseDrawable;
    private volatile boolean scanDiscoveryPulseActive = false;
    private boolean scanPulseBright = false;
    private final Runnable scanDiscoveryPulseRunnable = new Runnable() {
        @Override
        public void run() {
            if (!scanDiscoveryPulseActive || btnScan == null) {
                return;
            }
            int fill = scanPulseBright ? 0xFFE0B800 : COLOR_PILL_BUTTON_PRIMARY;
            int stroke = 0xFFFFEB3B;
            btnScan.setBackgroundTintList(null);
            btnScan.setBackground(buildPillButtonBackground(fill, stroke));
            btnScan.setAlpha(1f);
            btnScan.invalidate();
            scanPulseBright = !scanPulseBright;
            getMapView().postDelayed(this, 320L);
        }
    };
    private static final long MESH_GPS_FRESH_TIMEOUT_MS = 12_000L;
    private Boolean meshGpsEnabledState = null;
    private boolean suppressMeshGpsSwitchCallbacks = false;
    private boolean pendingManualMeshGpsUpdate = false;
    private long pendingManualMeshGpsSinceMs = 0L;
    private final Runnable manualMeshGpsTimeoutRunnable = () -> {
        if (!pendingManualMeshGpsUpdate) {
            return;
        }
        pendingManualMeshGpsUpdate = false;
        pendingManualMeshGpsSinceMs = 0L;
        appendLog("No fresh MeshCore GPS fix received. Move device outside and retry.");
    };
    private final BtConnectionManager.MeshStateListener meshStateListener =
            new BtConnectionManager.MeshStateListener() {
                @Override
                public void onMeshGpsStateChanged(boolean enabled) {
                    meshGpsEnabledState = enabled;
                    getMapView().post(() -> {
                        updateMeshGpsControlsUi();
                        appendLog("MeshCore GPS " + (enabled ? "enabled" : "disabled"));
                    });
                }

                @Override
                public void onMeshSelfLocationUpdated(BtConnectionManager.MeshLocationFix fix) {
                    if (fix == null || !fix.isValid()) {
                        return;
                    }
                    if (!pendingManualMeshGpsUpdate) {
                        return;
                    }
                    if (fix.receivedAtMs < pendingManualMeshGpsSinceMs) {
                        return;
                    }
                    pendingManualMeshGpsUpdate = false;
                    pendingManualMeshGpsSinceMs = 0L;
                    getMapView().removeCallbacks(manualMeshGpsTimeoutRunnable);
                    getMapView().post(() -> {
                        if (injectMeshGpsIntoAtak(fix)) {
                            appendLog(String.format(Locale.US,
                                    "Updated ATAK from MeshCore GPS: %.5f, %.5f",
                                    fix.latitude, fix.longitude));
                        } else {
                            appendLog("Could not apply MeshCore GPS to ATAK");
                        }
                    });
                }
            };
    private final CompoundButton.OnCheckedChangeListener smartBeaconCheckedListener =
            (buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                Context ctx = getMapView().getContext();
                SmartBeacon.setEnabled(ctx, isChecked);
                appendLog("Smart beacon " + (isChecked ? "enabled" : "disabled"));
                try {
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent(MeshCoreMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                } catch (Exception ignored) {
                }
            };

    public MeshCoreDropDownReceiver(MapView mapView,
                                 Context pluginContext,
                                 BtConnectionManager btManager,
                                 ContactTracker contactTracker,
                                 CotBridge cotBridge) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
        this.contactTracker = contactTracker;
        this.cotBridge = cotBridge;
        btManager.addListener(this);
        btManager.addMeshStateListener(meshStateListener);
        contactTracker.setListener(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SHOW_PLUGIN.equals(intent.getAction())) {
            showDropDown(createView(),
                    HALF_WIDTH, FULL_HEIGHT,
                    FULL_WIDTH, HALF_HEIGHT,
                    false, this);
        }
    }

    private View createView() {
        LayoutInflater inflater = LayoutInflater.from(pluginContext);
        rootView = inflater.inflate(
                pluginContext.getResources().getIdentifier(
                        "uvpro_dropdown", "layout", pluginContext.getPackageName()),
                null);

        bindViews();
        setupListeners();
        MeshStatusOverlay.install(pluginContext);

        String callsign = "UNKNOWN";
        try {
            callsign = MapView.getMapView().getSelfMarker().getMetaString("callsign", "UNKNOWN");
        } catch (Exception ignored) {
        }
        if (callsignText != null) {
            callsignText.setText(callsign);
        }

        if (btManager.isConnected()) {
            updateConnectionUI(true, btManager.getConnectedDeviceName());
            MeshStatusOverlay.setConnected(true);
            stopConnectButtonPulse(true);
        } else {
            updateConnectionUI(false, null);
            MeshStatusOverlay.setConnected(false);
            if (btManager.isConnecting()) {
                startConnectButtonPulse();
            } else {
                stopConnectButtonPulse(true);
            }
        }

        updateContactCount();
        updatePacketCount();
        refreshFavoriteStrip();
        updateScanButtonText();
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setChecked(SmartBeacon.isEnabled(getMapView().getContext()));
        }
        meshGpsEnabledState = btManager.getMeshGpsEnabled();
        updateMeshGpsControlsUi();
        appendLog("MeshCore ready");
        return rootView;
    }

    private void bindViews() {
        statusDot = rootView.findViewById(getId("status_dot"));
        statusText = rootView.findViewById(getId("status_text"));
        deviceName = rootView.findViewById(getId("device_name"));
        callsignText = rootView.findViewById(getId("text_callsign"));
        contactsText = rootView.findViewById(getId("text_contacts"));
        packetsText = rootView.findViewById(getId("text_packets"));
        logText = rootView.findViewById(getId("text_log"));

        favoritesLabel = rootView.findViewById(getId("favorites_label"));
        favoritesScroll = rootView.findViewById(getId("favorites_scroll"));
        favoritesStrip = rootView.findViewById(getId("favorites_strip"));
        connectModeHint = rootView.findViewById(getId("connect_mode_hint"));

        btnScan = rootView.findViewById(getId("btn_scan"));
        btnDisconnect = rootView.findViewById(getId("btn_disconnect"));
        btnSettings = rootView.findViewById(getId("btn_settings"));
        btnSendBeacon = rootView.findViewById(getId("btn_send_beacon"));
        btnBeaconSettings = rootView.findViewById(getId("btn_beacon_settings"));
        btnPluginSettings = rootView.findViewById(getId("btn_plugin_settings"));
        btnSmartBeaconSettings = rootView.findViewById(getId("btn_smart_beacon_settings"));
        switchSmartBeacon = rootView.findViewById(getId("switch_smart_beacon"));
        switchMeshEnableGps = rootView.findViewById(getId("switch_mesh_enable_gps"));
        btnUpdateGpsFromMeshcore = rootView.findViewById(getId("btn_update_gps_from_meshcore"));

        if (logText != null) {
            logText.setMovementMethod(new ScrollingMovementMethod());
            logText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
        }
    }

    private void setupListeners() {
        if (btnScan != null) {
            btnScan.setOnClickListener(v -> onScanOrConnectClicked());
        }
        if (btnDisconnect != null) {
            btnDisconnect.setOnClickListener(v -> {
                btManager.disconnect();
                stopConnectButtonPulse(true);
            });
        }
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v ->
                    com.atakmaps.meshcore.plugin.ui.BluetoothDevicesManagement.show(
                            getMapView().getContext(),
                            () -> getMapView().post(() -> {
                                refreshFavoriteStrip();
                                updateScanButtonText();
                            })));
        }
        if (btnSendBeacon != null) {
            btnSendBeacon.setOnClickListener(v -> sendManualBeacon());
        }
        if (btnBeaconSettings != null) {
            btnBeaconSettings.setOnClickListener(v -> showSettingsDialog());
        }
        if (btnPluginSettings != null) {
            btnPluginSettings.setOnClickListener(v -> showSettingsDialog());
        }
        if (switchSmartBeacon != null) {
            switchSmartBeacon.setOnCheckedChangeListener(smartBeaconCheckedListener);
        }
        if (btnSmartBeaconSettings != null) {
            btnSmartBeaconSettings.setOnClickListener(v ->
                    SmartBeaconSettingsDialog.show(getMapView().getContext(),
                            () -> appendLog("Smart beacon settings updated")));
        }
        if (switchMeshEnableGps != null) {
            switchMeshEnableGps.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks) {
                    return;
                }
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before changing GPS state");
                    updateMeshGpsControlsUi();
                    return;
                }
                meshGpsEnabledState = isChecked;
                updateMeshGpsControlsUi();
                btManager.setMeshGpsEnabled(isChecked);
                btManager.queryMeshGpsEnabled();
            });
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setOnClickListener(v -> requestManualMeshGpsUpdate());
        }
    }

    private void updateMeshGpsControlsUi() {
        boolean enabled = Boolean.TRUE.equals(meshGpsEnabledState);
        if (switchMeshEnableGps != null) {
            suppressMeshGpsSwitchCallbacks = true;
            switchMeshEnableGps.setChecked(enabled);
            suppressMeshGpsSwitchCallbacks = false;
            switchMeshEnableGps.setEnabled(btManager != null && btManager.isConnected());
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private void requestManualMeshGpsUpdate() {
        if (!btManager.isConnected()) {
            appendLog("Connect to MeshCore first");
            return;
        }
        BtConnectionManager.MeshLocationFix cached = btManager.getLatestSelfLocation();
        if (cached != null && cached.isValid()) {
            long ageSec = Math.max(0L, (System.currentTimeMillis() - cached.receivedAtMs) / 1000L);
            appendLog("Cached MeshCore fix age: " + ageSec + "s");
        }
        pendingManualMeshGpsUpdate = true;
        pendingManualMeshGpsSinceMs = System.currentTimeMillis();
        getMapView().removeCallbacks(manualMeshGpsTimeoutRunnable);
        getMapView().postDelayed(manualMeshGpsTimeoutRunnable, MESH_GPS_FRESH_TIMEOUT_MS);
        appendLog("Requesting fresh MeshCore GPS fix...");
        btManager.requestSelfInfo();
    }

    private boolean injectMeshGpsIntoAtak(BtConnectionManager.MeshLocationFix fix) {
        if (fix == null || !fix.isValid()) {
            return false;
        }
        MapView mv = getMapView();
        if (mv == null) {
            return false;
        }
        MetaDataHolder2 data = mv.getMapData();
        if (data == null) {
            return false;
        }
        com.atakmap.coremap.maps.coords.GeoPoint gp =
                new com.atakmap.coremap.maps.coords.GeoPoint(fix.latitude, fix.longitude);
        data.setMetaString("locationSourcePrefix", "mock");
        data.setMetaBoolean("mockLocationAvailable", true);
        data.setMetaString("mockLocationSource", "MeshCore GPS");
        data.setMetaString("mockLocationSourceColor", "#FF00BCD4");
        data.setMetaBoolean("mockLocationCallsignValid", true);
        data.setMetaString("mockLocation", gp.toString());
        data.setMetaLong("mockLocationTime", SystemClock.elapsedRealtime());
        data.setMetaLong("mockGPSTime", new com.atakmap.coremap.maps.time.CoordinatedTime().getMilliseconds());
        Intent gpsReceived = new Intent("com.atakmap.android.map.WR_GPS_RECEIVED");
        AtakBroadcast.getInstance().sendBroadcast(gpsReceived);
        return true;
    }

    private void sendManualBeacon() {
        if (cotBridge == null || !btManager.isConnected()) {
            appendLog("Not connected");
            return;
        }
        com.atakmap.android.maps.MapItem self = getMapView().getSelfMarker();
        if (!(self instanceof com.atakmap.android.maps.PointMapItem)) {
            appendLog("No self-location available");
            return;
        }
        com.atakmap.coremap.maps.coords.GeoPoint gp =
                ((com.atakmap.android.maps.PointMapItem) self).getPoint();
        cotBridge.sendPositionOverRadio(
                gp.getLatitude(),
                gp.getLongitude(),
                gp.getAltitude(),
                0f,
                0f,
                -1);
        appendLog("Beacon sent");
    }

    private void showSettingsDialog() {
        Context ctx = getMapView().getContext();
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        ScrollView scrollView = new ScrollView(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 12);
        layout.setPadding(pad, pad, pad, pad);
        scrollView.addView(layout);

        TextView bluetoothHeader = new TextView(ctx);
        bluetoothHeader.setText("Bluetooth Favorites");
        bluetoothHeader.setTextColor(0xFFFFFFFF);
        bluetoothHeader.setTextSize(15f);
        layout.addView(bluetoothHeader);

        TextView bluetoothHint = new TextView(ctx);
        bluetoothHint.setText("Manage saved MeshCore devices and favorites shown on the panel.");
        bluetoothHint.setTextColor(0xFFAAAAAA);
        bluetoothHint.setTextSize(11f);
        bluetoothHint.setPadding(0, dip(ctx, 2), 0, dip(ctx, 6));
        layout.addView(bluetoothHint);

        Button btnBluetoothDevices = new Button(ctx);
        btnBluetoothDevices.setText("Manage Bluetooth Devices");
        applyPillButtonBackground(btnBluetoothDevices, COLOR_PILL_BUTTON_PRIMARY);
        btnBluetoothDevices.setOnClickListener(v ->
                com.atakmaps.meshcore.plugin.ui.BluetoothDevicesManagement.show(ctx, () ->
                        getMapView().post(() -> {
                            refreshFavoriteStrip();
                            updateScanButtonText();
                        })));
        layout.addView(btnBluetoothDevices);

        TextView beaconHeader = new TextView(ctx);
        beaconHeader.setText("\nBeacon");
        beaconHeader.setTextColor(0xFFFFFFFF);
        beaconHeader.setTextSize(15f);
        layout.addView(beaconHeader);

        TextView beaconLabel = new TextView(ctx);
        beaconLabel.setText("GPS Beacon Interval (seconds)");
        beaconLabel.setTextColor(0xFFAAAAAA);
        beaconLabel.setPadding(0, dip(ctx, 4), 0, dip(ctx, 2));
        layout.addView(beaconLabel);

        EditText editBeaconInterval = new EditText(ctx);
        editBeaconInterval.setInputType(InputType.TYPE_CLASS_NUMBER);
        editBeaconInterval.setText(prefs.getString(
                SettingsFragment.PREF_BEACON_INTERVAL,
                SettingsFragment.DEFAULT_BEACON_INTERVAL));
        layout.addView(editBeaconInterval);

        LinearLayout smartRow = new LinearLayout(ctx);
        smartRow.setOrientation(LinearLayout.HORIZONTAL);
        smartRow.setPadding(0, dip(ctx, 8), 0, dip(ctx, 2));

        TextView smartLabel = new TextView(ctx);
        smartLabel.setText("Enable Smart Beacon");
        smartLabel.setTextColor(0xFFE0E0E0);
        LinearLayout.LayoutParams smartLabelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        smartRow.addView(smartLabel, smartLabelLp);

        Switch switchSmart = new Switch(ctx);
        switchSmart.setChecked(SmartBeacon.isEnabled(ctx));
        smartRow.addView(switchSmart);
        layout.addView(smartRow);

        Button btnSmartSettings = new Button(ctx);
        btnSmartSettings.setText("Smart Beacon Settings");
        applyPillButtonBackground(btnSmartSettings, COLOR_PILL_BUTTON_PRIMARY);
        btnSmartSettings.setOnClickListener(v ->
                SmartBeaconSettingsDialog.show(ctx,
                        () -> appendLog("Smart beacon settings updated")));
        layout.addView(btnSmartSettings);

        switchSmart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SmartBeacon.setEnabled(ctx, isChecked);
            editBeaconInterval.setEnabled(!isChecked);
            editBeaconInterval.setAlpha(isChecked ? 0.45f : 1.0f);
        });
        editBeaconInterval.setEnabled(!switchSmart.isChecked());
        editBeaconInterval.setAlpha(switchSmart.isChecked() ? 0.45f : 1.0f);

        new AlertDialog.Builder(ctx)
                .setTitle("MeshCore Settings")
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String beacon = editBeaconInterval.getText().toString().trim();
                    android.content.SharedPreferences.Editor editor = prefs.edit();
                    if (!beacon.isEmpty()) {
                        editor.putString(SettingsFragment.PREF_BEACON_INTERVAL, beacon);
                    }
                    editor.apply();
                    SmartBeacon.setEnabled(ctx, switchSmart.isChecked());
                    if (switchSmartBeacon != null) {
                        switchSmartBeacon.setChecked(switchSmart.isChecked());
                    }
                    appendLog("Settings saved");
                    try {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent(MeshCoreMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                    } catch (Exception ignored) {
                    }
                })
                .show();
    }

    private void onScanOrConnectClicked() {
        if (btManager.isConnected()) {
            btManager.disconnect();
            return;
        }
        if (btManager.isConnecting()) {
            appendLog("Cancelling current connection attempt...");
            btManager.cancelConnectionAttempts();
            stopConnectButtonPulse(true);
        }

        Context ctx = getMapView().getContext();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            appendLog("Bluetooth not available");
            return;
        }

        String target = getFavoriteDirectConnectTarget(ctx);
        BtDeviceRecord targetRecord =
                (target != null && !target.isEmpty()) ? BluetoothDeviceRegistry.find(ctx, target) : null;
        boolean connectMode = target != null && !target.isEmpty();

        if (connectMode) {
            try {
                BluetoothDevice device = adapter.getRemoteDevice(target);
                String display = targetRecord != null
                        ? BluetoothDeviceRegistry.getDisplayTitle(targetRecord)
                        : target;
                appendLog("Connecting to " + display + "...");
                startConnectButtonPulse();
                btManager.connect(device);
            } catch (Exception e) {
                appendLog("Saved MeshCore target unavailable, switching to scan.");
                BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
                refreshFavoriteStrip();
                updateScanButtonText();
            }
            return;
        }

        foundDevices.clear();
        scanFoundAnyDevice = false;
        refreshFavoriteStrip();
        updateScanButtonText();
        appendLog("Scanning for MeshCore devices...");
        startScanDiscoveryPulse();
        btManager.startScan();
    }

    private void showDevicePicker() {
        if (foundDevices.isEmpty()) {
            appendLog("No MeshCore devices found");
            return;
        }
        Context ctx = getMapView().getContext();
        final String[] names = new String[foundDevices.size()];
        for (int i = 0; i < foundDevices.size(); i++) {
            names[i] = resolveDeviceDisplayName(ctx, foundDevices.get(i));
        }
        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Select MeshCore")
                    .setItems(names, (dialog, which) -> {
                        if (which < 0 || which >= foundDevices.size()) {
                            return;
                        }
                        BluetoothDevice selected = foundDevices.get(which);
                        appendLog("Connecting to " + names[which] + "...");
                        startConnectButtonPulse();
                        btManager.connect(selected);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing MeshCore picker", e);
            appendLog("Error showing MeshCore picker");
        }
    }

    private void updateConnectionUI(boolean connected, String device) {
        if (statusDot != null) {
            statusDot.setBackgroundColor(connected ? 0xFF4CAF50 : 0xFFFF0000);
        }
        if (statusText != null) {
            statusText.setText(connected ? "Connected" : "Disconnected");
        }
        if (deviceName != null) {
            if (connected && device != null) {
                deviceName.setText(device);
                deviceName.setVisibility(View.VISIBLE);
            } else {
                deviceName.setVisibility(View.GONE);
            }
        }
        if (btnScan != null) {
            btnScan.setEnabled(!connected);
        }
        if (btnDisconnect != null) {
            btnDisconnect.setEnabled(connected);
        }
        updateScanButtonText();
    }

    private void updateScanButtonText() {
        if (btnScan == null) return;
        Context ctx = getMapView().getContext();
        String tgt = getFavoriteDirectConnectTarget(ctx);
        if (!btManager.isConnected() && tgt != null && !tgt.isEmpty()) {
            btnScan.setText("CONNECT");
        } else {
            btnScan.setText("SCAN & CONNECT");
        }
    }

    private void refreshFavoriteStrip() {
        if (favoritesStrip == null || favoritesScroll == null
                || favoritesLabel == null || connectModeHint == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        favoritesStrip.removeAllViews();

        List<BtDeviceRecord> favs = BluetoothDeviceRegistry.getFavoritesSorted(ctx);
        List<BtDeviceRecord> meshFavs = new ArrayList<>();
        for (BtDeviceRecord r : favs) {
            if (isLikelyMeshRecord(r)) {
                meshFavs.add(r);
            }
        }
        if (meshFavs.isEmpty()) {
            favoritesLabel.setVisibility(View.GONE);
            favoritesScroll.setVisibility(View.GONE);
            connectModeHint.setVisibility(View.GONE);
            return;
        }

        favoritesLabel.setText("FAVORITE MESH");
        favoritesLabel.setVisibility(View.VISIBLE);
        favoritesScroll.setVisibility(View.VISIBLE);

        String selected = getFavoriteDirectConnectTarget(ctx);
        if (selected != null && !selected.isEmpty()) {
            connectModeHint.setVisibility(View.VISIBLE);
            connectModeHint.setText("Direct connect enabled — tap same favorite for scan mode");
        } else {
            connectModeHint.setVisibility(View.GONE);
        }

        for (BtDeviceRecord r : meshFavs) {
            Button chip = new Button(ctx);
            chip.setAllCaps(false);
            chip.setText(BluetoothDeviceRegistry.getDisplayTitle(r));
            boolean isSel = selected != null && selected.equalsIgnoreCase(r.address);
            applyPillButtonBackground(chip, isSel ? 0xFF00788B : 0xFF3D3D3D);
            chip.setTextColor(0xFFFFFFFF);
            int px = dip(ctx, 8);
            chip.setPadding(px, px / 2, px, px / 2);
            chip.setOnClickListener(v -> {
                String cur = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
                if (cur != null && cur.equalsIgnoreCase(r.address)) {
                    BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
                    appendLog("MeshCore using Scan & Connect mode");
                } else {
                    BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, r.address);
                    appendLog("MeshCore selected: " + BluetoothDeviceRegistry.getDisplayTitle(r));
                }
                refreshFavoriteStrip();
                updateScanButtonText();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dip(ctx, 6));
            favoritesStrip.addView(chip, lp);
        }
    }

    private boolean isLikelyMeshRecord(BtDeviceRecord record) {
        if (record == null) {
            return false;
        }
        String[] hints = {
                "meshcore", "meshtastic", "wismesh", "rak", "heltec", "lilygo",
                "seeed", "seed", "sensecap", "t-echo", "tdeck", "t-deck", "mesh"
        };
        String[] candidates = new String[]{
                record.customName,
                record.lastSystemName,
                BluetoothDeviceRegistry.getDisplayTitle(record)
        };
        for (String candidate : candidates) {
            if (candidate == null) continue;
            String n = candidate.toLowerCase(Locale.US);
            for (String hint : hints) {
                if (n.contains(hint)) return true;
            }
        }
        return false;
    }

    private String resolveDeviceDisplayName(Context ctx, BluetoothDevice device) {
        try {
            BtDeviceRecord r = BluetoothDeviceRegistry.find(ctx, device.getAddress());
            if (r != null) {
                return BluetoothDeviceRegistry.getDisplayTitle(r);
            }
        } catch (Exception ignored) {
        }
        String n = device.getName();
        return n != null ? n : device.getAddress();
    }

    private String getFavoriteDirectConnectTarget(Context ctx) {
        String target = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
        if (target == null || target.isEmpty()) {
            return null;
        }
        BtDeviceRecord r = BluetoothDeviceRegistry.find(ctx, target);
        if (r == null || !r.favorite || !isLikelyMeshRecord(r)) {
            // Guard against stale/accidental targets so new users stay in scan mode.
            BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
            return null;
        }
        return target;
    }

    private int getId(String name) {
        return pluginContext.getResources().getIdentifier(
                name, "id", pluginContext.getPackageName());
    }

    private int dip(Context c, int d) {
        return (int) (d * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable buildPillButtonBackground(int fillColor, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dip(getMapView().getContext(), PILL_CORNER_RADIUS_DP));
        if (strokeColor != 0) {
            drawable.setStroke(dip(getMapView().getContext(), 1), strokeColor);
        }
        return drawable;
    }

    private void applyPillButtonBackground(Button button, int fillColor) {
        if (button == null) {
            return;
        }
        button.setBackgroundTintList(null);
        button.setBackground(buildPillButtonBackground(fillColor, 0x00000000));
    }

    private void startConnectButtonPulse() {
        if (btnScan == null) {
            return;
        }
        stopConnectButtonPulse(false);
        btnScan.setBackgroundTintList(null);
        btnScan.setAlpha(1f);
        connectPulseDrawable = buildPillButtonBackground(COLOR_PILL_BUTTON_PRIMARY, 0xFFFFEB3B);
        btnScan.setBackground(connectPulseDrawable);
        connectPulseAnimator = ValueAnimator.ofFloat(1.0f, 0.62f);
        connectPulseAnimator.setDuration(560L);
        connectPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        connectPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        connectPulseAnimator.addUpdateListener(animation -> {
            if (btnScan == null) return;
            float alpha = (Float) animation.getAnimatedValue();
            btnScan.setAlpha(alpha);
            btnScan.invalidate();
        });
        connectPulseAnimator.start();
    }

    private void startScanDiscoveryPulse() {
        scanDiscoveryPulseActive = true;
        scanPulseBright = false;
        getMapView().removeCallbacks(scanDiscoveryPulseRunnable);
        // Keep scan pulse independent from touch/ripple pressed-state behavior.
        stopConnectButtonPulse(false);
        getMapView().post(scanDiscoveryPulseRunnable);
    }

    private void stopScanDiscoveryPulse() {
        if (!scanDiscoveryPulseActive) {
            return;
        }
        scanDiscoveryPulseActive = false;
        getMapView().removeCallbacks(scanDiscoveryPulseRunnable);
        if (!btManager.isConnecting() && !btManager.isConnected()) {
            stopConnectButtonPulse(true);
        }
    }

    private void stopConnectButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = connectPulseAnimator;
        connectPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
        }
        if (btnScan != null) {
            btnScan.setAlpha(1f);
        }
        connectPulseDrawable = null;
        if (restoreBackground && btnScan != null) {
            applyPillButtonBackground(btnScan, COLOR_PILL_BUTTON_PRIMARY);
        }
    }

    private void appendLog(String line) {
        if (line == null) return;
        String stamped = String.format(Locale.US, "[%tT] %s", System.currentTimeMillis(), line);
        logLines.add(stamped);
        while (logLines.size() > MAX_LOG_LINES) {
            logLines.removeFirst();
        }
        if (logText != null) {
            StringBuilder sb = new StringBuilder();
            for (String l : logLines) {
                sb.append(l).append('\n');
            }
            logText.setText(sb.toString());
        }
    }

    private void updateContactCount() {
        if (contactsText != null) {
            int active = contactTracker.getActiveCount();
            int total = contactTracker.getTotalCount();
            contactsText.setText(active + " active / " + total + " total");
        }
    }

    private void updatePacketCount() {
        if (packetsText != null) {
            packetsText.setText(txCount + " / " + rxCount);
        }
    }

    @Override
    public void onConnected(BluetoothDevice device) {
        if (device != null) {
            Context ctx = getMapView().getContext();
            BluetoothDeviceRegistry.recordConnection(ctx, device, false);
        }
        String display = device != null
                ? resolveDeviceDisplayName(getMapView().getContext(), device)
                : "MeshCore";
        getMapView().post(() -> {
            stopScanDiscoveryPulse();
            stopConnectButtonPulse(true);
            MeshStatusOverlay.setConnected(true);
            updateConnectionUI(true, display);
            updateMeshGpsControlsUi();
            refreshFavoriteStrip();
            appendLog("Connected to " + display);
            btManager.queryMeshGpsEnabled();
        });
    }

    @Override
    public void onDisconnected(String reason) {
        getMapView().post(() -> {
            stopScanDiscoveryPulse();
            stopConnectButtonPulse(true);
            MeshStatusOverlay.setConnected(false);
            pendingManualMeshGpsUpdate = false;
            pendingManualMeshGpsSinceMs = 0L;
            getMapView().removeCallbacks(manualMeshGpsTimeoutRunnable);
            updateConnectionUI(false, null);
            updateMeshGpsControlsUi();
            appendLog("Disconnected: " + reason);
        });
    }

    @Override
    public void onError(String error) {
        getMapView().post(() -> {
            stopScanDiscoveryPulse();
            stopConnectButtonPulse(true);
            MeshStatusOverlay.setConnected(false);
            appendLog("Error: " + error);
        });
    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        String address = device.getAddress();
        for (BluetoothDevice d : foundDevices) {
            if (d != null && address != null && address.equalsIgnoreCase(d.getAddress())) {
                return;
            }
        }
        foundDevices.add(device);
        if (!scanFoundAnyDevice) {
            scanFoundAnyDevice = true;
            getMapView().post(() -> appendLog("MeshCore node discovered; finishing scan..."));
        }
    }

    @Override
    public void onScanComplete() {
        getMapView().post(() -> {
            stopScanDiscoveryPulse();
            showDevicePicker();
        });
    }

    @Override
    public void onContactUpdated(RadioContact contact) {
        getMapView().post(this::updateContactCount);
    }

    @Override
    public void onContactRemoved(RadioContact contact) {
        getMapView().post(this::updateContactCount);
    }

    @Override
    public void onContactCountChanged(int count) {
        getMapView().post(this::updateContactCount);
    }

    @Override
    public void onPacketReceived() {
        rxCount++;
        getMapView().post(this::updatePacketCount);
    }

    @Override
    public void onPacketTransmitted() {
        txCount++;
        getMapView().post(this::updatePacketCount);
    }

    @Override
    public void onDropDownClose() {
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownVisible(boolean visible) {
        if (visible) {
            refreshFavoriteStrip();
            updateScanButtonText();
        }
    }

    @Override
    public void disposeImpl() {
        btManager.removeListener(this);
        btManager.removeMeshStateListener(meshStateListener);
        contactTracker.setListener(null);
        stopConnectButtonPulse(true);
        pendingManualMeshGpsUpdate = false;
        pendingManualMeshGpsSinceMs = 0L;
        getMapView().removeCallbacks(manualMeshGpsTimeoutRunnable);
    }
}
