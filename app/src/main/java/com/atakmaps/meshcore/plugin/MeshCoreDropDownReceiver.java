package com.atakmaps.meshcore.plugin;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.MetaDataHolder2;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.toolbar.ToolManagerBroadcastReceiver;
import com.atakmap.android.toolbar.widgets.TextContainer;
import com.atakmap.android.user.MapClickTool;
import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.beacon.SmartBeacon;
import com.atakmaps.meshcore.plugin.beacon.SmartBeaconSettingsDialog;
import com.atakmaps.meshcore.plugin.bluetooth.BluetoothDeviceRegistry;
import com.atakmaps.meshcore.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.bluetooth.MeshBleDeviceMatcher;
import com.atakmaps.meshcore.plugin.contacts.ContactTracker;
import com.atakmaps.meshcore.plugin.contacts.RadioContact;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.protocol.MeshCorePacket;
import com.atakmaps.meshcore.plugin.protocol.PacketRouter;
import com.atakmaps.meshcore.plugin.protocol.PingReplyNotifier;
import com.atakmaps.meshcore.plugin.ui.MeshStatusOverlay;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;

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
    private static final String EXTRA_MESH_NODE_POSITION_PICK_RESULT =
            "mesh_node_position_pick_result";
    private static final String TAG = "MeshCore.UI";
    private static final int MAX_LOG_LINES = 50;
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int EDIT_SELECTION_STROKE_DP = 3;
    private static final String PREF_MESH_SHOW_REPEATERS = "meshcore_mesh_show_repeaters";
    private static final String PREF_MESH_SHOW_NODES = "meshcore_mesh_show_nodes";
    private static final String PREF_MESH_REPEATER_CACHE = "meshcore_mesh_repeater_cache_v1";
    private static final String PREF_MESH_NODE_CACHE = "meshcore_mesh_node_cache_v1";
    private static final String PREF_MESH_SEND_POSITION_WITH_ADVERT =
            "meshcore_mesh_send_position_with_advert";
    private static final String PREF_MESH_USE_GPS_FOR_POSITION =
            "meshcore_mesh_use_gps_for_position";
    private static final String PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION =
            "meshcore_mesh_use_callsign_location_for_position";
    private static final String PREF_MESH_MAP_SET_POSITION_LAT =
            "meshcore_mesh_map_set_position_lat";
    private static final String PREF_MESH_MAP_SET_POSITION_LON =
            "meshcore_mesh_map_set_position_lon";
    private static final long MESH_CALLSIGN_POSITION_PUSH_INTERVAL_MS = 15_000L;
    private static final String MESH_NODE_MAP_POSITION_UID = "MESHCORE-NODE-MAP-POSITION";
    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";
    private static final String ANDROID_MESH_NODE_UID_PREFIX = "ANDROID-MESHCORE-NODE-";
    private static final String ANDROID_MESH_RPTR_UID_PREFIX = "ANDROID-MESHCORE-RPTR-";
    private static final double[] MESH_BANDWIDTH_OPTIONS_KHZ = new double[]{
            7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0
    };
    private static final int[] MESH_SPREADING_FACTOR_OPTIONS = new int[]{
            5, 6, 7, 8, 9, 10, 11, 12
    };
    private static final int[] MESH_CODING_RATE_OPTIONS = new int[]{
            5, 6, 7, 8
    };
    private static final int MAX_CHANNEL_LOG_LINES = 80;

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final ContactTracker contactTracker;
    private final CotBridge cotBridge;
    private EncryptionManager encryptionManager;

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
    private Button btnSendPing;
    private Button btnBeaconSettings;
    private Button btnPluginSettings;
    private Button btnSmartBeaconSettings;
    private Button btnMeshSendAdvert;
    private Switch switchSmartBeacon;
    private Switch switchMeshEnableGps;
    private Button btnUpdateGpsFromMeshcore;
    private Switch switchMeshShowRepeaters;
    private Switch switchMeshShowNodes;
    private Button btnMeshRequestChannels;
    private TextView meshChannelLogText;
    private EditText editMeshChannelIndex;
    private EditText editMeshChannelMessage;
    private Button btnMeshChannelSend;
    private Switch switchMeshSendPositionWithAdvert;
    private Switch switchMeshUseCallsignLocation;
    private TextView textMeshUseCallsignLocation;
    private Button btnClearMeshContacts;
    private Button btnMeshNodeSettings;
    private Button btnMeshcoreSetNodePositionMap;

    private boolean meshGpsEnableRequested = false;
    private Boolean meshSendPositionWithAdvertState = null;
    private boolean meshSendPositionWithAdvertRequested = false;
    private boolean suppressMeshSendPositionWithAdvertSwitchCallbacks = false;
    private BtConnectionManager.MeshNodeSettings meshNodeSettingsState;
    private AlertDialog meshNodeSettingsDialog;
    private EditText meshNodeSettingsNameField;
    private EditText meshNodeSettingsFrequencyField;
    private Spinner meshNodeSettingsBandwidthSpinner;
    private Spinner meshNodeSettingsSfSpinner;
    private Spinner meshNodeSettingsCrSpinner;
    private EditText meshNodeSettingsTxPowerField;
    private TextView meshNodeSettingsStatus;
    private ScrollView meshNodeSettingsScrollView;
    private final Runnable meshCallsignPositionSyncRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                pushPhoneLocationToMeshNodeIfNeeded(false);
            } finally {
                scheduleMeshCallsignPositionSync();
            }
        }
    };

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
                    meshGpsEnableRequested = enabled;
                    getMapView().post(() -> {
                        setMeshUseGpsForPositionPreference(enabled);
                        updateMeshGpsControlsUi();
                        appendLog("Use Meschore GPS for position "
                                + (enabled ? "enabled" : "disabled"));
                        if (enabled) {
                            removeMeshNodeMapPositionMarker(true);
                        }
                        scheduleMeshCallsignPositionSync();
                    });
                }

                @Override
                public void onSendPositionWithAdvertChanged(boolean enabled) {
                    meshSendPositionWithAdvertState = enabled;
                    meshSendPositionWithAdvertRequested = enabled;
                    getMapView().post(() -> {
                        setMeshSendPositionWithAdvertPreference(enabled);
                        updateMeshGpsControlsUi();
                        appendLog("Send Position With Advert "
                                + (enabled ? "enabled" : "disabled"));
                        scheduleMeshCallsignPositionSync();
                    });
                }

                @Override
                public void onMeshNodeSettingsUpdated(BtConnectionManager.MeshNodeSettings settings) {
                    meshNodeSettingsState = settings;
                    getMapView().post(() -> {
                        refreshMeshNodeSettingsDialogFromState(false);
                        updateMeshNodeMapPositionMarkerLabel();
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
    private final LinkedList<String> meshChannelLogLines = new LinkedList<>();
    private final BtConnectionManager.MeshChannelListener meshChannelListener =
            new BtConnectionManager.MeshChannelListener() {
                @Override
                public void onChannelInfo(BtConnectionManager.MeshChannelInfo info) {
                    if (info == null) {
                        return;
                    }
                    // Hide the internal ATAK data channel from the user-facing channel list.
                    if (info.name != null && "ATAK_DATA".equalsIgnoreCase(info.name.trim())) {
                        return;
                    }
                    getMapView().post(() -> appendChannelLog(
                            "Channel " + info.index + ": "
                                    + (info.name != null ? info.name : "")));
                }

                @Override
                public void onChannelMessage(BtConnectionManager.MeshChannelMessage message) {
                    if (message == null) {
                        return;
                    }
                    getMapView().post(() -> {
                        String dir = message.outbound ? ">>" : "<<";
                        appendChannelLog("[ch " + message.channelIndex + "] " + dir + " "
                                + (message.text != null ? message.text : ""));
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
        btManager.addMeshChannelListener(meshChannelListener);
        contactTracker.setListener(this);
    }

    public void setEncryptionManager(EncryptionManager encryptionManager) {
        this.encryptionManager = encryptionManager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SHOW_PLUGIN.equals(intent.getAction())) {
            if (intent.getBooleanExtra(EXTRA_MESH_NODE_POSITION_PICK_RESULT, false)) {
                handleMeshNodePositionPickResult(intent);
                return;
            }
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
                        "meshcore_dropdown", "layout", pluginContext.getPackageName()),
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
        updateMeshUseCallsignLocationLabel(callsign);

        Context initCtx = getMapView().getContext();
        meshGpsEnableRequested = getMeshUseGpsForPositionPreference(initCtx);
        meshSendPositionWithAdvertState = btManager.getSendPositionWithAdvertEnabled();
        if (meshSendPositionWithAdvertState == null) {
            meshSendPositionWithAdvertRequested = getMeshSendPositionWithAdvertPreference(initCtx);
        } else {
            meshSendPositionWithAdvertRequested =
                    Boolean.TRUE.equals(meshSendPositionWithAdvertState);
        }
        meshNodeSettingsState = btManager.getLatestNodeSettings();

        if (btManager.isConnected()) {
            updateConnectionUI(true, btManager.getConnectedDeviceName());
            MeshStatusOverlay.setConnected(true);
            stopConnectButtonPulse(true);
            btManager.requestSelfInfo();
            if (meshSendPositionWithAdvertRequested) {
                btManager.setSendPositionWithAdvertEnabled(true);
            }
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
        try {
            android.content.SharedPreferences meshPrefs =
                    PreferenceManager.getDefaultSharedPreferences(getMapView().getContext());
            if (switchMeshShowRepeaters != null) {
                switchMeshShowRepeaters.setChecked(
                        meshPrefs.getBoolean(PREF_MESH_SHOW_REPEATERS, true));
            }
            if (switchMeshShowNodes != null) {
                switchMeshShowNodes.setChecked(
                        meshPrefs.getBoolean(PREF_MESH_SHOW_NODES, true));
            }
            if (switchMeshSendPositionWithAdvert != null) {
                switchMeshSendPositionWithAdvert.setChecked(
                        getMeshSendPositionWithAdvertPreference(initCtx));
            }
            if (switchMeshUseCallsignLocation != null) {
                switchMeshUseCallsignLocation.setChecked(
                        isMeshUseCallsignLocationPreferenceEnabled(initCtx));
            }
        } catch (Exception ignored) {
        }
        meshGpsEnabledState = btManager.getMeshGpsEnabled();
        updateMeshGpsControlsUi();
        scheduleMeshCallsignPositionSync();
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
        btnSendPing = rootView.findViewById(getId("btn_send_ping"));
        btnBeaconSettings = rootView.findViewById(getId("btn_beacon_settings"));
        btnPluginSettings = rootView.findViewById(getId("btn_plugin_settings"));
        btnSmartBeaconSettings = rootView.findViewById(getId("btn_smart_beacon_settings"));
        btnMeshSendAdvert = rootView.findViewById(getId("btn_meshcore_send_advert"));
        switchSmartBeacon = rootView.findViewById(getId("switch_smart_beacon"));
        switchMeshEnableGps = rootView.findViewById(getId("switch_mesh_enable_gps"));
        btnUpdateGpsFromMeshcore = rootView.findViewById(getId("btn_update_gps_from_meshcore"));
        switchMeshShowRepeaters = rootView.findViewById(getId("switch_mesh_show_repeaters"));
        switchMeshShowNodes = rootView.findViewById(getId("switch_mesh_show_nodes"));
        btnMeshRequestChannels = rootView.findViewById(getId("btn_mesh_request_channels"));
        meshChannelLogText = rootView.findViewById(getId("text_mesh_channel_log"));
        editMeshChannelIndex = rootView.findViewById(getId("edit_mesh_channel_index"));
        editMeshChannelMessage = rootView.findViewById(getId("edit_mesh_channel_message"));
        btnMeshChannelSend = rootView.findViewById(getId("btn_mesh_channel_send"));
        switchMeshSendPositionWithAdvert =
                rootView.findViewById(getId("switch_mesh_send_position_with_advert"));
        switchMeshUseCallsignLocation =
                rootView.findViewById(getId("switch_mesh_use_callsign_location"));
        textMeshUseCallsignLocation =
                rootView.findViewById(getId("text_mesh_use_callsign_location"));
        btnClearMeshContacts = rootView.findViewById(getId("btn_clear_mesh_contacts"));
        btnMeshNodeSettings = rootView.findViewById(getId("btn_mesh_node_settings"));
        btnMeshcoreSetNodePositionMap =
                rootView.findViewById(getId("btn_meshcore_set_node_position_map"));

        if (meshChannelLogText != null) {
            meshChannelLogText.setMovementMethod(new ScrollingMovementMethod());
        }

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
        if (btnSendPing != null) {
            btnSendPing.setOnClickListener(v -> sendPing());
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
        if (btnMeshSendAdvert != null) {
            btnMeshSendAdvert.setOnClickListener(v -> {
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before sending advert");
                    return;
                }
                pushPhoneLocationToMeshNodeIfNeeded(false);
                if (btManager.sendSelfAdvert()) {
                    appendLog("Requested MeshCore self advert");
                } else {
                    appendLog("Failed to request self advert");
                }
            });
        }
        if (switchMeshEnableGps != null) {
            switchMeshEnableGps.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before changing GPS state");
                    updateMeshGpsControlsUi();
                    return;
                }
                onMeshGpsToggleChanged(isChecked);
            });
        }
        if (switchMeshSendPositionWithAdvert != null) {
            switchMeshSendPositionWithAdvert.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshSendPositionWithAdvertSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (!btManager.isConnected()) {
                    updateMeshGpsControlsUi();
                    return;
                }
                meshSendPositionWithAdvertRequested = isChecked;
                if (!isChecked) {
                    meshSendPositionWithAdvertState = Boolean.FALSE;
                }
                updateMeshGpsControlsUi();
                appendLog("Setting Send Position With Advert " + (isChecked ? "ON..." : "OFF..."));
                setMeshSendPositionWithAdvertPreference(isChecked);
                btManager.setSendPositionWithAdvertEnabled(isChecked);
                scheduleMeshCallsignPositionSync();
                if (isChecked) {
                    pushPhoneLocationToMeshNodeIfNeeded(true);
                }
                btManager.requestSelfInfo();
            });
        }
        if (switchMeshUseCallsignLocation != null) {
            switchMeshUseCallsignLocation.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setMeshUseCallsignLocationPreference(isChecked);
                appendLog(isChecked
                        ? "Node advert position source: ATAK callsign location (dynamic)."
                        : "Node advert position source: node GPS -> map-set position -> none.");
                if (isChecked) {
                    removeMeshNodeMapPositionMarker(true);
                }
                scheduleMeshCallsignPositionSync();
                if (isChecked) {
                    pushPhoneLocationToMeshNodeIfNeeded(true);
                }
            });
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setOnClickListener(v -> requestManualMeshGpsUpdate());
        }
        if (btnClearMeshContacts != null) {
            btnClearMeshContacts.setOnClickListener(v -> confirmClearAllMeshContacts());
        }
        if (btnMeshNodeSettings != null) {
            btnMeshNodeSettings.setOnClickListener(v -> showMeshNodeSettingsDialog());
        }
        if (btnMeshcoreSetNodePositionMap != null) {
            btnMeshcoreSetNodePositionMap.setOnClickListener(v -> startMeshNodePositionMapPick());
        }
        if (switchMeshShowRepeaters != null) {
            switchMeshShowRepeaters.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setMeshBooleanPreference(PREF_MESH_SHOW_REPEATERS, isChecked);
                appendLog("Show repeaters " + (isChecked ? "enabled" : "disabled"));
            });
        }
        if (switchMeshShowNodes != null) {
            switchMeshShowNodes.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setMeshBooleanPreference(PREF_MESH_SHOW_NODES, isChecked);
                appendLog("Show nodes " + (isChecked ? "enabled" : "disabled"));
            });
        }
        if (btnMeshRequestChannels != null) {
            btnMeshRequestChannels.setOnClickListener(v -> {
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before requesting channels");
                    return;
                }
                btManager.requestAllChannelInfo();
                appendLog("Requested all channel info");
            });
        }
        if (btnMeshChannelSend != null) {
            btnMeshChannelSend.setOnClickListener(v -> sendMeshChannelText());
        }
    }

    private void setMeshBooleanPreference(String key, boolean value) {
        try {
            PreferenceManager.getDefaultSharedPreferences(getMapView().getContext())
                    .edit().putBoolean(key, value).apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not persist " + key, e);
        }
    }

    private void sendMeshChannelText() {
        if (!btManager.isConnected()) {
            appendLog("Connect to MeshCore before sending channel message");
            return;
        }
        if (editMeshChannelMessage == null) {
            return;
        }
        String text = editMeshChannelMessage.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        int channelIndex = 0;
        if (editMeshChannelIndex != null) {
            try {
                channelIndex = Integer.parseInt(editMeshChannelIndex.getText().toString().trim());
            } catch (NumberFormatException ignored) {
                channelIndex = 0;
            }
        }
        if (btManager.sendChannelText(channelIndex, text)) {
            editMeshChannelMessage.setText("");
            appendChannelLog("[ch " + channelIndex + "] >> " + text);
        } else {
            appendLog("Failed to send channel message");
        }
    }

    private void appendChannelLog(String line) {
        if (line == null) {
            return;
        }
        meshChannelLogLines.add(line);
        while (meshChannelLogLines.size() > MAX_CHANNEL_LOG_LINES) {
            meshChannelLogLines.removeFirst();
        }
        if (meshChannelLogText != null) {
            StringBuilder sb = new StringBuilder();
            for (String l : meshChannelLogLines) {
                sb.append(l).append('\n');
            }
            meshChannelLogText.setText(sb.toString());
        }
    }

    private void updateMeshGpsControlsUi() {
        boolean meshConnected = btManager != null && btManager.isConnected();
        if (switchMeshEnableGps != null) {
            suppressMeshGpsSwitchCallbacks = true;
            try {
                // Node GPS toggle is only meaningful once the node reports a GPS state
                // (not all nodes have GPS installed).
                boolean gpsCapabilityKnown = meshGpsEnabledState != null;
                switchMeshEnableGps.setEnabled(meshConnected && gpsCapabilityKnown);
                switchMeshEnableGps.setAlpha((meshConnected && gpsCapabilityKnown) ? 1f : 0.45f);
                switchMeshEnableGps.setChecked(
                        gpsCapabilityKnown
                                && (meshGpsEnableRequested
                                || Boolean.TRUE.equals(meshGpsEnabledState)));
            } finally {
                suppressMeshGpsSwitchCallbacks = false;
            }
        }
        if (switchMeshSendPositionWithAdvert != null) {
            suppressMeshSendPositionWithAdvertSwitchCallbacks = true;
            try {
                switchMeshSendPositionWithAdvert.setEnabled(meshConnected);
                switchMeshSendPositionWithAdvert.setChecked(
                        meshSendPositionWithAdvertRequested
                                || Boolean.TRUE.equals(meshSendPositionWithAdvertState));
            } finally {
                suppressMeshSendPositionWithAdvertSwitchCallbacks = false;
            }
        }
        if (switchMeshUseCallsignLocation != null) {
            switchMeshUseCallsignLocation.setEnabled(meshConnected);
        }
        boolean meshGpsOn = meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState);
        boolean gpsDrivenActionsEnabled = meshConnected && meshGpsOn;
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setVisibility(View.VISIBLE);
            btnUpdateGpsFromMeshcore.setEnabled(gpsDrivenActionsEnabled);
            btnUpdateGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.45f);
        }
        if (btnMeshcoreSetNodePositionMap != null) {
            btnMeshcoreSetNodePositionMap.setEnabled(meshConnected);
        }
    }

    private void updateMeshUseCallsignLocationLabel(String callsign) {
        if (textMeshUseCallsignLocation == null) {
            return;
        }
        String safe = (callsign == null || callsign.trim().isEmpty())
                ? "UNKNOWN" : callsign.trim();
        textMeshUseCallsignLocation.setText("Use " + safe + " location for position");
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

    private void sendPing() {
        if (!btManager.isConnected()) {
            appendLog("Not connected");
            return;
        }
        try {
            String callsign = getMapView().getSelfMarker().getMetaString("callsign", "UNKNOWN");
            MeshCorePacket packet = MeshCorePacket.createPingPacket(
                    CallsignUtil.toRadioCallsign(callsign));
            byte[] packetBytes = packet.encode();
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                packetBytes = encryptionManager.encrypt(packetBytes);
                if (packetBytes == null) {
                    appendLog("Ping encryption failed");
                    return;
                }
            }
            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(callsign, 0, packetBytes);
            btManager.sendKissFrame(frame.encode());
            PingReplyNotifier.notePingSent(getMapView().getContext());
            appendLog("Ping sent over MeshCore");
        } catch (Exception e) {
            appendLog("Ping failed: " + e.getMessage());
        }
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
            if (MeshBleDeviceMatcher.isKnownMeshRecord(r)) {
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
        if (r == null || !r.favorite) {
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
            Context ctx = getMapView().getContext();
            meshGpsEnableRequested = getMeshUseGpsForPositionPreference(ctx);
            meshSendPositionWithAdvertState = null;
            meshSendPositionWithAdvertRequested = getMeshSendPositionWithAdvertPreference(ctx);
            meshNodeSettingsState = null;
            updateConnectionUI(true, display);
            updateMeshGpsControlsUi();
            refreshFavoriteStrip();
            appendLog("Connected to " + display);
            btManager.queryMeshGpsEnabled();
            btManager.requestSelfInfo();
            if (meshSendPositionWithAdvertRequested) {
                btManager.setSendPositionWithAdvertEnabled(true);
            }
            scheduleMeshCallsignPositionSync();
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
            meshGpsEnabledState = null;
            meshGpsEnableRequested = getMeshUseGpsForPositionPreference(getMapView().getContext());
            meshSendPositionWithAdvertState = null;
            meshSendPositionWithAdvertRequested =
                    getMeshSendPositionWithAdvertPreference(getMapView().getContext());
            meshNodeSettingsState = null;
            updateConnectionUI(false, null);
            updateMeshGpsControlsUi();
            scheduleMeshCallsignPositionSync();
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

    private void onMeshGpsToggleChanged(boolean isChecked) {
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (meshGpsEnabledState == null) {
            updateMeshGpsControlsUi();
            return;
        }
        meshGpsEnableRequested = isChecked;
        setMeshUseGpsForPositionPreference(isChecked);
        if (!isChecked) {
            meshGpsEnabledState = Boolean.FALSE;
        }
        updateMeshGpsControlsUi();
        appendLog("Setting Use Meschore GPS for position " + (isChecked ? "ON..." : "OFF..."));
        if (isChecked) {
            removeMeshNodeMapPositionMarker(true);
        }
        scheduleMeshCallsignPositionSync();
        btManager.setMeshGpsEnabled(isChecked);
        btManager.queryMeshGpsEnabled();
        if (!isChecked) {
            pushPhoneLocationToMeshNodeIfNeeded(false);
        }
    }

    /**
     * Advert position priority when node GPS is OFF:
     * 1) dynamic ATAK self/callsign location if enabled, else
     * 2) map-picked node position, else
     * 3) no advert position override.
     */
    private boolean pushPhoneLocationToMeshNodeIfNeeded(boolean verboseSkipLog) {
        if (btManager == null || !btManager.isConnected()) {
            return false;
        }
        boolean advertPosOn = meshSendPositionWithAdvertRequested
                || Boolean.TRUE.equals(meshSendPositionWithAdvertState);
        if (!advertPosOn) {
            if (verboseSkipLog) {
                appendLog("Advert position override skipped (Send Position With Advert is OFF).");
            }
            return false;
        }
        boolean nodeGpsOn = meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState);
        if (nodeGpsOn) {
            if (verboseSkipLog) {
                appendLog("Advert position source: node GPS.");
            }
            return false;
        }
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        boolean useCallsign = isMeshUseCallsignLocationPreferenceEnabled(ctx);
        com.atakmap.coremap.maps.coords.GeoPoint gp;
        String source;
        if (useCallsign) {
            gp = getAtakSelfGeoPoint();
            source = "callsign location";
        } else {
            gp = getMeshMapSetPosition(ctx);
            source = "map-set node position";
        }
        if (gp == null || !gp.isValid()) {
            if (verboseSkipLog) {
                appendLog("Advert position source unavailable (" + source + ").");
            }
            return false;
        }
        boolean ok = btManager.setAdvertLatLon(
                gp.getLatitude(), gp.getLongitude(), gp.getAltitude());
        if (ok) {
            appendLog(String.format(Locale.US,
                    "Pushed %s to node advert: %.5f, %.5f",
                    source, gp.getLatitude(), gp.getLongitude()));
        } else if (verboseSkipLog) {
            appendLog("Advert position push failed.");
        }
        return ok;
    }

    private com.atakmap.coremap.maps.coords.GeoPoint getAtakSelfGeoPoint() {
        MapView mv = getMapView();
        if (mv == null || mv.getSelfMarker() == null
                || !(mv.getSelfMarker() instanceof com.atakmap.android.maps.PointMapItem)) {
            return null;
        }
        return ((com.atakmap.android.maps.PointMapItem) mv.getSelfMarker()).getPoint();
    }

    private void scheduleMeshCallsignPositionSync() {
        MapView mv = getMapView();
        if (mv == null) {
            return;
        }
        mv.removeCallbacks(meshCallsignPositionSyncRunnable);
        Context ctx = mv.getContext();
        boolean shouldRun = btManager != null && btManager.isConnected()
                && isMeshUseCallsignLocationPreferenceEnabled(ctx)
                && (meshSendPositionWithAdvertRequested
                || Boolean.TRUE.equals(meshSendPositionWithAdvertState))
                && !(meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState));
        if (shouldRun) {
            mv.postDelayed(meshCallsignPositionSyncRunnable, MESH_CALLSIGN_POSITION_PUSH_INTERVAL_MS);
        }
    }

    private void startMeshNodePositionMapPick() {
        if (btManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent callback = new Intent(SHOW_PLUGIN);
        callback.putExtra(EXTRA_MESH_NODE_POSITION_PICK_RESULT, true);
        Intent begin = new Intent(ToolManagerBroadcastReceiver.BEGIN_TOOL);
        begin.putExtra("tool", MapClickTool.TOOL_NAME);
        begin.putExtra("callback", callback);
        begin.putExtra("prompt", "Select node position on map");
        AtakBroadcast.getInstance().sendBroadcast(begin);
        appendLog("Pick a map location for node advert position...");
    }

    private void handleMeshNodePositionPickResult(Intent intent) {
        com.atakmap.coremap.maps.coords.GeoPoint gp = parseGeoPointFromIntent(intent);
        if (gp == null || !gp.isValid()) {
            return;
        }
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        setMeshMapSetPosition(ctx, gp);
        createOrUpdateMeshNodeMapPositionMarker(gp);
        appendLog(String.format(Locale.US, "Saved node map position: %.5f, %.5f",
                gp.getLatitude(), gp.getLongitude()));
        if (!isMeshUseCallsignLocationPreferenceEnabled(ctx)
                && !(meshGpsEnableRequested || Boolean.TRUE.equals(meshGpsEnabledState))) {
            pushPhoneLocationToMeshNodeIfNeeded(true);
        }
        try {
            TextContainer.getTopInstance().closePrompt("Select node position on map");
        } catch (Exception ignored) {
        }
    }

    private com.atakmap.coremap.maps.coords.GeoPoint parseGeoPointFromIntent(Intent intent) {
        if (intent == null) {
            return null;
        }
        String point = intent.getStringExtra("point");
        if (point == null || point.trim().isEmpty()) {
            return null;
        }
        String[] parts = point.split(",");
        if (parts.length < 2) {
            return null;
        }
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            return new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void createOrUpdateMeshNodeMapPositionMarker(com.atakmap.coremap.maps.coords.GeoPoint gp) {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null || gp == null || !gp.isValid()) {
            return;
        }
        removeMeshNodeMapPositionMarker(false);
        Marker marker = new Marker(gp, MESH_NODE_MAP_POSITION_UID);
        marker.setType("b-m-p-s-p-i");
        String nodeLabel = resolveMeshNodeDisplayName();
        marker.setTitle(nodeLabel);
        marker.setMetaString("callsign", nodeLabel);
        marker.setMetaBoolean("editable", true);
        marker.setMetaBoolean("movable", false);
        mv.getRootGroup().addItem(marker);
    }

    private String resolveMeshNodeDisplayName() {
        String name = null;
        if (meshNodeSettingsState != null && meshNodeSettingsState.nodeName != null) {
            name = meshNodeSettingsState.nodeName.trim();
        }
        if (name == null || name.isEmpty()) {
            name = "Mesh Node";
        }
        return name;
    }

    private void removeMeshNodeMapPositionMarker(boolean clearStoredPosition) {
        MapView mv = getMapView();
        if (mv != null && mv.getRootGroup() != null) {
            removeMapItemByUidRecursive(mv.getRootGroup(), MESH_NODE_MAP_POSITION_UID);
        }
        if (clearStoredPosition) {
            Context ctx = mv != null ? mv.getContext() : pluginContext;
            clearMeshMapSetPosition(ctx);
        }
    }

    private boolean removeMapItemByUidRecursive(MapGroup group, String uid) {
        if (group == null || uid == null || uid.trim().isEmpty()) {
            return false;
        }
        for (MapItem item : new ArrayList<>(group.getItems())) {
            if (item != null && uid.equals(item.getUID())) {
                group.removeItem(item);
                return true;
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            if (removeMapItemByUidRecursive(child, uid)) {
                return true;
            }
        }
        return false;
    }

    private void updateMeshNodeMapPositionMarkerLabel() {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null) {
            return;
        }
        String label = resolveMeshNodeDisplayName();
        updateMapItemTitleByUidRecursive(mv.getRootGroup(), MESH_NODE_MAP_POSITION_UID, label);
    }

    private boolean updateMapItemTitleByUidRecursive(MapGroup group, String uid, String title) {
        if (group == null || uid == null || uid.trim().isEmpty()) {
            return false;
        }
        for (MapItem item : new ArrayList<>(group.getItems())) {
            if (item == null || !uid.equals(item.getUID())) {
                continue;
            }
            if (item instanceof Marker) {
                Marker marker = (Marker) item;
                marker.setTitle(title);
                marker.setMetaString("callsign", title);
            } else {
                item.setMetaString("callsign", title);
            }
            return true;
        }
        for (MapGroup child : group.getChildGroups()) {
            if (updateMapItemTitleByUidRecursive(child, uid, title)) {
                return true;
            }
        }
        return false;
    }

    private void showMeshNodeSettingsDialog() {
        if (btManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (meshNodeSettingsDialog == null) {
            buildMeshNodeSettingsDialog();
        }
        meshNodeSettingsDialog.show();
        if (meshNodeSettingsScrollView != null) {
            meshNodeSettingsScrollView.post(() -> {
                meshNodeSettingsScrollView.scrollTo(0, 0);
                meshNodeSettingsScrollView.fullScroll(View.FOCUS_UP);
            });
        }
        refreshMeshNodeSettingsDialogFromState(true);
        appendLog("Polling MeshCore node settings...");
        btManager.requestSelfInfo();
    }

    private void buildMeshNodeSettingsDialog() {
        Context ctx = getMapView().getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 12);
        root.setPadding(pad, pad, pad, pad);

        TextView nameLabel = new TextView(ctx);
        nameLabel.setText("Name");
        nameLabel.setTextColor(0xFFFFFFFF);
        root.addView(nameLabel);

        meshNodeSettingsNameField = new EditText(ctx);
        meshNodeSettingsNameField.setHint("Node name");
        meshNodeSettingsNameField.setSingleLine(true);
        root.addView(meshNodeSettingsNameField);

        TextView section = new TextView(ctx);
        section.setText("Radio Settings");
        section.setTextColor(0xFF00BCD4);
        section.setTextSize(14f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.topMargin = dip(ctx, 12);
        root.addView(section, sectionLp);

        TextView freqLabel = new TextView(ctx);
        freqLabel.setText("Frequency (MHz)");
        freqLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams freqLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        freqLabelLp.topMargin = dip(ctx, 6);
        root.addView(freqLabel, freqLabelLp);

        meshNodeSettingsFrequencyField = new EditText(ctx);
        meshNodeSettingsFrequencyField.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        meshNodeSettingsFrequencyField.setHint("910.525");
        meshNodeSettingsFrequencyField.setSingleLine(true);
        root.addView(meshNodeSettingsFrequencyField);

        TextView bwLabel = new TextView(ctx);
        bwLabel.setText("Bandwidth");
        bwLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams bwLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bwLabelLp.topMargin = dip(ctx, 6);
        root.addView(bwLabel, bwLabelLp);

        meshNodeSettingsBandwidthSpinner = new Spinner(ctx);
        meshNodeSettingsBandwidthSpinner.setAdapter(new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, meshBandwidthLabels()));
        ((ArrayAdapter<?>) meshNodeSettingsBandwidthSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        root.addView(meshNodeSettingsBandwidthSpinner);

        TextView sfLabel = new TextView(ctx);
        sfLabel.setText("Spreading Factor");
        sfLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams sfLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sfLabelLp.topMargin = dip(ctx, 6);
        root.addView(sfLabel, sfLabelLp);

        meshNodeSettingsSfSpinner = new Spinner(ctx);
        meshNodeSettingsSfSpinner.setAdapter(new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, meshSfLabels()));
        ((ArrayAdapter<?>) meshNodeSettingsSfSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        root.addView(meshNodeSettingsSfSpinner);

        TextView crLabel = new TextView(ctx);
        crLabel.setText("Coding Rate");
        crLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams crLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLabelLp.topMargin = dip(ctx, 6);
        root.addView(crLabel, crLabelLp);

        meshNodeSettingsCrSpinner = new Spinner(ctx);
        meshNodeSettingsCrSpinner.setAdapter(new ArrayAdapter<>(
                ctx, android.R.layout.simple_spinner_item, meshCrLabels()));
        ((ArrayAdapter<?>) meshNodeSettingsCrSpinner.getAdapter())
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        root.addView(meshNodeSettingsCrSpinner);

        TextView txPowerLabel = new TextView(ctx);
        txPowerLabel.setText("Transmit Power (dBm)");
        txPowerLabel.setTextColor(0xFFFFFFFF);
        LinearLayout.LayoutParams txPowerLabelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txPowerLabelLp.topMargin = dip(ctx, 6);
        root.addView(txPowerLabel, txPowerLabelLp);

        meshNodeSettingsTxPowerField = new EditText(ctx);
        meshNodeSettingsTxPowerField.setInputType(
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        meshNodeSettingsTxPowerField.setSingleLine(true);
        meshNodeSettingsTxPowerField.setHint("22");
        root.addView(meshNodeSettingsTxPowerField);

        meshNodeSettingsStatus = new TextView(ctx);
        meshNodeSettingsStatus.setTextColor(0xFF90A4AE);
        meshNodeSettingsStatus.setTextSize(11f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.topMargin = dip(ctx, 8);
        root.addView(meshNodeSettingsStatus, statusLp);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        meshNodeSettingsScrollView = scroll;

        meshNodeSettingsDialog = new AlertDialog.Builder(ctx)
                .setTitle("Node Settings")
                .setView(scroll)
                .setPositiveButton("Apply", null)
                .setNeutralButton("Refresh", null)
                .setNegativeButton("Close", null)
                .create();
        meshNodeSettingsDialog.setOnShowListener(dialog -> {
            Button apply = meshNodeSettingsDialog.getButton(DialogInterface.BUTTON_POSITIVE);
            if (apply != null) {
                apply.setOnClickListener(v -> applyMeshNodeSettingsFromDialog());
            }
            Button refresh = meshNodeSettingsDialog.getButton(DialogInterface.BUTTON_NEUTRAL);
            if (refresh != null) {
                refresh.setOnClickListener(v -> {
                    appendLog("Polling MeshCore node settings...");
                    if (btManager != null) {
                        btManager.requestSelfInfo();
                    }
                });
            }
        });
    }

    private void applyMeshNodeSettingsFromDialog() {
        if (btManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "MeshCore not connected.", Toast.LENGTH_SHORT).show();
            return;
        }
        String nodeName = meshNodeSettingsNameField != null
                ? meshNodeSettingsNameField.getText().toString().trim() : "";
        String freqRaw = meshNodeSettingsFrequencyField != null
                ? meshNodeSettingsFrequencyField.getText().toString().trim() : "";
        String txRaw = meshNodeSettingsTxPowerField != null
                ? meshNodeSettingsTxPowerField.getText().toString().trim() : "";
        if (nodeName.isEmpty() || freqRaw.isEmpty() || txRaw.isEmpty()) {
            Toast.makeText(getMapView().getContext(),
                    "Name, Frequency, and Transmit Power are required.", Toast.LENGTH_SHORT).show();
            return;
        }
        double freqMHz;
        try {
            freqMHz = Double.parseDouble(freqRaw);
        } catch (Exception e) {
            Toast.makeText(getMapView().getContext(),
                    "Invalid frequency value.", Toast.LENGTH_SHORT).show();
            return;
        }
        int txPowerDbm;
        try {
            txPowerDbm = Integer.parseInt(txRaw);
        } catch (Exception e) {
            Toast.makeText(getMapView().getContext(),
                    "Invalid transmit power value.", Toast.LENGTH_SHORT).show();
            return;
        }
        double bwKHz = MESH_BANDWIDTH_OPTIONS_KHZ[
                Math.max(0, meshNodeSettingsBandwidthSpinner != null
                        ? meshNodeSettingsBandwidthSpinner.getSelectedItemPosition() : 0)];
        int sf = MESH_SPREADING_FACTOR_OPTIONS[
                Math.max(0, meshNodeSettingsSfSpinner != null
                        ? meshNodeSettingsSfSpinner.getSelectedItemPosition() : 0)];
        int cr = MESH_CODING_RATE_OPTIONS[
                Math.max(0, meshNodeSettingsCrSpinner != null
                        ? meshNodeSettingsCrSpinner.getSelectedItemPosition() : 0)];

        boolean nameOk = btManager.setNodeAdvertName(nodeName);
        boolean radioOk = btManager.setRadioParams(freqMHz, bwKHz, sf, cr);
        boolean txOk = btManager.setRadioTxPowerDbm(txPowerDbm);
        if (!nameOk || !radioOk || !txOk) {
            Toast.makeText(getMapView().getContext(),
                    "Failed to apply one or more settings.", Toast.LENGTH_SHORT).show();
            return;
        }
        appendLog(String.format(Locale.US,
                "Node settings applied: name=%s freq=%.3f bw=%s sf=%d cr=%d tx=%d dBm",
                nodeName, freqMHz, trimDouble(bwKHz), sf, cr, txPowerDbm));
        Toast.makeText(getMapView().getContext(),
                "Node settings applied.", Toast.LENGTH_SHORT).show();
        btManager.requestSelfInfo();
    }

    private void refreshMeshNodeSettingsDialogFromState(boolean initializing) {
        if (meshNodeSettingsDialog == null || !meshNodeSettingsDialog.isShowing()) {
            return;
        }
        if (meshNodeSettingsState == null) {
            if (meshNodeSettingsStatus != null) {
                meshNodeSettingsStatus.setText("Waiting for node settings response...");
            }
            return;
        }
        if (meshNodeSettingsNameField != null) {
            meshNodeSettingsNameField.setText(meshNodeSettingsState.nodeName != null
                    ? meshNodeSettingsState.nodeName : "");
        }
        if (meshNodeSettingsFrequencyField != null) {
            meshNodeSettingsFrequencyField.setText(String.format(
                    Locale.US, "%.3f", meshNodeSettingsState.frequencyMHz));
        }
        if (meshNodeSettingsBandwidthSpinner != null) {
            meshNodeSettingsBandwidthSpinner.setSelection(
                    nearestBandwidthIndex(meshNodeSettingsState.bandwidthKHz));
        }
        if (meshNodeSettingsSfSpinner != null) {
            meshNodeSettingsSfSpinner.setSelection(indexOfIntOption(
                    MESH_SPREADING_FACTOR_OPTIONS, meshNodeSettingsState.spreadingFactor));
        }
        if (meshNodeSettingsCrSpinner != null) {
            meshNodeSettingsCrSpinner.setSelection(indexOfIntOption(
                    MESH_CODING_RATE_OPTIONS, meshNodeSettingsState.codingRate));
        }
        if (meshNodeSettingsTxPowerField != null) {
            meshNodeSettingsTxPowerField.setText(String.valueOf(meshNodeSettingsState.txPowerDbm));
        }
        if (meshNodeSettingsStatus != null) {
            meshNodeSettingsStatus.setText(String.format(Locale.US,
                    "Node response: %.3f MHz, %s kHz, SF%d, CR%d, TX %d dBm (max %d)",
                    meshNodeSettingsState.frequencyMHz,
                    trimDouble(meshNodeSettingsState.bandwidthKHz),
                    meshNodeSettingsState.spreadingFactor,
                    meshNodeSettingsState.codingRate,
                    meshNodeSettingsState.txPowerDbm,
                    meshNodeSettingsState.maxTxPowerDbm));
        }
        if (!initializing) {
            appendLog("MeshCore node settings updated from node poll.");
        }
    }

    private static String[] meshBandwidthLabels() {
        String[] out = new String[MESH_BANDWIDTH_OPTIONS_KHZ.length];
        for (int i = 0; i < MESH_BANDWIDTH_OPTIONS_KHZ.length; i++) {
            out[i] = trimDouble(MESH_BANDWIDTH_OPTIONS_KHZ[i]) + " kHz";
        }
        return out;
    }

    private static String[] meshSfLabels() {
        String[] out = new String[MESH_SPREADING_FACTOR_OPTIONS.length];
        for (int i = 0; i < MESH_SPREADING_FACTOR_OPTIONS.length; i++) {
            out[i] = "SF" + MESH_SPREADING_FACTOR_OPTIONS[i];
        }
        return out;
    }

    private static String[] meshCrLabels() {
        String[] out = new String[MESH_CODING_RATE_OPTIONS.length];
        for (int i = 0; i < MESH_CODING_RATE_OPTIONS.length; i++) {
            out[i] = "CR" + MESH_CODING_RATE_OPTIONS[i];
        }
        return out;
    }

    private static int indexOfIntOption(int[] options, int value) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == value) {
                return i;
            }
        }
        return 0;
    }

    private static int nearestBandwidthIndex(double valueKHz) {
        int bestIdx = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < MESH_BANDWIDTH_OPTIONS_KHZ.length; i++) {
            double d = Math.abs(MESH_BANDWIDTH_OPTIONS_KHZ[i] - valueKHz);
            if (d < bestDiff) {
                bestDiff = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static String trimDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.0001) {
            return String.format(Locale.US, "%.0f", v);
        }
        if (Math.abs(v * 10.0 - Math.rint(v * 10.0)) < 0.0001) {
            return String.format(Locale.US, "%.1f", v);
        }
        if (Math.abs(v * 100.0 - Math.rint(v * 100.0)) < 0.0001) {
            return String.format(Locale.US, "%.2f", v);
        }
        return String.format(Locale.US, "%.3f", v);
    }

    private boolean getMeshSendPositionWithAdvertPreference(Context ctx) {
        if (ctx == null) {
            return true;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_MESH_SEND_POSITION_WITH_ADVERT, true);
    }

    private void setMeshSendPositionWithAdvertPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_MESH_SEND_POSITION_WITH_ADVERT, enabled).apply();
    }

    private boolean getMeshUseGpsForPositionPreference(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_MESH_USE_GPS_FOR_POSITION, false);
    }

    private void setMeshUseGpsForPositionPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_MESH_USE_GPS_FOR_POSITION, enabled).apply();
    }

    private boolean isMeshUseCallsignLocationPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION, false);
    }

    private void setMeshUseCallsignLocationPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION, enabled).apply();
    }

    private void setMeshMapSetPosition(Context ctx, com.atakmap.coremap.maps.coords.GeoPoint gp) {
        if (ctx == null || gp == null || !gp.isValid()) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .putString(PREF_MESH_MAP_SET_POSITION_LAT, Double.toString(gp.getLatitude()))
                .putString(PREF_MESH_MAP_SET_POSITION_LON, Double.toString(gp.getLongitude()))
                .apply();
    }

    private com.atakmap.coremap.maps.coords.GeoPoint getMeshMapSetPosition(Context ctx) {
        if (ctx == null) {
            return null;
        }
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        String latStr = prefs.getString(PREF_MESH_MAP_SET_POSITION_LAT, null);
        String lonStr = prefs.getString(PREF_MESH_MAP_SET_POSITION_LON, null);
        if (latStr == null || lonStr == null) {
            return null;
        }
        try {
            double lat = Double.parseDouble(latStr);
            double lon = Double.parseDouble(lonStr);
            com.atakmap.coremap.maps.coords.GeoPoint gp =
                    new com.atakmap.coremap.maps.coords.GeoPoint(lat, lon);
            return gp.isValid() ? gp : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void clearMeshMapSetPosition(Context ctx) {
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx).edit()
                .remove(PREF_MESH_MAP_SET_POSITION_LAT)
                .remove(PREF_MESH_MAP_SET_POSITION_LON)
                .apply();
    }

    private void confirmClearAllMeshContacts() {
        MapView mv = getMapView();
        Context ctx = mv != null ? mv.getContext() : pluginContext;
        new AlertDialog.Builder(ctx)
                .setTitle("Clear All Mesh Contacts")
                .setMessage("This will delete all repeaters and nodes from your map. Are you sure?")
                .setPositiveButton("Yes", (d, w) -> clearAllMeshContacts())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearAllMeshContacts() {
        MapView mv = getMapView();
        if (mv == null || mv.getRootGroup() == null) {
            return;
        }
        mv.post(() -> {
            int removedMarkers = removeMeshItemsRecursive(mv.getRootGroup());
            int removedContacts = removeMeshContactsFromContactStore();
            Context ctx = mv.getContext();
            if (ctx != null) {
                try {
                    PreferenceManager.getDefaultSharedPreferences(ctx)
                            .edit()
                            .remove(PREF_MESH_REPEATER_CACHE)
                            .remove(PREF_MESH_NODE_CACHE)
                            .remove(PREF_MESH_MAP_SET_POSITION_LAT)
                            .remove(PREF_MESH_MAP_SET_POSITION_LON)
                            .apply();
                } catch (Exception ignored) {
                }
            }
            String msg = "Cleared Mesh contacts: " + removedMarkers
                    + " markers, " + removedContacts + " contacts";
            appendLog(msg);
            Toast.makeText(mv.getContext(), msg, Toast.LENGTH_SHORT).show();
            updateContactCount();
        });
    }

    private int removeMeshItemsRecursive(MapGroup group) {
        if (group == null) {
            return 0;
        }
        int removed = 0;
        List<MapItem> items = new ArrayList<>(group.getItems());
        for (MapItem item : items) {
            if (item == null) {
                continue;
            }
            if (CotBridge.isMeshcoreMeshMarker(item)
                    || MESH_NODE_MAP_POSITION_UID.equals(item.getUID())) {
                group.removeItem(item);
                removed++;
            }
        }
        for (MapGroup child : group.getChildGroups()) {
            removed += removeMeshItemsRecursive(child);
        }
        return removed;
    }

    private int removeMeshContactsFromContactStore() {
        int removed = 0;
        try {
            Contacts contacts = Contacts.getInstance();
            List<Contact> all = contacts.getAllContacts();
            if (all == null) {
                return 0;
            }
            for (Contact c : new ArrayList<>(all)) {
                if (c == null) {
                    continue;
                }
                String uid = c.getUID();
                if (uid == null) {
                    continue;
                }
                String u = uid.trim().toUpperCase(Locale.US);
                if (u.startsWith(MESH_NODE_UID_PREFIX)
                        || u.startsWith(MESH_RPTR_UID_PREFIX)
                        || u.startsWith(ANDROID_MESH_NODE_UID_PREFIX)
                        || u.startsWith(ANDROID_MESH_RPTR_UID_PREFIX)) {
                    contacts.removeContact(c);
                    removed++;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "removeMeshContactsFromContactStore failed", e);
        }
        return removed;
    }

    @Override
    public void onDropDownClose() {
        if (getMapView() != null) {
            getMapView().removeCallbacks(meshCallsignPositionSyncRunnable);
        }
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
        btManager.removeMeshChannelListener(meshChannelListener);
        contactTracker.setListener(null);
        stopConnectButtonPulse(true);
        pendingManualMeshGpsUpdate = false;
        pendingManualMeshGpsSinceMs = 0L;
        getMapView().removeCallbacks(manualMeshGpsTimeoutRunnable);
        getMapView().removeCallbacks(meshCallsignPositionSyncRunnable);
    }
}
