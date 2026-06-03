package com.atakmaps.meshcore.plugin;

import android.animation.ArgbEvaluator;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
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
import com.atakmaps.meshcore.plugin.bluetooth.MeshBluetoothForgetAll;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private static final int COLOR_CONNECTION_STROKE_RED = 0xFFF44336;
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int EDIT_SELECTION_STROKE_DP = 3;
    private static final String PREF_MESH_CHANNEL_HISTORY = "meshcore_mesh_channel_history_v1";
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
    private static final String PREF_MESH_USE_CUSTOM_NODE_POSITION =
            "meshcore_mesh_use_custom_node_position";
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
    private ChatBridge chatBridge;

    public void setChatBridge(ChatBridge bridge) {
        this.chatBridge = bridge;
    }
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
    private Switch switchMeshEnableGpsConnection; // CONNECTION section power switch
    private Switch switchMeshEnableGpsHardware;   // MESHCORE section convenience copy
    private Switch switchMeshEnableGps;
    private Button btnUpdateGpsFromMeshcore;
    private Switch switchMeshShowRepeaters;
    private Switch switchMeshShowNodes;
    private Button btnMeshRequestChannels;
    private Button btnMeshcoreChannels;
    private TextView meshChannelLogText;
    private EditText editMeshChannelIndex;
    private EditText editMeshChannelMessage;
    private Button btnMeshChannelSend;
    private Switch switchMeshSendPositionWithAdvert;
    private Switch switchMeshUseCallsignLocation;
    private Switch switchMeshUseCustomNodePosition;
    private TextView textMeshUseCallsignLocation;
    private Button btnClearMeshContacts;
    private Button btnMeshNodeSettings;
    private Button btnMeshcoreSetNodePositionMap;
    private Switch switchEncryption;
    private View passphraseRow;
    private EditText editPassphrase;
    private Button btnSetPassphrase;
    private TextView encryptionStatusText;

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

    private static final int DOT_AVAILABLE = 0xFF00CC44;
    private static final int DOT_BUSY = 0xFFCC4444;
    private static final int DOT_UNSEEN = 0xFF888888;

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
    private static final String PREF_AUGMENT_GPS_FROM_MESHCORE =
            "meshcore_augment_gps_from_meshcore";
    private static final long MESH_GPS_AUGMENT_INTERVAL_MS = 120_000L;
    private Switch switchAugmentGpsFromMeshcore;
    private View rowAugmentGpsFromMeshcore;
    private final AtomicBoolean meshGpsAugmentUpdateInFlight = new AtomicBoolean(false);
    private final Runnable meshGpsAugmentRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                Context ctx = getMapView() != null ? getMapView().getContext() : null;
                if (ctx == null || !isAugmentMeshPreferenceEnabled(ctx)) {
                    return;
                }
                if (btManager == null || !btManager.isConnected()) {
                    return;
                }
                // Only augment when the phone's own GPS chip is not providing a fix.
                if (isPhoneGpsAvailable()) {
                    return;
                }
                if (!Boolean.TRUE.equals(meshGpsEnabledState)) {
                    btManager.queryMeshGpsEnabled();
                    return;
                }
                if (!meshGpsAugmentUpdateInFlight.compareAndSet(false, true)) {
                    return;
                }
                new Thread(() -> {
                    try {
                        updateGpsFromMeshcoreNow();
                    } finally {
                        meshGpsAugmentUpdateInFlight.set(false);
                    }
                }, "meshcore-gps-augment").start();
            } finally {
                scheduleMeshGpsAugmentTick();
            }
        }
    };
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
                    // Hardware on => node drives advert position, so node GPS is the source and the
                    // other (mutually-exclusive) sources are cleared. Hardware off => clear source.
                    meshGpsEnableRequested = enabled;
                    getMapView().post(() -> {
                        setMeshUseGpsForPositionPreference(enabled);
                        if (enabled) {
                            setMeshUseCallsignLocationPreference(false);
                            if (switchMeshUseCallsignLocation != null) {
                                switchMeshUseCallsignLocation.setChecked(false);
                            }
                            setMeshUseCustomNodePositionPreference(false);
                            if (switchMeshUseCustomNodePosition != null) {
                                switchMeshUseCustomNodePosition.setChecked(false);
                            }
                        }
                        updateMeshGpsControlsUi();
                        appendLog("MeshCore GPS hardware "
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
    private final Map<Integer, String> meshChannelNames = new HashMap<>();
    private final Map<Integer, LinkedList<BtConnectionManager.MeshChannelMessage>>
            meshChannelMessages = new HashMap<>();
    private AlertDialog meshChannelChatDialog;
    private TextView meshChannelChatLogView;
    private TextView meshChannelChatTitleView;
    private int meshChannelChatActiveIndex = -1;
    private static final int MAX_MESH_CHANNEL_MESSAGES = 120;
    private static final long MESH_CHANNEL_QUEUE_TIMEOUT_MS = 8000L;
    private boolean meshChannelHistoryLoaded = false;
    private final Runnable meshQueuedStatusTimeoutRunnable = this::applyMeshQueuedStatusTimeouts;
    private final BtConnectionManager.MeshChannelListener meshChannelListener =
            new BtConnectionManager.MeshChannelListener() {
                @Override
                public void onChannelInfo(BtConnectionManager.MeshChannelInfo info) {
                    if (info == null) {
                        return;
                    }
                    getMapView().post(() -> {
                        meshChannelNames.put(info.index, info.name);
                        persistMeshChannelHistory();
                        updateMeshChannelButtonLabel();
                    });
                }

                @Override
                public void onChannelMessage(BtConnectionManager.MeshChannelMessage message) {
                    if (message == null) {
                        return;
                    }
                    getMapView().post(() -> {
                        appendMeshChannelMessage(message);
                        if (meshChannelChatActiveIndex == message.channelIndex) {
                            renderMeshChannelChatLog(message.channelIndex);
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

        // Set version label dynamically so it always matches the Gradle PLUGIN_VERSION.
        try {
            android.widget.TextView versionView = rootView.findViewById(
                    pluginContext.getResources().getIdentifier(
                            "header_version", "id", pluginContext.getPackageName()));
            if (versionView != null) {
                versionView.setText("v" + com.atakmaps.meshcore.plugin.BuildConfig.PLUGIN_VERSION);
            }
        } catch (Exception ignored) {
        }

        bindViews();
        loadMeshChannelHistoryIfNeeded();
        setupListeners();
        // Status overlay is installed once from MeshCoreMapComponent.onCreate().

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
            btManager.requestAllChannelInfo();
            meshChannelNames.putAll(btManager.getKnownChannelNamesSnapshot());
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
            if (switchMeshUseCustomNodePosition != null) {
                switchMeshUseCustomNodePosition.setChecked(
                        isMeshUseCustomNodePositionPreferenceEnabled(initCtx));
            }
            if (switchAugmentGpsFromMeshcore != null) {
                switchAugmentGpsFromMeshcore.setChecked(isAugmentMeshPreferenceEnabled(initCtx));
            }
        } catch (Exception ignored) {
        }
        meshGpsEnabledState = btManager.getMeshGpsEnabled();
        updateMeshGpsControlsUi();
        boolean encOn = SettingsFragment.isEncryptionEnabled(initCtx);
        if (switchEncryption != null) {
            switchEncryption.setChecked(encOn);
        }
        if (passphraseRow != null) {
            passphraseRow.setVisibility(encOn ? View.VISIBLE : View.GONE);
        }
        updateEncryptionStatus();
        scheduleMeshCallsignPositionSync();
        scheduleMeshGpsAugmentTick();
        updateMeshChannelButtonLabel();
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
        btnBeaconSettings = rootView.findViewById(getId("btn_manage_plugin_beacon_settings"));
        btnPluginSettings = rootView.findViewById(getId("btn_plugin_settings"));
        btnSmartBeaconSettings = rootView.findViewById(getId("btn_manage_smart_beacon_settings"));
        btnMeshSendAdvert = rootView.findViewById(getId("btn_meshcore_send_advert"));
        switchSmartBeacon = rootView.findViewById(getId("switch_smart_beacon"));
        switchMeshEnableGpsConnection =
                rootView.findViewById(getId("switch_mesh_enable_gps_connection"));
        switchMeshEnableGpsHardware =
                rootView.findViewById(getId("switch_mesh_enable_gps_hardware"));
        switchMeshEnableGps = rootView.findViewById(getId("switch_mesh_enable_gps"));
        btnUpdateGpsFromMeshcore = rootView.findViewById(getId("btn_update_gps_from_meshcore"));
        switchAugmentGpsFromMeshcore =
                rootView.findViewById(getId("switch_augment_gps_from_meshcore"));
        rowAugmentGpsFromMeshcore = rootView.findViewById(getId("row_augment_gps_from_meshcore"));
        switchMeshShowRepeaters = rootView.findViewById(getId("switch_mesh_show_repeaters"));
        switchMeshShowNodes = rootView.findViewById(getId("switch_mesh_show_nodes"));
        btnMeshRequestChannels = rootView.findViewById(getId("btn_mesh_request_channels"));
        btnMeshcoreChannels = rootView.findViewById(getId("btn_meshcore_channels"));
        meshChannelLogText = rootView.findViewById(getId("text_mesh_channel_log"));
        editMeshChannelIndex = rootView.findViewById(getId("edit_mesh_channel_index"));
        editMeshChannelMessage = rootView.findViewById(getId("edit_mesh_channel_message"));
        btnMeshChannelSend = rootView.findViewById(getId("btn_mesh_channel_send"));
        switchMeshSendPositionWithAdvert =
                rootView.findViewById(getId("switch_mesh_send_position_with_advert"));
        switchMeshUseCallsignLocation =
                rootView.findViewById(getId("switch_mesh_use_callsign_location"));
        switchMeshUseCustomNodePosition =
                rootView.findViewById(getId("switch_mesh_use_custom_node_position"));
        textMeshUseCallsignLocation =
                rootView.findViewById(getId("text_mesh_use_callsign_location"));
        btnClearMeshContacts = rootView.findViewById(getId("btn_clear_mesh_contacts"));
        btnMeshNodeSettings = rootView.findViewById(getId("btn_mesh_node_settings"));
        btnMeshcoreSetNodePositionMap =
                rootView.findViewById(getId("btn_meshcore_set_node_position_map"));
        switchEncryption = rootView.findViewById(getId("switch_encryption"));
        passphraseRow = rootView.findViewById(getId("passphrase_row"));
        editPassphrase = rootView.findViewById(getId("edit_passphrase"));
        btnSetPassphrase = rootView.findViewById(getId("btn_set_passphrase"));
        encryptionStatusText = rootView.findViewById(getId("text_encryption_status"));

        if (meshChannelLogText != null) {
            meshChannelLogText.setMovementMethod(new ScrollingMovementMethod());
            // Let the channel window scroll independently of the outer dropdown ScrollView.
            meshChannelLogText.setOnTouchListener((v, event) -> {
                v.getParent().requestDisallowInterceptTouchEvent(true);
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                }
                return false;
            });
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
            btnSettings.setOnClickListener(v -> showSettingsDialog());
        }
        if (btnSendBeacon != null) {
            btnSendBeacon.setOnClickListener(v -> sendManualBeacon());
        }
        if (btnSendPing != null) {
            btnSendPing.setOnClickListener(v -> sendPing());
        }
        if (btnBeaconSettings != null) {
            btnBeaconSettings.setOnClickListener(v -> showBeaconSettingsDialog());
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
                    Toast.makeText(getMapView().getContext(),
                            "Advert Sent", Toast.LENGTH_SHORT).show();
                } else {
                    appendLog("Failed to request self advert");
                }
            });
        }
        if (switchMeshEnableGpsConnection != null) {
            switchMeshEnableGpsConnection.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before changing GPS state");
                    updateMeshGpsControlsUi();
                    return;
                }
                onMeshEnableGpsHardwareChanged(isChecked);
            });
        }
        if (switchMeshEnableGpsHardware != null) {
            switchMeshEnableGpsHardware.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before changing GPS state");
                    updateMeshGpsControlsUi();
                    return;
                }
                onMeshEnableGpsHardwareChanged(isChecked);
            });
        }
        if (switchMeshEnableGps != null) {
            switchMeshEnableGps.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressMeshGpsSwitchCallbacks || !buttonView.isPressed()) {
                    return;
                }
                if (!isChecked
                        && !isMeshUseCallsignLocationPreferenceEnabled(getMapView().getContext())
                        && !isMeshUseCustomNodePositionPreferenceEnabled(getMapView().getContext())) {
                    suppressMeshGpsSwitchCallbacks = true;
                    try {
                        switchMeshEnableGps.setChecked(true);
                    } finally {
                        suppressMeshGpsSwitchCallbacks = false;
                    }
                    return;
                }
                if (!btManager.isConnected()) {
                    appendLog("Connect to MeshCore before changing GPS state");
                    updateMeshGpsControlsUi();
                    return;
                }
                if (isChecked) {
                    setMeshUseCallsignLocationPreference(false);
                    if (switchMeshUseCallsignLocation != null) {
                        switchMeshUseCallsignLocation.setChecked(false);
                    }
                    setMeshUseCustomNodePositionPreference(false);
                    if (switchMeshUseCustomNodePosition != null) {
                        switchMeshUseCustomNodePosition.setChecked(false);
                    }
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
                if (!isChecked
                        && !getMeshUseGpsForPositionPreference(getMapView().getContext())
                        && !isMeshUseCustomNodePositionPreferenceEnabled(getMapView().getContext())) {
                    switchMeshUseCallsignLocation.setChecked(true);
                    return;
                }
                if (isChecked) {
                    forceDisableMeshGpsPositionSource();
                    setMeshUseCustomNodePositionPreference(false);
                    if (switchMeshUseCustomNodePosition != null) {
                        switchMeshUseCustomNodePosition.setChecked(false);
                    }
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
        if (switchMeshUseCustomNodePosition != null) {
            switchMeshUseCustomNodePosition.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                if (!isChecked
                        && !getMeshUseGpsForPositionPreference(getMapView().getContext())
                        && !isMeshUseCallsignLocationPreferenceEnabled(getMapView().getContext())) {
                    switchMeshUseCustomNodePosition.setChecked(true);
                    return;
                }
                if (isChecked) {
                    forceDisableMeshGpsPositionSource();
                    setMeshUseCallsignLocationPreference(false);
                    if (switchMeshUseCallsignLocation != null) {
                        switchMeshUseCallsignLocation.setChecked(false);
                    }
                }
                setMeshUseCustomNodePositionPreference(isChecked);
                updateMeshGpsControlsUi();
            });
        }
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setOnClickListener(v -> requestManualMeshGpsUpdate());
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (!buttonView.isPressed()) {
                    return;
                }
                setAugmentMeshPreference(isChecked);
                scheduleMeshGpsAugmentTick();
                appendLog(isChecked
                        ? "MeshCore GPS augment enabled (2 min when phone has no fix)."
                        : "MeshCore GPS augment disabled.");
            });
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
        if (btnMeshcoreChannels != null) {
            btnMeshcoreChannels.setOnClickListener(v -> onMeshcoreChannelsClicked());
        }
        if (switchEncryption != null) {
            switchEncryption.setOnCheckedChangeListener((buttonView, isChecked) -> {
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putBoolean(SettingsFragment.PREF_ENCRYPTION_ENABLED, isChecked).apply();
                if (passphraseRow != null) {
                    passphraseRow.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                }
                if (!isChecked && encryptionManager != null) {
                    encryptionManager.setSharedSecret(null);
                    updateEncryptionStatus();
                    appendLog("Encryption disabled");
                } else if (isChecked) {
                    String existing = SettingsFragment.getEncryptionPassphrase(
                            getMapView().getContext());
                    if (existing != null && !existing.isEmpty() && encryptionManager != null) {
                        encryptionManager.setSharedSecret(existing);
                        updateEncryptionStatus();
                        appendLog("Encryption enabled (AES-256-GCM)");
                    } else {
                        updateEncryptionStatus();
                        appendLog("Configure shared secret to enable encryption");
                    }
                }
            });
        }
        if (btnSetPassphrase != null) {
            btnSetPassphrase.setOnClickListener(v -> {
                if (editPassphrase == null) {
                    return;
                }
                String pass = editPassphrase.getText().toString().trim();
                if (pass.isEmpty()) {
                    appendLog("Shared secret cannot be empty");
                    return;
                }
                SharedPreferences prefs = PreferenceManager
                        .getDefaultSharedPreferences(getMapView().getContext());
                prefs.edit().putString(SettingsFragment.PREF_ENCRYPTION_PASSPHRASE, pass).apply();
                if (encryptionManager != null) {
                    encryptionManager.setSharedSecret(pass);
                }
                editPassphrase.setText("");
                updateEncryptionStatus();
                appendLog("Shared secret saved — encryption active");
            });
        }
    }

    private void updateEncryptionStatus() {
        if (encryptionStatusText == null) {
            return;
        }
        Context ctx = getMapView().getContext();
        boolean encOn = SettingsFragment.isEncryptionEnabled(ctx);
        String pass = SettingsFragment.getEncryptionPassphrase(ctx);
        if (encOn && pass != null && !pass.isEmpty()) {
            encryptionStatusText.setText("\u2705 AES-256-GCM active");
            encryptionStatusText.setTextColor(0xFF4CAF50);
        } else if (encOn) {
            encryptionStatusText.setText("\u26A0 Enter shared secret to activate");
            encryptionStatusText.setTextColor(0xFFFF9800);
        } else {
            encryptionStatusText.setText("All radios must use the same shared secret");
            encryptionStatusText.setTextColor(0xFF888888);
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
            meshChannelChatActiveIndex = channelIndex;
            renderMeshChannelChatLog(channelIndex);
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

    private void onMeshcoreChannelsClicked() {
        if (btManager == null || !btManager.isConnected()) {
            Toast.makeText(getMapView().getContext(),
                    "Connect to a MeshCore node first.", Toast.LENGTH_SHORT).show();
            return;
        }
        btManager.requestAllChannelInfo();
        Map<Integer, String> snapshot = btManager.getKnownChannelNamesSnapshot();
        if (!snapshot.isEmpty()) {
            meshChannelNames.putAll(snapshot);
            persistMeshChannelHistory();
        }
        updateMeshChannelButtonLabel();
        showMeshChannelPickerDialog();
    }

    private void showMeshChannelPickerDialog() {
        Context ctx = getMapView().getContext();
        List<Integer> indices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String name = meshChannelNames.get(i);
            if (name != null && !name.trim().isEmpty()) {
                if ("ATAK_DATA".equalsIgnoreCase(name.trim())) {
                    continue;
                }
                indices.add(i);
                labels.add("#" + i + "  " + name);
            }
        }
        if (indices.isEmpty()) {
            Toast.makeText(ctx, "Channel list is still loading. Tap again in a moment.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(ctx)
                .setTitle("MeshCore Channels")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which < 0 || which >= indices.size()) {
                        return;
                    }
                    selectMeshChannelForInlineWindow(indices.get(which));
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Show the chosen channel's conversation in the existing bottom chat window (no popup).
     * Sets the active channel so inbound/outbound messages render there and the inline Send
     * field targets it.
     */
    private void selectMeshChannelForInlineWindow(int channelIndex) {
        meshChannelChatActiveIndex = channelIndex;
        if (editMeshChannelIndex != null) {
            editMeshChannelIndex.setText(String.valueOf(channelIndex));
        }
        String channelName = meshChannelNames.get(channelIndex);
        if (channelName == null || channelName.trim().isEmpty()) {
            channelName = "Channel";
        }
        appendLog("Viewing channel #" + channelIndex + " - " + channelName);
        renderMeshChannelChatLog(channelIndex);
    }

    private void openMeshChannelChatDialog(int channelIndex) {
        Context ctx = getMapView().getContext();
        String channelName = meshChannelNames.get(channelIndex);
        if (channelName == null || channelName.trim().isEmpty()) {
            channelName = "Channel";
        }
        meshChannelChatActiveIndex = channelIndex;

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Channel #" + channelIndex + " - " + channelName);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(15f);
        root.addView(title);
        meshChannelChatTitleView = title;

        TextView status = new TextView(ctx);
        status.setText("Status shown from MeshCore message metadata when available.");
        status.setTextColor(0xFF90A4AE);
        status.setTextSize(11f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.bottomMargin = dip(ctx, 8);
        root.addView(status, statusLp);

        ScrollView scroll = new ScrollView(ctx);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0);
        scrollLp.weight = 1f;
        TextView log = new TextView(ctx);
        log.setTextColor(0xFFE0E0E0);
        log.setTextSize(13f);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setPadding(dip(ctx, 8), dip(ctx, 8), dip(ctx, 8), dip(ctx, 8));
        log.setBackgroundColor(0xFF1E1E1E);
        scroll.addView(log, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, scrollLp);
        meshChannelChatLogView = log;

        EditText input = new EditText(ctx);
        input.setHint("Type message");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dip(ctx, 8);
        root.addView(input, inputLp);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle("MeshCore Channel Chat")
                .setView(root)
                .setPositiveButton("Send", null)
                .setNegativeButton("Close", (d, which) -> {
                    meshChannelChatActiveIndex = -1;
                    meshChannelChatDialog = null;
                    meshChannelChatLogView = null;
                    meshChannelChatTitleView = null;
                })
                .create();
        dialog.setOnShowListener(d -> {
            Button send = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (send != null) {
                send.setOnClickListener(v -> {
                    String text = input.getText() != null ? input.getText().toString().trim() : "";
                    if (text.isEmpty()) {
                        return;
                    }
                    if (!btManager.sendChannelText(channelIndex, text)) {
                        Toast.makeText(ctx, "Failed to send over MeshCore channel.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    input.setText("");
                });
            }
            renderMeshChannelChatLog(channelIndex);
        });
        dialog.show();
        meshChannelChatDialog = dialog;
    }

    private void appendMeshChannelMessage(BtConnectionManager.MeshChannelMessage message) {
        if (message == null) {
            return;
        }
        int channelIndex = message.channelIndex;
        if (message.outbound && message.statusText != null
                && !message.statusText.trim().isEmpty()
                && !"queued".equalsIgnoreCase(message.statusText.trim())
                && tryUpgradeQueuedOutboundFromOutboundStatus(message)) {
            persistMeshChannelHistory();
            return;
        }
        if (!message.outbound && tryUpgradeQueuedOutboundMessage(message)) {
            persistMeshChannelHistory();
            return;
        }
        if (!message.outbound && tryUpgradeMostRecentQueuedOutboundAnyChannel(message)) {
            persistMeshChannelHistory();
            return;
        }
        if (channelIndex < 0 || channelIndex > 7) {
            // Some firmware variants omit/shift channel index in channel message pushes.
            // We still attempt queued-upgrade paths above; if none matched, skip storing.
            return;
        }
        LinkedList<BtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null) {
            bucket = new LinkedList<>();
            meshChannelMessages.put(channelIndex, bucket);
        }
        bucket.add(message);
        while (bucket.size() > MAX_MESH_CHANNEL_MESSAGES) {
            bucket.removeFirst();
        }
        persistMeshChannelHistory();
        if (message.outbound && isRepeatStatusAwaitingEvidence(message.statusText)) {
            scheduleMeshQueuedStatusTimeout();
        }
    }

    /**
     * Mesh channel TX has no explicit send-confirm packet for this UI path.
     * Promote the latest matching queued outbound row when the same channel text
     * is observed on RX with path metadata.
     */
    private boolean tryUpgradeQueuedOutboundMessage(BtConnectionManager.MeshChannelMessage inbound) {
        if (inbound == null || inbound.outbound) {
            return false;
        }
        String inboundNorm = normalizeChannelMessageText(inbound.text);
        if (inboundNorm.isEmpty()) {
            return false;
        }
        int channelIndex = inbound.channelIndex;
        if (channelIndex < 0 || channelIndex > 7) {
            channelIndex = findMostRecentQueuedChannelIndex();
            if (channelIndex < 0) {
                return false;
            }
        }
        LinkedList<BtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null || bucket.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        int fallbackQueuedIndex = -1;
        for (int i = bucket.size() - 1; i >= 0; i--) {
            BtConnectionManager.MeshChannelMessage existing = bucket.get(i);
            if (!existing.outbound) {
                continue;
            }
            String status = existing.statusText != null ? existing.statusText.trim().toLowerCase(Locale.US) : "";
            if (!isRepeatStatusAwaitingEvidence(status)) {
                continue;
            }
            long ageMs = (now - existing.receivedAtMs);
            if (ageMs > 20_000L) {
                continue;
            }
            int repeats = Math.max(0, (inbound.pathLen != null ? inbound.pathLen : 0));
            String heardStatus = outboundStatusFromRepeats(repeats);
            String existingNorm = normalizeChannelMessageText(existing.text);
            boolean textMatches = !existingNorm.isEmpty()
                    && !inboundNorm.isEmpty()
                    && (existingNorm.equals(inboundNorm)
                    || inboundNorm.contains(existingNorm)
                    || existingNorm.contains(inboundNorm));
            if (!textMatches) {
                continue;
            }
            if (fallbackQueuedIndex < 0) {
                fallbackQueuedIndex = i;
            }
            if (textMatches) {
                BtConnectionManager.MeshChannelMessage upgraded =
                        new BtConnectionManager.MeshChannelMessage(
                                existing.channelIndex,
                                existing.text,
                                existing.receivedAtMs,
                                true,
                                heardStatus,
                                inbound.snrQuarterDb,
                                inbound.pathLen,
                                inbound.senderTimestampSec);
                bucket.set(i, upgraded);
                Log.d(TAG, "Mesh channel status upgraded by text match ch=" + channelIndex
                        + " status=" + heardStatus);
                return true;
            }
        }
        if (fallbackQueuedIndex >= 0) {
            BtConnectionManager.MeshChannelMessage existing = bucket.get(fallbackQueuedIndex);
            int repeats = Math.max(0, (inbound.pathLen != null ? inbound.pathLen : 0));
            String heardStatus = outboundStatusFromRepeats(repeats);
            BtConnectionManager.MeshChannelMessage upgraded =
                    new BtConnectionManager.MeshChannelMessage(
                            existing.channelIndex,
                            existing.text,
                            existing.receivedAtMs,
                            true,
                            heardStatus,
                            inbound.snrQuarterDb,
                            inbound.pathLen,
                            inbound.senderTimestampSec);
            bucket.set(fallbackQueuedIndex, upgraded);
            Log.d(TAG, "Mesh channel status upgraded by fallback ch=" + channelIndex
                    + " status=" + heardStatus);
            return true;
        }
        return false;
    }

    private int findMostRecentQueuedChannelIndex() {
        long newestMs = -1L;
        int bestChannel = -1;
        for (Map.Entry<Integer, LinkedList<BtConnectionManager.MeshChannelMessage>> e
                : meshChannelMessages.entrySet()) {
            LinkedList<BtConnectionManager.MeshChannelMessage> bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                BtConnectionManager.MeshChannelMessage m = bucket.get(i);
                if (!m.outbound) {
                    continue;
                }
                String status = m.statusText != null ? m.statusText.trim().toLowerCase(Locale.US) : "";
                if (!isRepeatStatusAwaitingEvidence(status)) {
                    continue;
                }
                if (m.receivedAtMs > newestMs) {
                    newestMs = m.receivedAtMs;
                    bestChannel = e.getKey();
                }
                break;
            }
        }
        return bestChannel;
    }

    private boolean tryUpgradeMostRecentQueuedOutboundAnyChannel(
            BtConnectionManager.MeshChannelMessage inbound) {
        if (inbound == null || inbound.outbound) {
            return false;
        }
        String inboundNorm = normalizeChannelMessageText(inbound.text);
        if (inboundNorm.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        int bestChannel = -1;
        int bestIndex = -1;
        long newestMs = -1L;
        for (Map.Entry<Integer, LinkedList<BtConnectionManager.MeshChannelMessage>> e
                : meshChannelMessages.entrySet()) {
            LinkedList<BtConnectionManager.MeshChannelMessage> bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                BtConnectionManager.MeshChannelMessage m = bucket.get(i);
                if (!m.outbound) {
                    continue;
                }
                String status = m.statusText != null ? m.statusText.trim().toLowerCase(Locale.US) : "";
                if (!isRepeatStatusAwaitingEvidence(status)) {
                    continue;
                }
                long ageMs = (now - m.receivedAtMs);
                if (ageMs > 20_000L) {
                    continue;
                }
                String queuedNorm = normalizeChannelMessageText(m.text);
                boolean textMatches = !queuedNorm.isEmpty()
                        && (queuedNorm.equals(inboundNorm)
                        || inboundNorm.contains(queuedNorm)
                        || queuedNorm.contains(inboundNorm));
                if (!textMatches) {
                    continue;
                }
                if (m.receivedAtMs > newestMs) {
                    newestMs = m.receivedAtMs;
                    bestChannel = e.getKey();
                    bestIndex = i;
                }
                break;
            }
        }
        if (bestChannel < 0 || bestIndex < 0) {
            return false;
        }
        LinkedList<BtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(bestChannel);
        if (bucket == null || bestIndex >= bucket.size()) {
            return false;
        }
        BtConnectionManager.MeshChannelMessage existing = bucket.get(bestIndex);
        int repeats = Math.max(0, (inbound.pathLen != null ? inbound.pathLen : 0));
        String heardStatus = outboundStatusFromRepeats(repeats);
        BtConnectionManager.MeshChannelMessage upgraded =
                new BtConnectionManager.MeshChannelMessage(
                        existing.channelIndex,
                        existing.text,
                        existing.receivedAtMs,
                        true,
                        heardStatus,
                        inbound.snrQuarterDb,
                        inbound.pathLen,
                        inbound.senderTimestampSec);
        bucket.set(bestIndex, upgraded);
        Log.d(TAG, "Mesh channel queued status upgraded by global fallback channel="
                + bestChannel + " status=" + heardStatus);
        return true;
    }

    private boolean tryUpgradeQueuedOutboundFromOutboundStatus(
            BtConnectionManager.MeshChannelMessage update) {
        if (update == null || !update.outbound) {
            return false;
        }
        int channelIndex = update.channelIndex;
        LinkedList<BtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null || bucket.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (int i = bucket.size() - 1; i >= 0; i--) {
            BtConnectionManager.MeshChannelMessage existing = bucket.get(i);
            if (!existing.outbound) {
                continue;
            }
            String status = existing.statusText != null ? existing.statusText.trim().toLowerCase(Locale.US) : "";
            if (!isRepeatStatusAwaitingEvidence(status)) {
                continue;
            }
            if ((now - existing.receivedAtMs) > 120_000L) {
                continue;
            }
            String existingNorm = normalizeChannelMessageText(existing.text);
            String updateNorm = normalizeChannelMessageText(update.text);
            if (!existingNorm.isEmpty() && !updateNorm.isEmpty() && !existingNorm.equals(updateNorm)) {
                continue;
            }
            BtConnectionManager.MeshChannelMessage upgraded =
                    new BtConnectionManager.MeshChannelMessage(
                            existing.channelIndex,
                            existing.text,
                            existing.receivedAtMs,
                            true,
                            update.statusText,
                            update.snrQuarterDb,
                            update.pathLen,
                            update.senderTimestampSec);
            bucket.set(i, upgraded);
            Log.d(TAG, "Mesh channel queued status upgraded from TX confirm ch=" + channelIndex
                    + " status=" + update.statusText);
            return true;
        }
        return false;
    }

    private static String normalizeChannelMessageText(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim().toLowerCase(Locale.US);
        t = t.replace('\n', ' ').replace('\r', ' ');
        while (t.contains("  ")) {
            t = t.replace("  ", " ");
        }
        return t;
    }

    private static boolean isRepeatStatusAwaitingEvidence(String statusRaw) {
        if (statusRaw == null) {
            return false;
        }
        String status = statusRaw.trim().toLowerCase(Locale.US);
        if (status.equals("queued") || status.equals("sent")) {
            return true;
        }
        if (status.startsWith("heard 0 repeats")) {
            return true;
        }
        return status.startsWith("heard (repeat count pending");
    }

    private static String outboundStatusFromRepeats(int repeats) {
        if (repeats <= 0) {
            return "Sent";
        }
        return "heard " + repeats + " repeats";
    }

    private void scheduleMeshQueuedStatusTimeout() {
        if (getMapView() == null) {
            return;
        }
        getMapView().removeCallbacks(meshQueuedStatusTimeoutRunnable);
        getMapView().postDelayed(meshQueuedStatusTimeoutRunnable, MESH_CHANNEL_QUEUE_TIMEOUT_MS);
    }

    private void applyMeshQueuedStatusTimeouts() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<Integer, LinkedList<BtConnectionManager.MeshChannelMessage>> e
                : meshChannelMessages.entrySet()) {
            LinkedList<BtConnectionManager.MeshChannelMessage> bucket = e.getValue();
            if (bucket == null || bucket.isEmpty()) {
                continue;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                BtConnectionManager.MeshChannelMessage m = bucket.get(i);
                if (!m.outbound) {
                    continue;
                }
                if (!isRepeatStatusAwaitingEvidence(m.statusText)) {
                    continue;
                }
                if ((now - m.receivedAtMs) < MESH_CHANNEL_QUEUE_TIMEOUT_MS) {
                    continue;
                }
                BtConnectionManager.MeshChannelMessage upgraded =
                        new BtConnectionManager.MeshChannelMessage(
                                m.channelIndex,
                                m.text,
                                m.receivedAtMs,
                                true,
                                "Sent",
                                m.snrQuarterDb,
                                0,
                                m.senderTimestampSec);
                bucket.set(i, upgraded);
                changed = true;
            }
        }
        if (changed) {
            persistMeshChannelHistory();
            if (meshChannelChatDialog != null && meshChannelChatDialog.isShowing()
                    && meshChannelChatActiveIndex >= 0) {
                renderMeshChannelChatLog(meshChannelChatActiveIndex);
            }
        }
    }

    private void renderMeshChannelChatLog(int channelIndex) {
        if (meshChannelLogText == null) {
            return;
        }
        LinkedList<BtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (bucket != null) {
            for (BtConnectionManager.MeshChannelMessage m : bucket) {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date(m.receivedAtMs));
                String sender = resolveMeshChannelSenderName(m);
                String msg = m.text == null ? "" : m.text;
                String prefix = "[" + ts + "] /" + sender + "/ ";
                int start = sb.length();
                sb.append(prefix).append(msg);
                int lineEnd = sb.length();
                // Enlarge the entire first line (timestamp + node name + message) two sizes above
                // the base/SNR line size (base 13sp -> 15sp).
                if (lineEnd > start) {
                    sb.setSpan(new RelativeSizeSpan(15f / 13f), start, lineEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                String meta = buildMeshChannelMetaLine(m);
                if (!meta.isEmpty()) {
                    sb.append("\n").append(meta);
                }
                sb.append("\n\n");
            }
        }
        if (sb.length() == 0) {
            sb.append("No messages yet for this channel.");
        }
        meshChannelLogText.setText(sb);
        // Auto-scroll to the most recent (bottom) message; user can still scroll up for history.
        meshChannelLogText.post(() -> {
            if (meshChannelLogText == null) {
                return;
            }
            android.text.Layout layout = meshChannelLogText.getLayout();
            if (layout == null) {
                return;
            }
            int visible = meshChannelLogText.getHeight()
                    - meshChannelLogText.getPaddingTop()
                    - meshChannelLogText.getPaddingBottom();
            int scrollY = layout.getHeight() - visible;
            meshChannelLogText.scrollTo(0, Math.max(0, scrollY));
        });
    }

    private void updateMeshChannelButtonLabel() {
        if (btnMeshcoreChannels == null) {
            return;
        }
        int known = 0;
        for (int i = 0; i < 8; i++) {
            String n = meshChannelNames.get(i);
            if (n != null && !n.trim().isEmpty()
                    && !"ATAK_DATA".equalsIgnoreCase(n.trim())) {
                known++;
            }
        }
        if (btManager != null && btManager.isConnected()) {
            btnMeshcoreChannels.setText(
                    known > 0 ? "Channels (" + known + ")" : "Channels");
        } else {
            btnMeshcoreChannels.setText("Channels");
        }
    }

    private String buildMeshChannelMetaLine(BtConnectionManager.MeshChannelMessage m) {
        List<String> parts = new ArrayList<>();
        if (m.snrQuarterDb != null) {
            parts.add(String.format(Locale.US, "SNR %.2f dB", m.snrQuarterDb / 4.0f));
        }
        String status = deriveMeshChannelMetaStatus(m);
        if (!status.isEmpty()) {
            parts.add(status);
        }
        if (parts.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(" / ");
            out.append(parts.get(i));
        }
        return out.toString();
    }

    private String resolveMeshChannelSenderName(BtConnectionManager.MeshChannelMessage m) {
        if (!m.outbound) {
            return "Node";
        }
        // Channel chat must NEVER show the ATAK callsign. Outbound messages are labeled with the
        // local MeshCore node name (e.g. "ATAK-TEST2"). ATAK callsigns are reserved for ATAK-level
        // contact chat only.
        try {
            BtConnectionManager.MeshNodeSettings ns =
                    btManager != null ? btManager.getLatestNodeSettings() : null;
            if (ns != null && ns.nodeName != null && !ns.nodeName.trim().isEmpty()) {
                return ns.nodeName.trim();
            }
            BtConnectionManager.MeshLocationFix fix =
                    btManager != null ? btManager.getLatestSelfLocation() : null;
            if (fix != null && fix.nodeName != null && !fix.nodeName.trim().isEmpty()) {
                return fix.nodeName.trim();
            }
        } catch (Exception ignored) {
        }
        return "Node";
    }

    private String deriveMeshChannelMetaStatus(BtConnectionManager.MeshChannelMessage m) {
        if (m.statusText != null) {
            String status = m.statusText.trim();
            if (!status.isEmpty()) {
                return status;
            }
        }
        if (m.pathLen != null && m.pathLen > 0) {
            return "heard " + m.pathLen + " repeats";
        }
        if (m.outbound) {
            return "Sent";
        }
        return "";
    }

    private void loadMeshChannelHistoryIfNeeded() {
        if (meshChannelHistoryLoaded) {
            return;
        }
        meshChannelHistoryLoaded = true;
        try {
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getMapView().getContext());
            String raw = prefs.getString(PREF_MESH_CHANNEL_HISTORY, "");
            if (raw == null || raw.isEmpty()) {
                return;
            }
            String[] lines = raw.split("\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\\|", 10);
                if (parts.length < 10) {
                    continue;
                }
                int channel = parseIntSafe(parts[0], -1);
                long recvMs = parseLongSafe(parts[1], 0L);
                boolean outbound = "1".equals(parts[2]);
                Integer snrQ = parseNullableInt(parts[3]);
                Integer pathLen = parseNullableInt(parts[4]);
                Integer senderTs = parseNullableInt(parts[5]);
                String status = decodeB64(parts[6]);
                String text = decodeB64(parts[7]);
                String channelName = decodeB64(parts[8]);
                if (channel < 0 || channel > 7 || text.isEmpty()) {
                    continue;
                }
                if (!channelName.isEmpty()) {
                    meshChannelNames.put(channel, channelName);
                }
                appendMeshChannelMessageNoPersist(new BtConnectionManager.MeshChannelMessage(
                        channel,
                        text,
                        recvMs > 0 ? recvMs : System.currentTimeMillis(),
                        outbound,
                        status,
                        snrQ,
                        pathLen,
                        senderTs));
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load Mesh channel history", e);
        }
    }

    private void appendMeshChannelMessageNoPersist(BtConnectionManager.MeshChannelMessage message) {
        if (message == null) {
            return;
        }
        int channelIndex = message.channelIndex;
        if (channelIndex < 0 || channelIndex > 7) {
            return;
        }
        LinkedList<BtConnectionManager.MeshChannelMessage> bucket = meshChannelMessages.get(channelIndex);
        if (bucket == null) {
            bucket = new LinkedList<>();
            meshChannelMessages.put(channelIndex, bucket);
        }
        bucket.add(message);
        while (bucket.size() > MAX_MESH_CHANNEL_MESSAGES) {
            bucket.removeFirst();
        }
    }

    private void persistMeshChannelHistory() {
        try {
            StringBuilder sb = new StringBuilder();
            for (int channel = 0; channel < 8; channel++) {
                LinkedList<BtConnectionManager.MeshChannelMessage> bucket =
                        meshChannelMessages.get(channel);
                if (bucket == null) {
                    continue;
                }
                String channelName = meshChannelNames.get(channel);
                for (BtConnectionManager.MeshChannelMessage m : bucket) {
                    sb.append(channel).append("|")
                            .append(m.receivedAtMs).append("|")
                            .append(m.outbound ? "1" : "0").append("|")
                            .append(nullableIntToken(m.snrQuarterDb)).append("|")
                            .append(nullableIntToken(m.pathLen)).append("|")
                            .append(nullableIntToken(m.senderTimestampSec)).append("|")
                            .append(encodeB64(m.statusText)).append("|")
                            .append(encodeB64(m.text)).append("|")
                            .append(encodeB64(channelName)).append("|")
                            .append("v1")
                            .append("\n");
                }
            }
            SharedPreferences prefs = PreferenceManager
                    .getDefaultSharedPreferences(getMapView().getContext());
            prefs.edit().putString(PREF_MESH_CHANNEL_HISTORY, sb.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist Mesh channel history", e);
        }
    }

    private static String encodeB64(String in) {
        if (in == null || in.isEmpty()) {
            return "";
        }
        return Base64.encodeToString(in.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.NO_WRAP);
    }

    private static String decodeB64(String in) {
        if (in == null || in.isEmpty()) {
            return "";
        }
        try {
            byte[] out = Base64.decode(in, Base64.NO_WRAP);
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int parseIntSafe(String s, int d) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return d;
        }
    }

    private static long parseLongSafe(String s, long d) {
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return d;
        }
    }

    private static Integer parseNullableInt(String s) {
        if (s == null || s.isEmpty() || "-".equals(s)) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nullableIntToken(Integer v) {
        return v == null ? "-" : Integer.toString(v);
    }

    private void updateMeshGpsControlsUi() {
        boolean meshConnected = btManager != null && btManager.isConnected();
        boolean advertPositionEnabled = meshSendPositionWithAdvertRequested
                || Boolean.TRUE.equals(meshSendPositionWithAdvertState);
        boolean meshGpsHardwareOn = Boolean.TRUE.equals(meshGpsEnabledState);
        // Sync both power switches (CONNECTION + MESHCORE) to the same hardware state.
        suppressMeshGpsSwitchCallbacks = true;
        try {
            if (switchMeshEnableGpsConnection != null) {
                switchMeshEnableGpsConnection.setEnabled(meshConnected);
                switchMeshEnableGpsConnection.setAlpha(meshConnected ? 1f : 0.45f);
                View connRow = (View) switchMeshEnableGpsConnection.getParent();
                if (connRow != null) connRow.setAlpha(meshConnected ? 1f : 0.55f);
                switchMeshEnableGpsConnection.setChecked(meshGpsHardwareOn);
            }
            if (switchMeshEnableGpsHardware != null) {
                switchMeshEnableGpsHardware.setEnabled(meshConnected);
                switchMeshEnableGpsHardware.setAlpha(meshConnected ? 1f : 0.45f);
                View hwRow = (View) switchMeshEnableGpsHardware.getParent();
                if (hwRow != null) hwRow.setAlpha(meshConnected ? 1f : 0.55f);
                switchMeshEnableGpsHardware.setChecked(meshGpsHardwareOn);
            }
        } finally {
            suppressMeshGpsSwitchCallbacks = false;
        }
        if (switchMeshEnableGps != null) {
            suppressMeshGpsSwitchCallbacks = true;
            try {
                // "Use MeshCore GPS for Position" selects the node GPS fix as the advert source.
                // It is only meaningful (and only selectable) once the GPS hardware is enabled.
                boolean meshGpsToggleEnabled = meshConnected
                        && meshGpsHardwareOn
                        && advertPositionEnabled;
                switchMeshEnableGps.setEnabled(meshGpsToggleEnabled);
                switchMeshEnableGps.setAlpha(meshGpsToggleEnabled ? 1f : 0.45f);
                View meshGpsRow = (View) switchMeshEnableGps.getParent();
                if (meshGpsRow != null) {
                    meshGpsRow.setAlpha(meshGpsToggleEnabled ? 1f : 0.55f);
                }
                switchMeshEnableGps.setChecked(meshGpsHardwareOn && meshGpsEnableRequested);
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
            boolean callsignToggleEnabled = meshConnected && advertPositionEnabled;
            switchMeshUseCallsignLocation.setEnabled(callsignToggleEnabled);
            switchMeshUseCallsignLocation.setAlpha(callsignToggleEnabled ? 1f : 0.45f);
            View callsignRow = (View) switchMeshUseCallsignLocation.getParent();
            if (callsignRow != null) {
                callsignRow.setAlpha(callsignToggleEnabled ? 1f : 0.55f);
            }
        }
        boolean customNodePositionEnabled = isMeshUseCustomNodePositionPreferenceEnabled(
                getMapView() != null ? getMapView().getContext() : null);
        if (switchMeshUseCustomNodePosition != null) {
            boolean customNodeToggleEnabled = meshConnected && advertPositionEnabled;
            switchMeshUseCustomNodePosition.setEnabled(customNodeToggleEnabled);
            switchMeshUseCustomNodePosition.setAlpha(customNodeToggleEnabled ? 1f : 0.45f);
            switchMeshUseCustomNodePosition.setChecked(customNodePositionEnabled);
            View customNodeRow = (View) switchMeshUseCustomNodePosition.getParent();
            if (customNodeRow != null) {
                customNodeRow.setAlpha(customNodeToggleEnabled ? 1f : 0.55f);
            }
        }
        boolean gpsDrivenActionsEnabled = meshConnected && meshGpsHardwareOn;
        if (btnUpdateGpsFromMeshcore != null) {
            btnUpdateGpsFromMeshcore.setEnabled(gpsDrivenActionsEnabled);
            btnUpdateGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.45f);
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setEnabled(gpsDrivenActionsEnabled);
            switchAugmentGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.45f);
        }
        if (rowAugmentGpsFromMeshcore != null) {
            rowAugmentGpsFromMeshcore.setVisibility(View.VISIBLE);
            rowAugmentGpsFromMeshcore.setAlpha(gpsDrivenActionsEnabled ? 1f : 0.55f);
        }
        if (btnMeshcoreSetNodePositionMap != null) {
            boolean mapButtonEnabled = meshConnected && customNodePositionEnabled;
            btnMeshcoreSetNodePositionMap.setEnabled(mapButtonEnabled);
            btnMeshcoreSetNodePositionMap.setAlpha(mapButtonEnabled ? 1f : 0.45f);
        }
    }

    private void updateMeshUseCallsignLocationLabel(String callsign) {
        if (textMeshUseCallsignLocation == null) {
            return;
        }
        String safe = (callsign == null || callsign.trim().isEmpty())
                ? "UNKNOWN" : callsign.trim();
        textMeshUseCallsignLocation.setText("Use " + safe + " Location for Position");
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

    private void updateGpsFromMeshcoreNow() {
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        btManager.requestSelfInfo();
        try {
            Thread.sleep(700L);
        } catch (InterruptedException ignored) {
        }
        BtConnectionManager.MeshLocationFix fix = btManager.getLatestSelfLocation();
        if (fix == null || !fix.isValid()) {
            return;
        }
        final BtConnectionManager.MeshLocationFix appliedFix = fix;
        getMapView().post(() -> {
            if (injectMeshGpsIntoAtak(appliedFix)) {
                appendLog(String.format(Locale.US,
                        "Augmented ATAK from MeshCore GPS: %.5f, %.5f",
                        appliedFix.latitude, appliedFix.longitude));
            }
        });
    }

    private void scheduleMeshGpsAugmentTick() {
        if (getMapView() == null) {
            return;
        }
        getMapView().removeCallbacks(meshGpsAugmentRunnable);
        Context ctx = getMapView().getContext();
        if (ctx == null || !isAugmentMeshPreferenceEnabled(ctx)) {
            return;
        }
        getMapView().postDelayed(meshGpsAugmentRunnable, MESH_GPS_AUGMENT_INTERVAL_MS);
    }

    /** True when the phone's internal GPS chip is reporting a valid fix to ATAK. */
    private boolean isPhoneGpsAvailable() {
        try {
            com.atakmap.android.location.framework.LocationProvider internal =
                    com.atakmap.android.location.framework.LocationManager.getInstance()
                            .getLocationProvider("internal-gps-chip");
            if (internal == null || !internal.getEnabled()) {
                return false;
            }
            com.atakmap.android.location.framework.Location loc =
                    internal.getLastReportedLocation();
            if (loc == null || !loc.isValid()) {
                return false;
            }
            com.atakmap.coremap.maps.coords.GeoPoint point = loc.getPoint();
            return point != null && point.isValid();
        } catch (Exception e) {
            Log.w(TAG, "Could not evaluate phone GPS state", e);
            return false;
        }
    }

    private boolean isAugmentMeshPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_AUGMENT_GPS_FROM_MESHCORE, false);
    }

    private void setAugmentMeshPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_AUGMENT_GPS_FROM_MESHCORE, enabled).apply();
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

    /** Beacon-only settings: GPS beacon interval + Smart Beacon enable/settings. */
    private void showBeaconSettingsDialog() {
        Context ctx = getMapView().getContext();
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        ScrollView scrollView = new ScrollView(ctx);
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dip(ctx, 12);
        layout.setPadding(pad, pad, pad, pad);
        scrollView.addView(layout);

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
        smartRow.addView(smartLabel,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
                .setTitle("Beacon Settings")
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String beacon = editBeaconInterval.getText().toString().trim();
                    if (!beacon.isEmpty()) {
                        prefs.edit().putString(SettingsFragment.PREF_BEACON_INTERVAL, beacon).apply();
                    }
                    SmartBeacon.setEnabled(ctx, switchSmart.isChecked());
                    if (switchSmartBeacon != null) {
                        switchSmartBeacon.setChecked(switchSmart.isChecked());
                    }
                    appendLog("Beacon settings saved");
                    try {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent(MeshCoreMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
                    } catch (Exception ignored) {
                    }
                })
                .show();
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

        // Favorites removed: this button always scans and opens the picker. Auto-connect to the
        // last device happens silently at startup only. The saved target is preserved for that.
        btManager.prepareForUserScan();
        if (btManager.isConnecting()) {
            appendLog("Cancelling current connection attempt...");
            btManager.cancelConnectionAttempts();
            stopConnectButtonPulse(true);
        }

        if (BluetoothAdapter.getDefaultAdapter() == null) {
            appendLog("Bluetooth not available");
            return;
        }

        foundDevices.clear();
        scanFoundAnyDevice = false;
        updateScanButtonText();
        appendLog("Scanning for MeshCore devices...");
        startScanDiscoveryPulse();
        btManager.startScan();
    }

    /**
     * Builds a two-line title for the MeshCore picker: the heading plus persistent PIN guidance.
     * Shown here (before the system pairing dialog steals focus) because a toast/log line is
     * hidden behind Android's modal PIN prompt and never seen by the user.
     */
    private View buildMeshPickerTitle(Context ctx) {
        android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = dip(ctx, 16);
        col.setPadding(pad, dip(ctx, 12), pad, dip(ctx, 4));

        TextView title = new TextView(ctx);
        title.setText("Select MeshCore");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        col.addView(title);

        TextView hint = new TextView(ctx);
        hint.setText(MeshBleDeviceMatcher.pinGuidance());
        hint.setTextColor(0xFFB0B0B0);
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        android.widget.LinearLayout.LayoutParams hp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dip(ctx, 4);
        hint.setLayoutParams(hp);
        col.addView(hint);

        return col;
    }

    private void showDevicePicker() {
        stopScanDiscoveryPulse();
        if (foundDevices.isEmpty()) {
            appendLog("No MeshCore devices found");
            btManager.endScanPickerSession();
            return;
        }

        Context ctx = getMapView().getContext();
        sortFoundDevicesForPicker();

        final int count = foundDevices.size();
        final int[] dotColors = new int[count];
        final String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            BluetoothDevice device = foundDevices.get(i);
            names[i] = resolveDeviceDisplayName(ctx, device);
            dotColors[i] = DOT_UNSEEN;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx,
                android.R.layout.simple_list_item_1, names) {
            @Override
            public View getView(int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, convertView, parent);
                SpannableStringBuilder sb = new SpannableStringBuilder("\u25CF  " + names[pos]);
                sb.setSpan(new ForegroundColorSpan(dotColors[pos]), 0, 1,
                        android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                tv.setText(sb);
                tv.setTextColor(0xFFFFFFFF);
                tv.setTextSize(16);
                return tv;
            }
        };

        appendLog("Select node: green=available, red=busy, grey=off/not in range");
        try {
            AlertDialog pickerDialog = new AlertDialog.Builder(ctx)
                    .setCustomTitle(buildMeshPickerTitle(ctx))
                    .setAdapter(adapter, (dialog, which) -> {
                        if (which < 0 || which >= foundDevices.size()) {
                            return;
                        }
                        BluetoothDevice selected = foundDevices.get(which);
                        appendLog("Connecting to " + names[which] + "...");
                        startConnectButtonPulse();
                        btManager.connectUserSelected(selected);
                        dialog.dismiss();
                    })
                    .setNeutralButton("Forget all", (d, w) -> {
                        d.dismiss();
                        btManager.cancelAvailabilityProbes();
                        confirmForgetAllMeshDevices(ctx);
                    })
                    .setNegativeButton("Cancel", (d, w) -> btManager.cancelAvailabilityProbes())
                    .setOnCancelListener(d -> btManager.cancelAvailabilityProbes())
                    .create();
            pickerDialog.setOnDismissListener(d -> {
                btManager.cancelAvailabilityProbes();
                btManager.endScanPickerSession();
            });
            pickerDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing MeshCore picker", e);
            appendLog("Error showing MeshCore picker");
            btManager.cancelAvailabilityProbes();
            return;
        }

        // Only probe devices actually seen in the live scan (in-range / advertising). The saved
        // last-connected device, if it isn't advertising right now, stays grey ("not seen")
        // rather than being probed — probing a non-advertising node is pointless and would
        // falsely show red.
        btManager.prepareForAvailabilityProbes();
        for (int i = 0; i < count; i++) {
            final int idx = i;
            BluetoothDevice device = foundDevices.get(i);
            if (!btManager.isLiveScanDevice(device)) {
                dotColors[idx] = DOT_UNSEEN;
                adapter.notifyDataSetChanged();
                continue;
            }
            btManager.probeDeviceAvailabilityForPicker(device, availability -> getMapView().post(() -> {
                dotColors[idx] = availability == BtConnectionManager.AVAIL_AVAILABLE
                        ? DOT_AVAILABLE : DOT_BUSY;
                adapter.notifyDataSetChanged();
            }));
        }
    }

    private void confirmForgetAllMeshDevices(Context ctx) {
        btManager.cancelAvailabilityProbes();
        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Forget all MeshCore devices?")
                    .setMessage(
                            "This clears saved MeshCore radios in the plugin and attempts "
                                    + "to unpair them from this phone.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Forget all", (d, w) -> forgetAllMeshDevices(ctx))
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing forget-all confirmation", e);
            forgetAllMeshDevices(ctx);
        }
    }

    private void forgetAllMeshDevices(Context ctx) {
        btManager.cancelAvailabilityProbes();
        if (btManager.isConnected()) {
            btManager.disconnect();
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        List<String> pickerAddresses = new ArrayList<>();
        for (BluetoothDevice device : foundDevices) {
            if (device != null && device.getAddress() != null) {
                pickerAddresses.add(device.getAddress());
            }
        }
        MeshBluetoothForgetAll.Result result =
                MeshBluetoothForgetAll.forgetAll(ctx, adapter, pickerAddresses);
        foundDevices.clear();
        refreshFavoriteStrip();
        updateScanButtonText();
        appendLog("Forgot " + result.registryEntriesCleared + " saved MeshCore device(s)"
                + (result.bondsAttempted > 0
                ? "; unpaired " + result.bondsRemoved + "/" + result.bondsAttempted
                : ""));
        if (result.needsAndroidSettingsReminder()) {
            showAndroidBluetoothSettingsReminder(ctx);
        }
    }

    private void showAndroidBluetoothSettingsReminder(Context ctx) {
        try {
            new AlertDialog.Builder(ctx)
                    .setTitle("Check Android Bluetooth settings")
                    .setMessage(
                            "Some MeshCore radios could not be unpaired automatically. "
                                    + "You must also forget these devices in your Android "
                                    + "Bluetooth settings.")
                    .setNegativeButton("Close", null)
                    .setPositiveButton("Open Settings", (d, w) -> openAndroidBluetoothSettings(ctx))
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing Bluetooth settings reminder", e);
            appendLog("You must also forget these devices in your Android Bluetooth settings.");
        }
    }

    private void openAndroidBluetoothSettings(Context ctx) {
        try {
            android.content.Intent intent =
                    new android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not open Bluetooth settings", e);
            appendLog("Open Settings > Bluetooth and forget MeshCore devices manually.");
        }
    }

    private void sortFoundDevicesForPicker() {
        Collections.sort(foundDevices, new Comparator<BluetoothDevice>() {
            @Override
            public int compare(BluetoothDevice a, BluetoothDevice b) {
                if (a == null && b == null) {
                    return 0;
                }
                if (a == null) {
                    return 1;
                }
                if (b == null) {
                    return -1;
                }
                boolean liveA = btManager.isLiveScanDevice(a);
                boolean liveB = btManager.isLiveScanDevice(b);
                if (liveA != liveB) {
                    return liveA ? -1 : 1;
                }
                Context ctx = getMapView().getContext();
                String nameA = resolveDeviceDisplayName(ctx, a);
                String nameB = resolveDeviceDisplayName(ctx, b);
                return nameA.compareToIgnoreCase(nameB);
            }
        });
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
        if (btnScan == null) {
            return;
        }
        // Favorites removed: the button is always SCAN & CONNECT when not connected (it doubles as
        // Disconnect while connected, handled by the caller's UI state).
        btnScan.setText("SCAN & CONNECT");
    }

    private void refreshFavoriteStrip() {
        // MeshCore favorites were removed in favour of "auto-connect to last device on startup".
        // The favorites row is always hidden.
        if (favoritesStrip != null) {
            favoritesStrip.removeAllViews();
        }
        if (favoritesLabel != null) {
            favoritesLabel.setVisibility(View.GONE);
        }
        if (favoritesScroll != null) {
            favoritesScroll.setVisibility(View.GONE);
        }
        if (connectModeHint != null) {
            connectModeHint.setVisibility(View.GONE);
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
            restoreScanButtonDefaultBackground();
        }
    }

    private void restoreScanButtonDefaultBackground() {
        if (btnScan == null) {
            return;
        }
        int bgId = pluginContext.getResources().getIdentifier(
                "bg_meshcore_connection_button_red", "drawable", pluginContext.getPackageName());
        if (bgId != 0) {
            btnScan.setBackgroundResource(bgId);
            return;
        }
        btnScan.setBackgroundTintList(null);
        btnScan.setBackground(buildPillButtonBackground(
                COLOR_PILL_BUTTON_PRIMARY, COLOR_CONNECTION_STROKE_RED));
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
            BluetoothDeviceRegistry.recordConnection(ctx, device, true);
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
            scheduleMeshGpsAugmentTick();
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
            getMapView().removeCallbacks(meshGpsAugmentRunnable);
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
        if (!scanFoundAnyDevice && btManager.isLiveScanDevice(device)) {
            scanFoundAnyDevice = true;
            getMapView().post(() -> appendLog("MeshCore node in range..."));
        }
    }

    @Override
    public void onScanComplete() {
        getMapView().post(() -> {
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

    /**
     * "Use MeshCore GPS for Position" — selects the node GPS fix as the advert position source.
     * This no longer toggles GPS hardware (see {@link #onMeshEnableGpsHardwareChanged}); it only
     * picks the source and is only operable while the GPS hardware is enabled.
     */
    private void onMeshGpsToggleChanged(boolean isChecked) {
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        if (!Boolean.TRUE.equals(meshGpsEnabledState)) {
            // Source selection requires the GPS hardware to be enabled first.
            updateMeshGpsControlsUi();
            return;
        }
        meshGpsEnableRequested = isChecked;
        setMeshUseGpsForPositionPreference(isChecked);
        updateMeshGpsControlsUi();
        appendLog(isChecked
                ? "Node advert position source: MeshCore GPS."
                : "Node advert position source: cleared.");
        if (isChecked) {
            removeMeshNodeMapPositionMarker(true);
        }
        scheduleMeshCallsignPositionSync();
        if (!isChecked) {
            pushPhoneLocationToMeshNodeIfNeeded(false);
        }
    }

    /**
     * "Enable MeshCore GPS" — turns the node's GPS hardware on/off. When the hardware is enabled
     * the node drives its own advert position from GPS, so node GPS becomes the advert source and
     * the other (mutually-exclusive) sources are cleared.
     */
    private void onMeshEnableGpsHardwareChanged(boolean isChecked) {
        if (btManager == null || !btManager.isConnected()) {
            return;
        }
        appendLog("Setting MeshCore GPS " + (isChecked ? "ON..." : "OFF..."));
        if (isChecked) {
            meshGpsEnableRequested = true;
            setMeshUseGpsForPositionPreference(true);
            setMeshUseCallsignLocationPreference(false);
            if (switchMeshUseCallsignLocation != null) {
                switchMeshUseCallsignLocation.setChecked(false);
            }
            setMeshUseCustomNodePositionPreference(false);
            if (switchMeshUseCustomNodePosition != null) {
                switchMeshUseCustomNodePosition.setChecked(false);
            }
            removeMeshNodeMapPositionMarker(true);
        } else {
            meshGpsEnableRequested = false;
            setMeshUseGpsForPositionPreference(false);
            meshGpsEnabledState = Boolean.FALSE;
            setAugmentMeshPreference(false);
            if (switchAugmentGpsFromMeshcore != null) {
                switchAugmentGpsFromMeshcore.setChecked(false);
            }
        }
        updateMeshGpsControlsUi();
        scheduleMeshCallsignPositionSync();
        btManager.setMeshGpsEnabled(isChecked);
        btManager.queryMeshGpsEnabled();
        if (!isChecked) {
            pushPhoneLocationToMeshNodeIfNeeded(false);
        }
    }

    private void forceDisableMeshGpsPositionSource() {
        meshGpsEnableRequested = false;
        meshGpsEnabledState = Boolean.FALSE;
        setMeshUseGpsForPositionPreference(false);
        if (switchMeshEnableGps != null) {
            suppressMeshGpsSwitchCallbacks = true;
            try {
                switchMeshEnableGps.setChecked(false);
            } finally {
                suppressMeshGpsSwitchCallbacks = false;
            }
        }
        suppressMeshGpsSwitchCallbacks = true;
        try {
            if (switchMeshEnableGpsConnection != null) {
                switchMeshEnableGpsConnection.setChecked(false);
            }
            if (switchMeshEnableGpsHardware != null) {
                switchMeshEnableGpsHardware.setChecked(false);
            }
        } finally {
            suppressMeshGpsSwitchCallbacks = false;
        }
        if (switchAugmentGpsFromMeshcore != null) {
            switchAugmentGpsFromMeshcore.setChecked(false);
        }
        setAugmentMeshPreference(false);
        if (btManager != null && btManager.isConnected()) {
            btManager.setMeshGpsEnabled(false);
            btManager.queryMeshGpsEnabled();
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
        boolean nodeGpsOn = Boolean.TRUE.equals(meshGpsEnabledState);
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
                && !Boolean.TRUE.equals(meshGpsEnabledState);
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
                && !Boolean.TRUE.equals(meshGpsEnabledState)) {
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
            return true;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION, true);
    }

    private boolean isMeshUseCustomNodePositionPreferenceEnabled(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean(PREF_MESH_USE_CUSTOM_NODE_POSITION, false);
    }

    private void setMeshUseCallsignLocationPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_MESH_USE_CALLSIGN_LOCATION_FOR_POSITION, enabled).apply();
    }

    private void setMeshUseCustomNodePositionPreference(boolean enabled) {
        Context ctx = getMapView() != null ? getMapView().getContext() : null;
        if (ctx == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(ctx)
                .edit().putBoolean(PREF_MESH_USE_CUSTOM_NODE_POSITION, enabled).apply();
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
            // Clear plugin-side unread counts so the contacts icon badge resets immediately.
            if (chatBridge != null) {
                chatBridge.clearUnreadForAllMeshContacts();
            } else {
                MeshCoreContactHandler.clearAllMeshUnread();
            }
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
            getMapView().removeCallbacks(meshQueuedStatusTimeoutRunnable);
            getMapView().removeCallbacks(meshGpsAugmentRunnable);
        }
        if (meshChannelChatDialog != null && meshChannelChatDialog.isShowing()) {
            meshChannelChatDialog.dismiss();
        }
        meshChannelChatDialog = null;
        meshChannelChatLogView = null;
        meshChannelChatTitleView = null;
        meshChannelChatActiveIndex = -1;
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
        getMapView().removeCallbacks(meshGpsAugmentRunnable);
        getMapView().removeCallbacks(meshCallsignPositionSyncRunnable);
        getMapView().removeCallbacks(meshQueuedStatusTimeoutRunnable);
        if (meshChannelChatDialog != null && meshChannelChatDialog.isShowing()) {
            meshChannelChatDialog.dismiss();
        }
        meshChannelChatDialog = null;
        meshChannelChatLogView = null;
        meshChannelChatTitleView = null;
        meshChannelChatActiveIndex = -1;
    }
}
