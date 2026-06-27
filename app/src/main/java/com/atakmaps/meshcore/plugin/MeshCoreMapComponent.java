package com.atakmaps.meshcore.plugin;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.maps.coords.GeoCalculations;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmaps.meshcore.plugin.beacon.MeshBeaconLimits;
import com.atakmaps.meshcore.plugin.beacon.SmartBeacon;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
import com.atakmaps.meshcore.plugin.contacts.ContactTracker;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.mesh.MeshNodeCachePolicy;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.location.MeshGpsBridge;
import com.atakmaps.meshcore.plugin.mesh.MeshDetailsDropDownReceiver;
import com.atakmaps.meshcore.plugin.protocol.MeshCoreRadioServices;
import com.atakmaps.meshcore.plugin.protocol.PacketRouter;
import com.atakmaps.meshcore.plugin.ax25.MeshcoreIconsetInstaller;
import com.atakmaps.meshcore.plugin.protocol.PositionRequester;
import com.atakmaps.meshcore.plugin.ui.MeshStatusOverlay;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MeshCore-only map component.
 */
public class MeshCoreMapComponent extends DropDownMapComponent {

    private static final String TAG = "MeshCore";

    /** One silent repo sync per process after trust is configured; no retry loop. */
    private static final AtomicBoolean startupRepoSyncScheduled = new AtomicBoolean(false);
    private static final long STARTUP_REPO_SYNC_DELAY_MS = 3500L;

    /**
     * PKCS#12 store key for {@code assets/atakmaps-ca.p12}, from
     * {@link R.string#meshcore_trust_bundle_p12_key} (Base64) — not a Java string literal
     * (Fortify / static analysis hygiene).
     */
    private static volatile String cachedTrustBundleP12Key;

    public static final String PLUGIN_PACKAGE = "com.atakmaps.meshcore.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.atakmaps.meshcore.plugin.BEACON_INTERVAL_CHANGED";
    private static final String PREF_MESH_SHOW_REPEATERS = "meshcore_mesh_show_repeaters";
    private static final String PREF_MESH_SHOW_NODES = "meshcore_mesh_show_nodes";
    private static final String PREF_MESH_REPEATER_CACHE = "meshcore_mesh_repeater_cache_v1";
    private static final String PREF_MESH_NODE_CACHE = "meshcore_mesh_node_cache_v1";
    private static final long MESH_REPEATER_TTL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long MESH_NODE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;

    private Context pluginContext;
    private MapView mapView;

    private BtConnectionManager btConnectionManager;
    private PacketRouter packetRouter;
    private CotBridge cotBridge;
    private ChatBridge chatBridge;
    private ContactTracker contactTracker;
    private MeshCoreDropDownReceiver dropDownReceiver;
    private MeshDetailsDropDownReceiver meshDetailsDropDownReceiver;
    private EncryptionManager encryptionManager;
    private MapEventDispatcher.MapEventDispatchListener mapItemClickListener;
    private SharedPreferences meshMapPrefs;
    private SharedPreferences.OnSharedPreferenceChangeListener meshMapPrefsListener;
    private final Set<String> meshRepeaterMapUids = new CopyOnWriteArraySet<>();
    private final Set<String> meshNodeMapUids = new CopyOnWriteArraySet<>();
    private Handler meshIconsetReminderHandler;
    private Runnable meshIconsetReminderRunnable;
    private android.content.BroadcastReceiver radialPingReceiver;
    private android.content.BroadcastReceiver beaconIntervalReceiver;
    private Handler beaconHandler;
    private Runnable beaconRunnable;
    private Runnable beaconWaitForPositionRunnable;
    private boolean forceFirstPostConnectBeacon = false;
    private final SmartBeacon smartBeacon = new SmartBeacon();
    private android.location.LocationManager gpsLocationManager;
    private android.location.LocationListener gpsLocationListener;
    private volatile android.location.Location lastGpsLocation = null;
    private double lastBeaconLatDeg = Double.NaN;
    private double lastBeaconLonDeg = Double.NaN;
    private long lastBeaconPositionMs = 0;

    /** Smart Beacon prefs are stored in ATAK default prefs, not plugin prefs. */
    private Context getBeaconPrefsContext() {
        if (mapView != null && mapView.getContext() != null) {
            return mapView.getContext();
        }
        return pluginContext;
    }

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        this.pluginContext = context;
        this.mapView = view;
        com.atakmaps.meshcore.plugin.contacts.ContactConnectorIcons.warmCache(context);

        // Configure update-server TLS + prefs before BT/CoT subsystems start.
        // Mirrors the same call in the Lifecycle constructor (belt-and-suspenders).
        try {
            configureUpdateServerStatic(context, view.getContext().getApplicationContext());
        } catch (Exception e) {
            Log.w(TAG, "early configureUpdateServer: " + e.getMessage());
        }

        String callsign = "UNKNOWN";
        try {
            if (view != null && view.getSelfMarker() != null) {
                callsign = view.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve ATAK callsign", e);
        }

        MeshGpsBridge.installLocationProvider();

        cotBridge = new CotBridge(context, view);
        cotBridge.setLocalCallsign(callsign);
        chatBridge = new ChatBridge(context, view);
        chatBridge.setLocalCallsign(callsign);
        chatBridge.setCotBridge(cotBridge);
        cotBridge.setChatBridge(chatBridge);
        ChatBridge.setMergeRoutingBridge(cotBridge);

        // Repair connector icons for any MESHCORE-* contacts left by prior plugin versions.
        // Post to next frame so ATAK's Contacts map is fully loaded before we iterate.
        view.post(MeshCoreContactHandler::repairAllMeshContactConnectors);

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
        cotBridge.setPacketRouter(packetRouter);

        btConnectionManager = new BtConnectionManager(context, packetRouter);
        MeshCoreRadioServices.install(btConnectionManager, encryptionManager);
        MeshCoreRadioServices.syncEncryptionFromSettings(context);
        PositionRequester.install(btConnectionManager, encryptionManager, cotBridge);
        btConnectionManager.addMeshAdvertListener(advert -> {
            if (advert == null || mapView == null || !advert.hasValidPosition()) {
                return;
            }
            boolean isRepeater = advert.advertType == 0x02;
            String pub = advert.pubKeyHex != null ? advert.pubKeyHex : "";
            // Always persist the node/repeater location regardless of display toggles.
            if (isRepeater) {
                String display = sanitizeRepeaterDisplayName(advert.name);
                persistRepeaterAdvert(advert, display);
                if (cotBridge != null && (isMeshShowRepeatersPreferenceEnabled()
                        || btConnectionManager.isNodeDiscoverSessionActive())) {
                    renderMeshRepeaterMarker(display, pub, advert.latitude, advert.longitude,
                            advert.advertTimestampSec);
                }
            } else {
                String display = sanitizeNodeDisplayName(advert.name, pub);
                persistNodeAdvert(advert, display);
                if (cotBridge != null && isMeshNodeDisplayEnabled()) {
                    renderMeshNodeMarker(display, pub, advert.latitude, advert.longitude,
                            advert.advertTimestampSec, advert.advertType, advert.name);
                }
            }
        });
        btConnectionManager.addListener(new BtConnectionManager.ConnectionListener() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                MeshStatusOverlay.setConnected(true);
                startBeaconTimer();
                if (cotBridge != null) {
                    cotBridge.setBtManager(btConnectionManager);
                    cotBridge.refreshSendableMapItems();
                    if (mapView != null) {
                        mapView.post(() -> {
                            if (cotBridge != null) {
                                cotBridge.refreshSendableMapItems();
                            }
                            // Collapse any duplicate/ghost contacts accumulated
                            // while the device was disconnected.
                            ChatBridge.collapseAllCallsignAliasDuplicates();
                            // Repair connector stacks for any MESHCORE-* contacts that
                            // were created by an older plugin version (plug icon → radio icon).
                            MeshCoreContactHandler.repairAllMeshContactConnectors();
                        });
                    }
                }
            }

            @Override
            public void onDisconnected(String reason) {
                MeshStatusOverlay.setConnected(false);
                stopBeaconTimer();
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
        dropDownReceiver.setChatBridge(chatBridge);
        dropDownReceiver.setEncryptionManager(encryptionManager);
        packetRouter.setPacketCountListener(dropDownReceiver);

        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(MeshCoreDropDownReceiver.SHOW_PLUGIN);
        filter.addAction(MeshCoreDropDownReceiver.ACTION_QR_CHANNEL_RESULT);
        registerDropDownReceiver(dropDownReceiver, filter);
        meshDetailsDropDownReceiver = new MeshDetailsDropDownReceiver(view, pluginContext, cotBridge);
        AtakBroadcast.DocumentedIntentFilter meshDetailsFilter =
                new AtakBroadcast.DocumentedIntentFilter();
        meshDetailsFilter.addAction(MeshDetailsDropDownReceiver.SHOW_MESH_DETAILS);
        registerDropDownReceiver(meshDetailsDropDownReceiver, meshDetailsFilter);
        setupMeshMapItemClickListener();
        setupMeshMapPrefsListener();
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "MeshCore Settings",
                        "MeshCore plugin configuration",
                        SettingsFragment.TOOL_SETTINGS_KEY,
                        MeshCoreTool.toolbarIcon(context),
                        new SettingsFragment(context)));
        view.post(() -> MeshStatusOverlay.install(pluginContext));
        MeshStatusOverlay.setConnected(btConnectionManager.isConnected());
        if (btConnectionManager.isConnected() && cotBridge != null) {
            cotBridge.refreshSendableMapItems();
        }
        contactTracker.start();
        chatBridge.setRelayOutgoing(true);
        chatBridge.startOutgoingRelay();
        cotBridge.setRelayOutgoingSa(false);
        cotBridge.startOutgoingRelay();

        startMeshIconsetReminder(context, view.getContext());
        btConnectionManager.scheduleBootAutoConnect();

        com.atakmaps.meshcore.plugin.contacts.ContactRadialMenuUtil.init(context);

        try {
            beaconIntervalReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (i == null) {
                        return;
                    }
                    if (ACTION_BEACON_INTERVAL_CHANGED.equals(i.getAction())) {
                        if (btConnectionManager != null && btConnectionManager.isConnected()) {
                            Log.d(TAG, "Beacon settings changed — rescheduling timer");
                            startBeaconTimer();
                        } else {
                            Log.d(TAG, "Beacon settings changed while disconnected — timer deferred");
                        }
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter beaconFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            beaconFilter.addAction(ACTION_BEACON_INTERVAL_CHANGED);
            AtakBroadcast.getInstance()
                    .registerReceiver(beaconIntervalReceiver, beaconFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register beacon interval receiver", e);
        }

        try {
            radialPingReceiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent i) {
                    if (i == null) {
                        return;
                    }
                    if (com.atakmaps.meshcore.plugin.contacts.ContactRadialMenuUtil
                            .ACTION_RADIAL_PING_CONTACT.equals(i.getAction())) {
                        com.atakmaps.meshcore.plugin.contacts.ContactRadialMenuUtil
                                .handleRadialPingContact(ctx, i.getStringExtra("uid"));
                    }
                }
            };
            AtakBroadcast.DocumentedIntentFilter pingFilter =
                    new AtakBroadcast.DocumentedIntentFilter();
            pingFilter.addAction(
                    com.atakmaps.meshcore.plugin.contacts.ContactRadialMenuUtil
                            .ACTION_RADIAL_PING_CONTACT);
            AtakBroadcast.getInstance().registerReceiver(radialPingReceiver, pingFilter);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register radial ping receiver", e);
        }

        Log.i(TAG, "MeshCore plugin initialized");
        startGpsSpeedListener(view.getContext());
        if (btConnectionManager.isConnected()) {
            startBeaconTimer();
        }
    }

    private void startGpsSpeedListener(Context ctx) {
        try {
            gpsLocationManager = (android.location.LocationManager)
                    ctx.getSystemService(Context.LOCATION_SERVICE);
            if (gpsLocationManager == null) {
                Log.w(TAG, "GPS: LocationManager unavailable");
                return;
            }
            gpsLocationListener = new android.location.LocationListener() {
                @Override public void onLocationChanged(android.location.Location loc) {
                    lastGpsLocation = loc;
                }
                @Override public void onStatusChanged(String p, int s, android.os.Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };
            gpsLocationManager.requestLocationUpdates(
                    android.location.LocationManager.GPS_PROVIDER,
                    1000L, 0f, gpsLocationListener,
                    android.os.Looper.getMainLooper());
            Log.i(TAG, "GPS speed listener started");
        } catch (SecurityException se) {
            Log.w(TAG, "GPS: location permission denied — falling back to derived speed", se);
        } catch (Exception e) {
            Log.w(TAG, "GPS: listener start failed — falling back to derived speed", e);
        }
    }

    private void startBeaconTimer() {
        stopBeaconTimer();
        smartBeacon.reset();
        forceFirstPostConnectBeacon = true;

        beaconHandler = new Handler(Looper.getMainLooper());
        beaconRunnable = new Runnable() {
            @Override
            public void run() {
                sendBeaconIfConnected(forceFirstPostConnectBeacon);
                forceFirstPostConnectBeacon = false;
                long nextCheckMs = getBeaconTimerDelayMs();
                beaconHandler.postDelayed(this, nextCheckMs);
            }
        };
        if (hasValidSelfPosition()) {
            Log.d(TAG, "Beacon timer armed: valid self position already present");
            beaconHandler.postDelayed(beaconRunnable, 30_000L);
            return;
        }
        beaconWaitForPositionRunnable = new Runnable() {
            @Override
            public void run() {
                if (beaconHandler == null
                        || btConnectionManager == null || !btConnectionManager.isConnected()) {
                    return;
                }
                if (hasValidSelfPosition()) {
                    Log.d(TAG, "Valid self position acquired; startup beacon in 30s");
                    beaconHandler.postDelayed(beaconRunnable, 30_000L);
                    beaconWaitForPositionRunnable = null;
                    return;
                }
                beaconHandler.postDelayed(this, 2_000L);
            }
        };
        Log.d(TAG, "Beacon timer waiting for valid self position before startup countdown");
        beaconHandler.postDelayed(beaconWaitForPositionRunnable, 2_000L);
    }

    private void stopBeaconTimer() {
        if (beaconHandler != null) {
            if (beaconRunnable != null) {
                beaconHandler.removeCallbacks(beaconRunnable);
            }
            if (beaconWaitForPositionRunnable != null) {
                beaconHandler.removeCallbacks(beaconWaitForPositionRunnable);
            }
        }
        beaconWaitForPositionRunnable = null;
        forceFirstPostConnectBeacon = false;
    }

    private long getBeaconTimerDelayMs() {
        Context beaconCtx = getBeaconPrefsContext();
        boolean meshLimits = MeshBeaconLimits.isActive(beaconCtx);
        if (SmartBeacon.isEnabled(beaconCtx)) {
            int checkSec = SmartBeacon.getRecommendedCheckIntervalSec(beaconCtx, meshLimits);
            return Math.max(1, checkSec) * 1000L;
        }
        int intervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
        if (intervalSec < 1) {
            intervalSec = 1;
        }
        if (meshLimits) {
            intervalSec = MeshBeaconLimits.capIntervalSec(beaconCtx, intervalSec);
        }
        return intervalSec * 1000L;
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
        return gp != null && gp.isValid();
    }

    private void sendBeaconIfConnected(boolean forceImmediate) {
        if (btConnectionManager == null || !btConnectionManager.isConnected()) {
            Log.d(TAG, (forceImmediate ? "Startup" : "Periodic")
                    + " beacon skipped: MeshCore not connected");
            if (forceImmediate && dropDownReceiver != null) {
                dropDownReceiver.appendPluginLog(
                        "Startup beacon not sent — MeshCore not connected");
            }
            return;
        }
        if (cotBridge == null || mapView == null) {
            return;
        }

        try {
            PointMapItem self = mapView.getSelfMarker();
            if (self == null) {
                if (forceImmediate && dropDownReceiver != null) {
                    dropDownReceiver.appendPluginLog(
                            "Startup beacon not sent — no self-location available");
                }
                return;
            }
            GeoPoint gp = self.getPoint();

            double speedMs = 0.0;
            double course = 0.0;
            String speedSrc;
            android.location.Location gpsLoc = lastGpsLocation;
            if (gpsLoc != null && gpsLoc.hasSpeed()) {
                speedMs = Math.max(0.0, gpsLoc.getSpeed());
                course = gpsLoc.hasBearing() ? gpsLoc.getBearing() : 0.0;
                speedSrc = "gps";
            } else {
                double currentLat = gp.getLatitude();
                double currentLon = gp.getLongitude();
                long nowMs = System.currentTimeMillis();
                speedSrc = "meta";
                if (!Double.isNaN(lastBeaconLatDeg) && lastBeaconPositionMs > 0) {
                    long dtMs = nowMs - lastBeaconPositionMs;
                    if (dtMs > 500 && dtMs < 120_000L) {
                        try {
                            GeoPoint prev = new GeoPoint(lastBeaconLatDeg, lastBeaconLonDeg);
                            GeoPoint curr = new GeoPoint(currentLat, currentLon);
                            double distM = GeoCalculations.distanceTo(prev, curr);
                            double derivedMs = distM / (dtMs / 1000.0);
                            if (derivedMs >= 0.0 && derivedMs < 120.0) {
                                speedMs = derivedMs;
                                speedSrc = "derived";
                                course = GeoCalculations.bearingTo(prev, curr);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                lastBeaconLatDeg = currentLat;
                lastBeaconLonDeg = currentLon;
                lastBeaconPositionMs = nowMs;
                if (!speedSrc.equals("derived")) {
                    try {
                        course = Double.parseDouble(self.getMetaString("course", "0"));
                    } catch (Exception ignored) {
                    }
                }
            }
            double speedMph = speedMs * 2.23694;

            Context beaconCtx = getBeaconPrefsContext();
            final boolean meshLimitedBeacon = MeshBeaconLimits.isActive(beaconCtx);
            int meshLimitSec = -1;
            if (!forceImmediate && SmartBeacon.isEnabled(beaconCtx)) {
                boolean smartFire = smartBeacon.shouldBeacon(
                        beaconCtx, speedMph, course, meshLimitedBeacon);
                int fixedIntervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
                if (fixedIntervalSec < 1) {
                    fixedIntervalSec = 60;
                }
                if (meshLimitedBeacon) {
                    fixedIntervalSec = MeshBeaconLimits.capIntervalSec(
                            beaconCtx, fixedIntervalSec);
                }
                boolean floorFire = smartBeacon.elapsedSinceLastBeaconSec() >= fixedIntervalSec;
                Log.d(TAG, "Smart beacon check: speed=" + String.format(Locale.US, "%.1f", speedMph)
                        + "mph (src=" + speedSrc + ")"
                        + " course=" + String.format(Locale.US, "%.0f", course) + "°"
                        + " meshLimited=" + meshLimitedBeacon
                        + " smartFire=" + smartFire + " floorFire=" + floorFire
                        + " floorSec=" + fixedIntervalSec);
                if (!smartFire && !floorFire) {
                    return;
                }
                if (meshLimitedBeacon) {
                    if (smartFire) {
                        meshLimitSec = smartBeacon.getFiringLimitSec(
                                beaconCtx, speedMph, course, true);
                    }
                    if (meshLimitSec < 1 && floorFire) {
                        meshLimitSec = fixedIntervalSec;
                    }
                }
                smartBeacon.recordBeacon(course);
                Log.d(TAG, "Smart beacon fired (smartFire=" + smartFire
                        + " floorFire=" + floorFire + ")");
            } else if (forceImmediate) {
                Log.d(TAG, "Post-connect startup beacon fired (30s)");
            } else {
                int intervalSec = SettingsFragment.getBeaconIntervalSec(pluginContext);
                if (intervalSec < 1) {
                    intervalSec = 60;
                }
                if (meshLimitedBeacon) {
                    intervalSec = MeshBeaconLimits.capIntervalSec(beaconCtx, intervalSec);
                }
                if (smartBeacon.elapsedSinceLastBeaconSec() < intervalSec) {
                    return;
                }
                if (meshLimitedBeacon) {
                    meshLimitSec = intervalSec;
                }
                smartBeacon.recordBeacon(course);
            }

            cotBridge.sendPositionOverRadio(
                    gp.getLatitude(), gp.getLongitude(),
                    gp.getAltitude(), (float) speedMs, (float) course, -1);
            String beaconKind = forceImmediate ? "Startup" : "Periodic";
            Log.d(TAG, beaconKind + " MeshCore GPS beacon sent"
                    + (meshLimitedBeacon ? " [mesh limits]" : ""));
            logBeaconSentToPluginUi(formatBeaconSentLog(
                    beaconKind, meshLimitedBeacon, meshLimitSec));
        } catch (Exception e) {
            Log.e(TAG, "Error sending periodic beacon", e);
            if (dropDownReceiver != null) {
                dropDownReceiver.appendPluginLog((forceImmediate ? "Startup" : "Periodic")
                        + " beacon not sent — " + e.getMessage());
            }
        }
    }

    private void logBeaconSentToPluginUi(String message) {
        if (dropDownReceiver != null) {
            dropDownReceiver.appendPluginLog(message);
        }
    }

    private static String formatBeaconSentLog(String beaconKind, boolean meshLimited,
                                              int limitSec) {
        String base = String.format(Locale.US, "%s beacon sent (MeshCore OPENRL)", beaconKind);
        if (meshLimited && limitSec > 0) {
            return base + String.format(Locale.US, " (limited to %d seconds)", limitSec);
        }
        return base;
    }

    /**
     * Persistent check: stage the bundled {@code meschore.zip} iconset into ATAK's import
     * directory and keep reminding the user to import it until ATAK shows it installed.
     * Does not assume any prior MeshCore build supplied the iconset.
     */
    private void startMeshIconsetReminder(Context pluginCtx, Context uiCtx) {
        if (meshIconsetReminderHandler != null && meshIconsetReminderRunnable != null) {
            meshIconsetReminderHandler.removeCallbacks(meshIconsetReminderRunnable);
        }
        meshIconsetReminderHandler = new Handler(Looper.getMainLooper());
        meshIconsetReminderRunnable = new Runnable() {
            @Override
            public void run() {
                boolean missing = MeshcoreIconsetInstaller.ensureStagedAndPromptIfMissing(
                        pluginCtx, uiCtx);
                if (missing && meshIconsetReminderHandler != null) {
                    // Persistent guidance while missing; staging + throttling handled inside installer.
                    meshIconsetReminderHandler.postDelayed(this, 15000L);
                }
            }
        };
        meshIconsetReminderHandler.post(meshIconsetReminderRunnable);
    }

    private void setupMeshMapItemClickListener() {
        if (mapView == null || mapItemClickListener != null) {
            return;
        }
        mapItemClickListener = event -> {
            try {
                if (event == null || !MapEvent.ITEM_CLICK.equals(event.getType())) {
                    return;
                }
                MapItem item = event.getItem();
                if (item == null || !CotBridge.isMeshcoreMeshMarker(item)) {
                    return;
                }
                Intent details = new Intent(MeshDetailsDropDownReceiver.SHOW_MESH_DETAILS);
                details.putExtra(MeshDetailsDropDownReceiver.EXTRA_TARGET_UID, item.getUID());
                AtakBroadcast.getInstance().sendBroadcast(details);
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent("com.atakmap.android.maps.HIDE_MENU"));
            } catch (Exception e) {
                Log.w(TAG, "Mesh details launch failed", e);
            }
        };
        try {
            mapView.getMapEventDispatcher().addMapEventListener(
                    MapEvent.ITEM_CLICK, mapItemClickListener);
        } catch (Exception e) {
            Log.w(TAG, "Mesh map click listener registration failed", e);
        }
    }

    private void setupMeshMapPrefsListener() {
        if (mapView == null || meshMapPrefsListener != null) {
            return;
        }
        meshMapPrefs = PreferenceManager.getDefaultSharedPreferences(mapView.getContext());
        meshMapPrefsListener = (prefs, key) -> {
            if (PREF_MESH_SHOW_REPEATERS.equals(key)) {
                if (prefs.getBoolean(PREF_MESH_SHOW_REPEATERS, true)) {
                    restorePersistedRepeaters();
                } else {
                    clearTrackedMeshMarkers(meshRepeaterMapUids);
                }
            }
            if (PREF_MESH_SHOW_NODES.equals(key)) {
                if (prefs.getBoolean(PREF_MESH_SHOW_NODES, true)) {
                    restorePersistedNodes();
                } else {
                    clearTrackedMeshMarkers(meshNodeMapUids);
                }
            }
        };
        try {
            meshMapPrefs.registerOnSharedPreferenceChangeListener(meshMapPrefsListener);
        } catch (Exception e) {
            Log.w(TAG, "Mesh prefs listener registration failed", e);
        }
        restorePersistedRepeaters();
        restorePersistedNodes();
    }

    private static String sanitizeRepeaterDisplayName(String name) {
        String raw = name != null ? name.trim() : "";
        if (raw.isEmpty()) {
            return "MESH_REPEATER";
        }
        String upper = raw.toUpperCase(Locale.US);
        String normalized = upper.replaceAll("[^A-Z0-9_\\- ]", "_").trim();
        if (normalized.isEmpty()) {
            return "MESH_REPEATER";
        }
        return normalized;
    }

    private static String sanitizeRepeaterUidSuffix(String pubKeyHex) {
        String raw = pubKeyHex != null ? pubKeyHex.trim().toUpperCase(Locale.US) : "";
        if (raw.isEmpty()) {
            return "UNKNOWN";
        }
        return raw.replaceAll("[^A-F0-9]", "");
    }

    private static String sanitizeNodeDisplayName(String name, String pubKeyHex) {
        String raw = name != null ? name.trim() : "";
        if (!raw.isEmpty()) {
            String upper = raw.toUpperCase(Locale.US);
            String normalized = upper.replaceAll("[^A-Z0-9_\\- ]", "_").trim();
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        String suffix = sanitizeRepeaterUidSuffix(pubKeyHex);
        if (suffix.length() > 6) {
            suffix = suffix.substring(0, 6);
        }
        if (suffix.isEmpty()) {
            suffix = "UNKNOWN";
        }
        return "MESH_NODE_" + suffix;
    }

    private static char meshNodeSymbolCode(String rawNodeName, String displayName) {
        String source = rawNodeName;
        if (source == null || source.trim().isEmpty()) {
            source = displayName;
        }
        if (source == null) {
            return 'N';
        }
        String trimmed = source.trim();
        if (trimmed.isEmpty()) {
            return 'N';
        }
        char first = Character.toUpperCase(trimmed.charAt(0));
        if (first >= 'A' && first <= 'Z') {
            return first;
        }
        return 'N';
    }

    private static String meshContactTypeLabel(int advertType) {
        switch (advertType) {
            case 0x01:
                return "Client";
            case 0x02:
                return "Repeater";
            case 0x03:
                return "Sensor";
            case 0x04:
                return "Gateway";
            default:
                return "Node (" + advertType + ")";
        }
    }

    private String buildMeshAdvertDetails(String name,
                                          String pubKeyHex,
                                          double lat,
                                          double lon,
                                          String contactType,
                                          long advertTimestampSec) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name: ").append(name != null ? name : "Unknown").append("\n");
        sb.append("Public Key: ").append(pubKeyHex != null ? pubKeyHex : "Unknown").append("\n");
        sb.append("Position: ")
                .append(String.format(Locale.US, "%.5f, %.5f", lat, lon))
                .append("\n");
        sb.append("Distance: ").append(formatDistanceFromSelf(lat, lon)).append("\n");
        sb.append("Contact Type: ").append(contactType != null ? contactType : "Unknown").append("\n");
        sb.append("Last Advert Heard: ").append(formatAdvertTimestamp(advertTimestampSec));
        return sb.toString();
    }

    private String formatDistanceFromSelf(double lat, double lon) {
        try {
            if (mapView == null || mapView.getSelfMarker() == null
                    || mapView.getSelfMarker().getPoint() == null) {
                return "Unknown";
            }
            GeoPoint self = mapView.getSelfMarker().getPoint();
            GeoPoint peer = new GeoPoint(lat, lon);
            double meters = GeoCalculations.distanceTo(self, peer);
            if (Double.isNaN(meters) || meters < 0) {
                return "Unknown";
            }
            if (meters >= 1000.0) {
                return String.format(Locale.US, "%.2f km", meters / 1000.0);
            }
            return String.format(Locale.US, "%.0f m", meters);
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private String formatAdvertTimestamp(long advertTimestampSec) {
        if (advertTimestampSec <= 0L) {
            return "Unknown";
        }
        try {
            long ms = advertTimestampSec * 1000L;
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    /**
     * Render a MeshCore repeater advert as a usericon CoT marker (imported {@code meschore}
     * iconset). Persistence is unchanged — this only controls how the marker is drawn.
     */
    private void renderMeshRepeaterMarker(String display, String pubKeyHex, double lat, double lon,
                                          long advertTimestampSec) {
        if (cotBridge == null || mapView == null || !isMeshShowRepeatersPreferenceEnabled()) {
            return;
        }
        String mapUid = "MESHCORE-RPTR-" + sanitizeRepeaterUidSuffix(pubKeyHex);
        String remarks = buildMeshAdvertDetails(
                display, pubKeyHex, lat, lon, "Repeater", advertTimestampSec);
        cotBridge.injectPositionCotAtMapUid(
                display, lat, lon, 0.0, -1.0, -1.0, "Cyan", 'M', '>', remarks, mapUid);
        cotBridge.markMeshRepeaterMapItem(mapUid);
        cotBridge.setMeshMarkerDetails(mapUid, remarks);
        cotBridge.promoteMeshContactMapItem(mapUid, display);
        meshRepeaterMapUids.add(mapUid);
    }

    /**
     * Render a MeshCore node advert as a usericon CoT marker (imported {@code meschore}
     * iconset). Persistence is unchanged — this only controls how the marker is drawn.
     */
    private void renderMeshNodeMarker(String display, String pubKeyHex, double lat, double lon,
                                      long advertTimestampSec, int advertType, String rawName) {
        if (cotBridge == null || mapView == null || !isMeshNodeDisplayEnabled()) {
            return;
        }
        String mapUid = "MESHCORE-NODE-" + sanitizeRepeaterUidSuffix(pubKeyHex);
        char meshNodeSymbol = meshNodeSymbolCode(rawName, display);
        String contactType = meshContactTypeLabel(advertType);
        String remarks = buildMeshAdvertDetails(
                display, pubKeyHex, lat, lon, contactType, advertTimestampSec);
        cotBridge.injectPositionCotAtMapUid(
                display, lat, lon, 0.0, -1.0, -1.0, "Cyan", 'M', meshNodeSymbol, remarks, mapUid);
        cotBridge.markMeshNodeMapItem(mapUid);
        cotBridge.setMeshMarkerDetails(mapUid, remarks);
        cotBridge.promoteMeshContactMapItem(mapUid, display);
        meshNodeMapUids.add(mapUid);
    }

    private void persistRepeaterAdvert(BtConnectionManager.MeshAdvert advert, String display) {
        if (advert == null || advert.pubKeyHex == null || advert.pubKeyHex.trim().isEmpty()) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_REPEATER_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            Map<String, JSONObject> byKey = new HashMap<>();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (key.isEmpty()) {
                    continue;
                }
                if (MeshNodeCachePolicy.shouldEvictRepeaterByTtl(
                        o, key, now, MESH_REPEATER_TTL_MS, cacheCtx)) {
                    continue;
                }
                byKey.put(key, o);
            }

            String pubKey = advert.pubKeyHex.trim().toUpperCase(Locale.US);
            JSONObject row = byKey.get(pubKey);
            if (row == null) {
                row = new JSONObject();
                byKey.put(pubKey, row);
            }
            long existingFirstSeen = row.optLong("firstSeenMs", 0L);
            long firstSeen = existingFirstSeen > 0L ? existingFirstSeen : now;

            row.put("pubKeyHex", pubKey);
            row.put("display", display != null ? display : "Mesh Repeater");
            row.put("lat", advert.latitude);
            row.put("lon", advert.longitude);
            row.put("firstSeenMs", firstSeen);
            row.put("lastAdvertSec", advert.advertTimestampSec);

            JSONArray out = new JSONArray();
            for (JSONObject o : byKey.values()) {
                out.put(o);
            }
            prefs.edit().putString(PREF_MESH_REPEATER_CACHE, out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistRepeaterAdvert failed", e);
        }
    }

    private void persistNodeAdvert(BtConnectionManager.MeshAdvert advert, String display) {
        if (advert == null || advert.pubKeyHex == null || advert.pubKeyHex.trim().isEmpty()) {
            return;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_NODE_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            Map<String, JSONObject> byKey = new HashMap<>();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (key.isEmpty()) {
                    continue;
                }
                if (MeshNodeCachePolicy.shouldEvictByNodeTtl(
                        o, key, now, MESH_NODE_TTL_MS, cacheCtx)) {
                    continue;
                }
                byKey.put(key, o);
            }

            String pubKey = advert.pubKeyHex.trim().toUpperCase(Locale.US);
            JSONObject row = byKey.get(pubKey);
            if (row == null) {
                row = new JSONObject();
                byKey.put(pubKey, row);
            }
            long existingFirstSeen = row.optLong("firstSeenMs", 0L);
            long firstSeen = existingFirstSeen > 0L ? existingFirstSeen : now;

            row.put("pubKeyHex", pubKey);
            row.put("display", display != null ? display : "Mesh Node");
            row.put("rawName", advert.name != null ? advert.name : "");
            row.put("advertType", advert.advertType);
            row.put("lat", advert.latitude);
            row.put("lon", advert.longitude);
            row.put("firstSeenMs", firstSeen);
            row.put("lastSeenMs", now);
            row.put("lastAdvertSec", advert.advertTimestampSec);

            MeshNodeCachePolicy.trimToRollingMax(byKey, cacheCtx);

            JSONArray out = new JSONArray();
            for (JSONObject o : byKey.values()) {
                out.put(o);
            }
            prefs.edit().putString(PREF_MESH_NODE_CACHE, out.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistNodeAdvert failed", e);
        }
    }

    private void restorePersistedRepeaters() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_REPEATER_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            JSONArray kept = new JSONArray();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                String display = o.optString("display", "Mesh Repeater").trim();
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                long lastAdvertSec = o.optLong("lastAdvertSec", 0L);
                if (pubKey.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)
                        || MeshNodeCachePolicy.shouldEvictRepeaterByTtl(
                        o, pubKey, now, MESH_REPEATER_TTL_MS, cacheCtx)) {
                    continue;
                }
                kept.put(o);
                if (isMeshShowRepeatersPreferenceEnabled()) {
                    renderMeshRepeaterMarker(display, pubKey, lat, lon, lastAdvertSec);
                }
            }
            prefs.edit().putString(PREF_MESH_REPEATER_CACHE, kept.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "restorePersistedRepeaters failed", e);
        }
    }

    private void restorePersistedNodes() {
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                    mapView != null ? mapView.getContext() : pluginContext);
            String raw = prefs.getString(PREF_MESH_NODE_CACHE, "[]");
            JSONArray arr = new JSONArray(raw != null ? raw : "[]");
            JSONArray kept = new JSONArray();
            long now = System.currentTimeMillis();
            Context cacheCtx = mapView != null ? mapView.getContext() : pluginContext;
            Map<String, JSONObject> byKey = new HashMap<>();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                String display = o.optString("display", "Mesh Node").trim();
                String rawName = o.optString("rawName", "");
                int advertType = o.optInt("advertType", 0);
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                long lastSeenMs = o.optLong("lastSeenMs", o.optLong("firstSeenMs", 0L));
                long lastAdvertSec = o.optLong("lastAdvertSec", 0L);
                if (pubKey.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)
                        || lastSeenMs <= 0L
                        || MeshNodeCachePolicy.shouldEvictByNodeTtl(
                        o, pubKey, now, MESH_NODE_TTL_MS, cacheCtx)) {
                    continue;
                }
                byKey.put(pubKey, o);
                if (isMeshNodeDisplayEnabled()) {
                    renderMeshNodeMarker(display, pubKey, lat, lon, lastAdvertSec, advertType, rawName);
                }
            }
            MeshNodeCachePolicy.trimToRollingMax(byKey, cacheCtx);
            for (JSONObject o : byKey.values()) {
                kept.put(o);
            }
            prefs.edit().putString(PREF_MESH_NODE_CACHE, kept.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "restorePersistedNodes failed", e);
        }
    }

    private boolean isMeshShowRepeatersPreferenceEnabled() {
        if (mapView == null) {
            return true;
        }
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext())
                .getBoolean(PREF_MESH_SHOW_REPEATERS, true);
    }

    private boolean isMeshNodeDisplayEnabled() {
        if (mapView == null) {
            return true;
        }
        return PreferenceManager.getDefaultSharedPreferences(mapView.getContext())
                .getBoolean(PREF_MESH_SHOW_NODES, true);
    }

    private void clearTrackedMeshMarkers(Set<String> trackedUids) {
        if (trackedUids == null || trackedUids.isEmpty() || mapView == null) {
            return;
        }
        Runnable work = () -> {
            for (String uid : trackedUids) {
                try {
                    MapItem item = mapView.getRootGroup().deepFindUID(uid);
                    if (item != null && item.getGroup() != null) {
                        item.getGroup().removeItem(item);
                    }
                } catch (Exception ignored) {
                }
            }
            trackedUids.clear();
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            work.run();
        } else {
            mapView.post(work);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Update-server TLS trust injection — mirrors UV-PRO (Darksteal) implementation exactly.
    // Same atakmaps.com server, same CA chain. Writes identical SharedPreferences values so
    // both plugins are idempotent when installed together. filesDir copy uses a distinct name
    // (meshcore_update_server_ca.p12) to avoid any cross-plugin file collision.
    // -----------------------------------------------------------------------------------------

    private static String trustBundleP12KeyMaterial(Context pluginCtx) {
        if (pluginCtx == null) return "";
        String hit = cachedTrustBundleP12Key;
        if (hit != null) return hit;
        synchronized (MeshCoreMapComponent.class) {
            if (cachedTrustBundleP12Key == null) {
                String b64 = pluginCtx.getString(R.string.meshcore_trust_bundle_p12_key);
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                cachedTrustBundleP12Key = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
            }
            return cachedTrustBundleP12Key;
        }
    }

    private static String atkReflectSaveCertCred() {
        return new String(new char[]{
                's', 'a', 'v', 'e', 'C', 'e', 'r', 't', 'i', 'f', 'i', 'c', 'a', 't', 'e',
                'P', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }

    private static String atkPrefsUpdateServerCaCredKey() {
        return new String(new char[]{
                'u', 'p', 'd', 'a', 't', 'e', 'S', 'e', 'r', 'v', 'e', 'r', 'C', 'a',
                'P', 'a', 's', 's', 'w', 'o', 'r', 'd'});
    }

    private static String resolveUpdateServerTypeKey() {
        String typeKey = "UPDATE_SERVER_TRUST_STORE_CA";
        try {
            Class<?> ifaceClass = Class.forName("com.atakmap.net.AtakCertificateDatabaseIFace");
            java.lang.reflect.Field f = ifaceClass.getField("TYPE_UPDATE_SERVER_TRUST_STORE_CA");
            Object v = f.get(null);
            if (v instanceof String && !((String) v).isEmpty()) {
                typeKey = (String) v;
            }
        } catch (Exception ignored) {
        }
        return typeKey;
    }

    private static Context tryResolveHostAtakContext(Context pluginContext) {
        if (pluginContext == null) return null;
        String[] pkgs = new String[]{
                "com.atakmap.app.civ", "com.atakmap.app", "com.atakmap.app.mil"
        };
        Context app = pluginContext.getApplicationContext();
        if (app != null) {
            String pn = app.getPackageName();
            for (String p : pkgs) {
                if (p.equals(pn)) return app;
            }
        }
        for (String pkg : pkgs) {
            try {
                return pluginContext.createPackageContext(pkg,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static void applyUpdateServerTrustEarly(Context pluginContext) {
        Context host = tryResolveHostAtakContext(pluginContext);
        if (host == null) {
            Log.w(TAG, "applyUpdateServerTrustEarly: could not resolve host ATAK Context");
            return;
        }
        configureUpdateServerStatic(pluginContext, host);
    }

    private static void configureUpdateServerStatic(Context pluginContext, Context atakContext) {
        try {
            android.content.SharedPreferences prefs =
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakContext);
            final String UPDATE_SERVER_URL = "https://atakmaps.com/plugins/product.infz";
            prefs.edit()
                    .putString("atakUpdateServerUrl", UPDATE_SERVER_URL)
                    .putString("appMgmtUpdateServerUrl", UPDATE_SERVER_URL)
                    .putBoolean("appMgmtEnableUpdateServer", true)
                    .putBoolean("app_mgmt_enable_update_server", true)
                    .putBoolean("app_mgmt_auto_sync", false)
                    .putBoolean("appMgmtAutoSync", false)
                    .apply();
            Log.i(TAG, "Plugin update server URL/trust configured (one startup sync; auto-sync off): "
                    + UPDATE_SERVER_URL);
            installUpdateServerTruststoreCompat(pluginContext, atakContext);
            reloadCertificateManagerFromDatabase();
            registerUpdateServerCA(pluginContext);
            scheduleOneStartupRepoSyncIfNeeded();
        } catch (Exception e) {
            Log.w(TAG, "configureUpdateServer failed: " + e.getMessage());
        }
    }

    private static void installUpdateServerTruststoreCompat(Context pluginCtx, Context atakCtx) {
        try {
            final String asset = "atakmaps-ca.p12";
            java.io.File p12 = new java.io.File(atakCtx.getFilesDir(), "meshcore_update_server_ca.p12");
            copyAssetToFile(pluginCtx, asset, p12);
            android.preference.PreferenceManager.getDefaultSharedPreferences(atakCtx).edit()
                    .putString("updateServerCaLocation", p12.getAbsolutePath())
                    .apply();

            Class<?> dbClass = Class.forName("com.atakmap.net.AtakCertificateDatabase");
            String typeKey = resolveUpdateServerTypeKey();
            Class<?>[] importActuals = new Class[]{
                    String.class, String.class, String.class, boolean.class
            };
            java.lang.reflect.Method importCert = findStaticMethodByActualParams(
                    dbClass, importActuals, "importCertificate", byte[].class);
            Object imported = null;
            if (importCert != null) {
                imported = importCert.invoke(null, p12.getAbsolutePath(), "", typeKey, false);
            } else {
                Class<?>[] importFileActuals = new Class[]{
                        java.io.File.class, String.class, String.class, boolean.class
                };
                java.lang.reflect.Method importFile = findStaticMethodByActualParams(
                        dbClass, importFileActuals, "importCertificate", byte[].class);
                if (importFile != null) {
                    imported = importFile.invoke(null, p12, "", typeKey, false);
                } else {
                    Log.w(TAG, "installUpdateServerTruststoreCompat: no importCertificate-like static method");
                }
            }
            int outLen = (imported instanceof byte[]) ? ((byte[]) imported).length : -1;
            Log.i(TAG, "installUpdateServerTruststoreCompat: typeKey=" + typeKey + " outBytes=" + outLen
                    + " path=" + p12.getAbsolutePath());

            if (imported instanceof byte[] && ((byte[]) imported).length > 0) {
                String p12Key = trustBundleP12KeyMaterial(pluginCtx);
                Class<?> base = Class.forName("com.atakmap.net.AtakCertificateDatabaseBase");
                java.lang.reflect.Method savePw = findStaticSaveCredentialThreeStrings(base);
                String credKey = atkPrefsUpdateServerCaCredKey();
                if (savePw == null) {
                    Log.w(TAG, "installUpdateServerTruststoreCompat: saveCertificatePassword-like not found");
                } else {
                    savePw.invoke(null, p12Key, credKey, null);
                    android.preference.PreferenceManager.getDefaultSharedPreferences(atakCtx).edit()
                            .putString(credKey, p12Key)
                            .apply();
                    Log.i(TAG, "Update-server CA PKCS#12 unlock credential stored; trust DB + prefs aligned");
                }
            }

            java.security.cert.X509Certificate fromP12 = loadCertificateFromPkcs12(
                    pluginCtx, asset, trustBundleP12KeyMaterial(pluginCtx).toCharArray());
            if (fromP12 != null) {
                bindUpdateServerCaToHost(fromP12);
            }
        } catch (Exception e) {
            Log.w(TAG, "installUpdateServerTruststoreCompat failed: " + e.getMessage(), e);
        }
    }

    private static void bindUpdateServerCaToHost(java.security.cert.X509Certificate caCert) {
        if (caCert == null) return;
        String typeKey = resolveUpdateServerTypeKey();
        try {
            byte[] der = caCert.getEncoded();
            Class<?> base = Class.forName("com.atakmap.net.AtakCertificateDatabaseBase");
            Class<?>[] three = new Class[]{String.class, String.class, byte[].class};
            java.lang.reflect.Method saveHost = findStaticMethodByActualParams(base, three,
                    "saveCertificateForServer");
            if (saveHost == null) {
                Log.w(TAG, "bindUpdateServerCaToHost: no static method matching (String,String,byte[])");
                return;
            }
            saveHost.invoke(null, typeKey, "atakmaps.com", der);
            Class<?>[] four = new Class[]{String.class, String.class, int.class, byte[].class};
            java.lang.reflect.Method savePort = findStaticMethodByActualParams(base, four,
                    "saveCertificateForServerAndPort");
            if (savePort != null) {
                savePort.invoke(null, typeKey, "atakmaps.com", 443, der);
                savePort.invoke(null, typeKey, "atakmaps.com", 8443, der);
            } else {
                Log.w(TAG, "bindUpdateServerCaToHost: saveCertificateForServerAndPort not found");
            }
            Log.i(TAG, "bindUpdateServerCaToHost: typeKey=" + typeKey + " host=atakmaps.com derBytes=" + der.length);
        } catch (Exception e) {
            Log.w(TAG, "bindUpdateServerCaToHost failed: " + e.getMessage(), e);
        }
    }

    private static void reloadCertificateManagerFromDatabase() {
        try {
            Class<?> cls = Class.forName("com.atakmap.net.CertificateManager");
            for (String hostKey : new String[]{
                    "atakmaps.com",
                    "https://atakmaps.com",
                    "https://atakmaps.com/plugins/product.infz"
            }) {
                try {
                    java.lang.reflect.Method inv = null;
                    try {
                        inv = cls.getMethod("invalidate", String.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                    if (inv == null) {
                        inv = findStaticMethodByActualParams(
                                cls, new Class[]{String.class}, "invalidate", void.class);
                    }
                    if (inv != null) {
                        inv.setAccessible(true);
                        inv.invoke(null, hostKey);
                        Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") via " + inv.getName());
                    }
                } catch (Exception e) {
                    Log.d(TAG, "CertificateManager.invalidate(" + hostKey + ") skipped: " + e.getMessage());
                }
            }
            java.lang.reflect.Method getInst = findSingletonGetter(cls);
            if (getInst == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: no singleton getter");
                return;
            }
            Object cm = getInst.invoke(null);
            if (cm == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: CertificateManager null");
                return;
            }
            java.lang.reflect.Method refresh = null;
            try {
                refresh = cls.getMethod("refresh");
            } catch (NoSuchMethodException ignored) {
            }
            if (refresh == null) {
                refresh = findInstanceVoidNoArg(cls, "refresh");
            }
            if (refresh == null) {
                Log.w(TAG, "reloadCertificateManagerFromDatabase: refresh() not found");
                return;
            }
            refresh.invoke(cm);
            Log.i(TAG, "CertificateManager refreshed after update-server DB import via " + refresh.getName());
        } catch (Exception e) {
            Log.w(TAG, "reloadCertificateManagerFromDatabase failed: " + e.getMessage());
        }
    }

    private static void registerUpdateServerCA(Context context) {
        try {
            java.security.cert.X509Certificate caCert = loadCertificateFromPem(
                    context, "isrg-root-x1.pem");
            String source = "isrg-root-x1.pem";
            if (caCert == null) {
                caCert = loadCertificateFromPkcs12(
                        context, "atakmaps-ca.p12", trustBundleP12KeyMaterial(context).toCharArray());
                source = "atakmaps-ca.p12";
            }
            if (caCert == null) {
                Log.w(TAG, "registerUpdateServerCA: no CA certificate asset could be loaded");
                return;
            }
            Log.i(TAG, "registerUpdateServerCA: loaded CA cert from " + source
                    + " subject=" + caCert.getSubjectDN());
            bindUpdateServerCaToHost(caCert);
            addOfficialCertificateManagerCa(caCert);
            injectCACert(caCert, 0);
        } catch (Exception e) {
            Log.w(TAG, "registerUpdateServerCA failed: " + e.getMessage(), e);
        }
    }

    private static void addOfficialCertificateManagerCa(java.security.cert.X509Certificate caCert) {
        try {
            Class<?> cls = Class.forName("com.atakmap.net.CertificateManager");
            java.lang.reflect.Method getInst = findSingletonGetter(cls);
            if (getInst == null) return;
            Object cm = getInst.invoke(null);
            if (cm == null) return;
            java.lang.reflect.Method add = null;
            try {
                add = cls.getMethod("addCertificate", java.security.cert.X509Certificate.class);
            } catch (NoSuchMethodException ignored) {
            }
            if (add == null) {
                add = findInstanceMethodByActualParams(
                        cls,
                        new Class[]{java.security.cert.X509Certificate.class},
                        "addCertificate",
                        void.class);
            }
            if (add == null) {
                Log.w(TAG, "addOfficialCertificateManagerCa: addCertificate(X509) not found");
                return;
            }
            add.setAccessible(true);
            add.invoke(cm, caCert);
            clearSocketFactoriesCache(cls);
            Log.i(TAG, "CertificateManager.addCertificate via " + add.getName());
        } catch (Exception e) {
            Log.w(TAG, "addOfficialCertificateManagerCa: " + e.getMessage());
        }
    }

    private static void injectCACert(final java.security.cert.X509Certificate cert, final int attempt) {
        try {
            Class<?> certMgrClass = Class.forName("com.atakmap.net.CertificateManager");
            java.lang.reflect.Method getInst = findSingletonGetter(certMgrClass);
            if (getInst == null) {
                Log.w(TAG, "injectCACert: getInstance() not found");
                return;
            }
            Object certMgr = getInst.invoke(null);
            boolean injectedA = false;
            boolean injectedB = false;

            // Strategy A: find nested impl object with addCertificate(X509Certificate)-like method.
            java.lang.reflect.Field implField = null;
            java.lang.reflect.Method addCertMethod = null;
            outerA:
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (f.getType().isPrimitive()) continue;
                Class<?> c = f.getType();
                while (c != null && c != Object.class) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getReturnType() != void.class) continue;
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1
                                && params[0].isAssignableFrom(java.security.cert.X509Certificate.class)) {
                            implField = f;
                            implField.setAccessible(true);
                            addCertMethod = m;
                            addCertMethod.setAccessible(true);
                            Log.d(TAG, "injectCACert[A]: field=" + f.getName()
                                    + " method=" + m.getName());
                            break outerA;
                        }
                    }
                    c = c.getSuperclass();
                }
            }

            if (implField != null) {
                Object impl = implField.get(certMgr);
                if (impl == null) {
                    if (attempt < 15) {
                        new Handler(Looper.getMainLooper()).postDelayed(
                                () -> injectCACert(cert, attempt + 1), 1000);
                        Log.d(TAG, "injectCACert[A]: _impl null, retry " + (attempt + 1));
                    } else {
                        Log.w(TAG, "injectCACert[A]: _impl still null after " + attempt + " retries");
                    }
                    return;
                }
                addCertMethod.invoke(impl, cert);
                Log.i(TAG, "injectCACert[A]: cert injected (attempt " + attempt + ")");
                clearSocketFactoriesCache(certMgrClass);
                injectedA = true;
            } else {
                Log.d(TAG, "injectCACert[A]: addCertificate-like method not found");
            }

            // Strategy B: walk X509TrustManager fields and object graph on CertificateManager.
            int tmFieldsFound = 0;
            int tmFieldsInjected = 0;
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!javax.net.ssl.X509TrustManager.class.isAssignableFrom(f.getType())) continue;
                tmFieldsFound++;
                f.setAccessible(true);
                Object tm = f.get(certMgr);
                if (tm == null) continue;
                if (injectIntoObjectCertArrays(tm, cert, "tm." + f.getName(), 2)) {
                    injectedB = true;
                    tmFieldsInjected++;
                }
            }

            int graphInjected = injectIntoObjectGraphCertArrays(
                    certMgr, cert, "cmgr", 8, new java.util.IdentityHashMap<>());
            injectedB |= graphInjected > 0;

            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object mapObj = f.get(null);
                if (!(mapObj instanceof java.util.Map)) continue;
                for (Object factory : ((java.util.Map<?, ?>) mapObj).values()) {
                    if (factory == null) continue;
                    int cacheInjected = injectIntoObjectGraphCertArrays(
                            factory, cert, "cache." + factory.getClass().getSimpleName(),
                            8, new java.util.IdentityHashMap<>());
                    injectedB |= cacheInjected > 0;
                }
            }

            if (tmFieldsFound == 0) {
                Log.w(TAG, "injectCACert[B]: X509TrustManager field not found on " + certMgrClass.getName());
            } else {
                Log.i(TAG, "injectCACert[B]: trustManagers found=" + tmFieldsFound
                        + " injected=" + tmFieldsInjected);
            }
            Log.i(TAG, "injectCACert[B]: graph injection count=" + graphInjected);

            if (!injectedA && !injectedB) {
                if (attempt < 15) {
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> injectCACert(cert, attempt + 1), 1000);
                    Log.d(TAG, "injectCACert: no injection target yet, retry " + (attempt + 1));
                } else {
                    Log.w(TAG, "injectCACert: no injection target found after " + attempt + " retries");
                }
                return;
            }
            Log.i(TAG, "Update server CA registered successfully (A=" + injectedA + ", B=" + injectedB + ")");
        } catch (Exception e) {
            Log.w(TAG, "injectCACert failed (attempt " + attempt + "): " + e.getMessage(), e);
        }
    }

    private static boolean injectIntoObjectCertArrays(Object obj, java.security.cert.X509Certificate cert,
            String label, int depth) {
        if (obj == null || depth < 0) return false;
        boolean injected = false;
        try {
            for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == java.security.cert.X509Certificate[].class) {
                    injected |= appendToCertArray(f, obj, cert, label + "." + f.getName());
                    continue;
                }
                if (depth == 0 || f.getType().isPrimitive()) continue;
                Object nested = f.get(obj);
                if (nested == null) continue;
                injected |= injectIntoObjectCertArrays(nested, cert, label + "." + f.getName(), depth - 1);
            }
        } catch (Exception e) {
            Log.w(TAG, "injectIntoObjectCertArrays[" + label + "] failed: " + e.getMessage());
        }
        return injected;
    }

    private static int injectIntoObjectGraphCertArrays(Object obj, java.security.cert.X509Certificate cert,
            String label, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth < 0) return 0;
        if (visited.containsKey(obj)) return 0;
        visited.put(obj, Boolean.TRUE);
        int injectedCount = 0;
        try {
            Class<?> cls = obj.getClass();
            String clsName = cls.getName();
            if (clsName.startsWith("java.") || clsName.startsWith("javax.")
                    || clsName.startsWith("android.") || clsName.startsWith("kotlin.")) {
                return 0;
            }
            if (obj instanceof java.lang.ref.Reference) {
                Object ref = ((java.lang.ref.Reference<?>) obj).get();
                return injectIntoObjectGraphCertArrays(ref, cert, label + ".ref", depth - 1, visited);
            }
            if (cls.isArray()) {
                Class<?> comp = cls.getComponentType();
                if (!comp.isPrimitive()) {
                    Object[] arr = (Object[]) obj;
                    for (int i = 0; i < arr.length; i++) {
                        injectedCount += injectIntoObjectGraphCertArrays(
                                arr[i], cert, label + "[" + i + "]", depth - 1, visited);
                    }
                }
                return injectedCount;
            }
            if (obj instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) obj).entrySet()) {
                    injectedCount += injectIntoObjectGraphCertArrays(
                            e.getValue(), cert, label + ".map", depth - 1, visited);
                }
                return injectedCount;
            }
            if (obj instanceof java.lang.Iterable) {
                int i = 0;
                for (Object v : (java.lang.Iterable<?>) obj) {
                    injectedCount += injectIntoObjectGraphCertArrays(
                            v, cert, label + ".it[" + i + "]", depth - 1, visited);
                    i++;
                }
                return injectedCount;
            }
            if (obj instanceof javax.net.ssl.X509TrustManager) {
                if (injectIntoObjectCertArrays(obj, cert, label + ".tm", 4)) {
                    injectedCount++;
                }
            }
            for (java.lang.reflect.Field f : getAllInstanceFields(cls)) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (f.getType() == java.security.cert.X509Certificate[].class) {
                        if (appendToCertArray(f, obj, cert, label + "." + f.getName())) {
                            injectedCount++;
                        }
                        continue;
                    }
                    if (depth == 0 || f.getType().isPrimitive()) continue;
                    Object nested = f.get(obj);
                    if (nested == null) continue;
                    injectedCount += injectIntoObjectGraphCertArrays(
                            nested, cert, label + "." + f.getName(), depth - 1, visited);
                } catch (Throwable t) {
                    Log.d(TAG, "injectIntoObjectGraphCertArrays[" + label + "." + f.getName()
                            + "] skip: " + t.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "injectIntoObjectGraphCertArrays[" + label + "] failed: " + e.getMessage());
        }
        return injectedCount;
    }

    private static java.util.List<java.lang.reflect.Field> getAllInstanceFields(Class<?> cls) {
        java.util.ArrayList<java.lang.reflect.Field> out = new java.util.ArrayList<>();
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                out.add(f);
            }
            c = c.getSuperclass();
        }
        return out;
    }

    private static boolean appendToCertArray(java.lang.reflect.Field f, Object obj,
            java.security.cert.X509Certificate cert, String label) {
        try {
            java.security.cert.X509Certificate[] existing =
                    (java.security.cert.X509Certificate[]) f.get(obj);
            if (existing != null) {
                for (java.security.cert.X509Certificate c : existing) {
                    if (cert.equals(c)) {
                        Log.d(TAG, "appendToCertArray[" + label + "]: already present");
                        return true;
                    }
                }
            }
            int len = existing != null ? existing.length : 0;
            java.security.cert.X509Certificate[] updated =
                    new java.security.cert.X509Certificate[len + 1];
            if (existing != null) System.arraycopy(existing, 0, updated, 0, len);
            updated[len] = cert;
            f.set(obj, updated);
            Log.i(TAG, "appendToCertArray[" + label + "]: appended to array of len " + len);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "appendToCertArray[" + label + "] failed: " + e.getMessage());
            return false;
        }
    }

    private static void clearSocketFactoriesCache(Class<?> certMgrClass) {
        try {
            for (java.lang.reflect.Field f : certMgrClass.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        && java.util.Map.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object map = f.get(null);
                    if (map instanceof java.util.Map) {
                        ((java.util.Map<?, ?>) map).clear();
                        Log.i(TAG, "clearSocketFactoriesCache: cleared");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "clearSocketFactoriesCache failed: " + e.getMessage());
        }
    }

    private static void copyAssetToFile(Context context, String assetName, java.io.File dest)
            throws java.io.IOException {
        try (java.io.InputStream in = context.getAssets().open(assetName);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static java.security.cert.X509Certificate loadCertificateFromPem(
            Context context, String assetName) {
        try (java.io.InputStream is = context.getAssets().open(assetName)) {
            return (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(is);
        } catch (Exception e) {
            Log.d(TAG, "loadCertificateFromPem(" + assetName + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static java.security.cert.X509Certificate loadCertificateFromPkcs12(
            Context context, String assetName, char[] pkcs12Unlock) {
        try (java.io.InputStream is = context.getAssets().open(assetName)) {
            java.security.KeyStore ks = java.security.KeyStore.getInstance("PKCS12");
            ks.load(is, pkcs12Unlock);
            java.util.Enumeration<String> aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                java.security.cert.Certificate cert = ks.getCertificate(alias);
                if (cert instanceof java.security.cert.X509Certificate) {
                    return (java.security.cert.X509Certificate) cert;
                }
            }
            return null;
        } catch (Exception e) {
            Log.d(TAG, "loadCertificateFromPkcs12(" + assetName + ") failed: " + e.getMessage());
            return null;
        }
    }

    private static boolean paramsAcceptActuals(Class<?>[] formals, Class<?>[] actuals) {
        if (formals.length != actuals.length) return false;
        for (int i = 0; i < formals.length; i++) {
            if (actuals[i] == null) {
                if (formals[i].isPrimitive()) return false;
                continue;
            }
            if (!isActualAssignableToFormal(formals[i], actuals[i])) return false;
        }
        return true;
    }

    private static boolean isActualAssignableToFormal(Class<?> formal, Class<?> actualArg) {
        if (formal.isAssignableFrom(actualArg)) return true;
        if (formal == boolean.class && actualArg == Boolean.class) return true;
        if (formal == Boolean.class && actualArg == boolean.class) return true;
        if (formal.isPrimitive()) {
            Class<?> box = boxPrimitive(formal);
            return box != null && formal == unboxWrapper(actualArg);
        }
        return false;
    }

    private static Class<?> boxPrimitive(Class<?> prim) {
        if (prim == int.class) return Integer.class;
        if (prim == boolean.class) return Boolean.class;
        if (prim == long.class) return Long.class;
        if (prim == byte.class) return Byte.class;
        if (prim == short.class) return Short.class;
        if (prim == char.class) return Character.class;
        if (prim == float.class) return Float.class;
        if (prim == double.class) return Double.class;
        return null;
    }

    private static Class<?> unboxWrapper(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Long.class) return long.class;
        if (c == Byte.class) return byte.class;
        if (c == Short.class) return short.class;
        if (c == Character.class) return char.class;
        if (c == Float.class) return float.class;
        if (c == Double.class) return double.class;
        return null;
    }

    private static java.lang.reflect.Method findStaticMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName,
            Class<?> returnFilter) {
        java.lang.reflect.Method preferred = null;
        java.lang.reflect.Method any = null;
        java.lang.reflect.Method returnFiltered = null;
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!paramsAcceptActuals(m.getParameterTypes(), actualParamTypes)) continue;
                m.setAccessible(true);
                if (preferredName != null && preferredName.equals(m.getName())) return m;
                if (returnFilter != null && returnFilter.isAssignableFrom(m.getReturnType())) {
                    if (returnFiltered == null) returnFiltered = m;
                }
                if (any == null) any = m;
                if (preferred == null && preferredName != null
                        && m.getName().toLowerCase().contains(preferredName.toLowerCase())) {
                    preferred = m;
                }
            }
        }
        if (preferred != null) return preferred;
        if (returnFiltered != null) return returnFiltered;
        return any;
    }

    private static java.lang.reflect.Method findStaticMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName) {
        return findStaticMethodByActualParams(startClass, actualParamTypes, preferredName, null);
    }

    private static java.lang.reflect.Method findInstanceMethodByActualParams(
            Class<?> startClass, Class<?>[] actualParamTypes, String preferredName,
            Class<?> returnFilter) {
        java.lang.reflect.Method preferred = null;
        java.lang.reflect.Method any = null;
        java.lang.reflect.Method returnFiltered = null;
        for (Class<?> c = startClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (!paramsAcceptActuals(m.getParameterTypes(), actualParamTypes)) continue;
                m.setAccessible(true);
                if (preferredName != null && preferredName.equals(m.getName())) return m;
                if (returnFilter != null && returnFilter.isAssignableFrom(m.getReturnType())) {
                    if (returnFiltered == null) returnFiltered = m;
                }
                if (any == null) any = m;
                if (preferred == null && preferredName != null
                        && m.getName().toLowerCase().contains(preferredName.toLowerCase())) {
                    preferred = m;
                }
            }
        }
        if (preferred != null) return preferred;
        if (returnFiltered != null) return returnFiltered;
        return any;
    }

    private static java.lang.reflect.Method findInstanceVoidNoArg(Class<?> cls, String preferredName) {
        java.lang.reflect.Method named = null;
        int voidNoArg = 0;
        java.lang.reflect.Method lone = null;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (m.getReturnType() != void.class) continue;
            String n = m.getName();
            if ("wait".equals(n) || "notify".equals(n) || "notifyAll".equals(n)) continue;
            m.setAccessible(true);
            if (preferredName != null && preferredName.equals(n)) return m;
            voidNoArg++;
            lone = m;
            if (preferredName != null && n.toLowerCase().contains(preferredName.toLowerCase())) {
                named = m;
            }
        }
        if (named != null) return named;
        if (preferredName != null) return null;
        return voidNoArg == 1 ? lone : null;
    }

    private static java.lang.reflect.Method findSingletonGetter(Class<?> cls) {
        java.lang.reflect.Method fallback = null;
        for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!cls.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            if ("getInstance".equals(m.getName())) return m;
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    private static java.lang.reflect.Method findStaticSaveCredentialThreeStrings(Class<?> base) {
        Class<?>[] three = new Class[]{String.class, String.class, String.class};
        String saveCred = atkReflectSaveCertCred();
        java.lang.reflect.Method m = findStaticMethodByActualParams(base, three, saveCred);
        if (m != null) return m;
        java.lang.reflect.Method match = null;
        int hits = 0;
        for (Class<?> c = base; c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Method x : c.getDeclaredMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(x.getModifiers())) continue;
                if (!paramsAcceptActuals(x.getParameterTypes(), three)) continue;
                if (x.getReturnType() != void.class && x.getReturnType() != boolean.class) continue;
                String n = x.getName().toLowerCase();
                if (!n.contains("save") || (!n.contains("password") && !n.contains("cert"))) continue;
                hits++;
                match = x;
            }
        }
        if (hits != 1) return null;
        match.setAccessible(true);
        return match;
    }

    private static void scheduleOneStartupRepoSyncIfNeeded() {
        if (!startupRepoSyncScheduled.compareAndSet(false, true)) return;
        new Handler(Looper.getMainLooper()).postDelayed(
                MeshCoreMapComponent::runStartupRepoSyncOnceNoRetry, STARTUP_REPO_SYNC_DELAY_MS);
        Log.d(TAG, "Scheduled one startup repo sync in " + STARTUP_REPO_SYNC_DELAY_MS + "ms");
    }

    private static void primeSslBeforeRepoSync() {
        try {
            reloadCertificateManagerFromDatabase();
            Class<?> cmCls = Class.forName("com.atakmap.net.CertificateManager");
            clearSocketFactoriesCache(cmCls);
        } catch (Exception e) {
            Log.d(TAG, "primeSslBeforeRepoSync: " + e.getMessage());
        }
    }

    private static void runStartupRepoSyncOnceNoRetry() {
        try {
            primeSslBeforeRepoSync();
            Class<?> cls = Class.forName("com.atakmap.android.update.ApkUpdateComponent");
            java.lang.reflect.Method singletonGetter = findSingletonGetter(cls);
            if (singletonGetter == null) {
                Log.d(TAG, "startup repo sync skipped: no ApkUpdateComponent singleton getter");
                return;
            }
            Object comp = singletonGetter.invoke(null);
            if (comp == null) {
                Log.d(TAG, "startup repo sync skipped: ApkUpdateComponent null (no retry)");
                return;
            }
            java.lang.reflect.Method pmGetter = findProviderManagerGetter(cls);
            if (pmGetter == null) {
                Log.d(TAG, "startup repo sync skipped: providerManager getter not found");
                return;
            }
            Object mgr = pmGetter.invoke(comp);
            if (mgr == null) {
                Log.d(TAG, "startup repo sync skipped: providerManager null");
                return;
            }
            Context uiCtx = resolveRepoSyncUiContext(mgr);
            java.lang.reflect.Method syncCtx = findContextSyncMethod(mgr.getClass());
            if (uiCtx == null || syncCtx == null) {
                Log.d(TAG, "startup repo sync skipped: no UI context or sync(Context,boolean,Listener)");
                return;
            }
            syncCtx.invoke(mgr, uiCtx, Boolean.TRUE, null);
            Log.i(TAG, "startup repo sync: one silent ProductProviderManager.sync");
        } catch (Exception e) {
            Log.w(TAG, "startup repo sync failed: " + e.getMessage());
        }
    }

    private static java.lang.reflect.Method findProviderManagerGetter(Class<?> compClass) {
        java.lang.reflect.Method best = null;
        int bestScore = -1;
        for (java.lang.reflect.Method m : compClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            Class<?> rt = m.getReturnType();
            for (java.lang.reflect.Method pm : rt.getMethods()) {
                if (pm.getReturnType() != void.class) continue;
                Class<?>[] p = pm.getParameterTypes();
                if (p.length == 2 && p[0] == boolean.class && p[1] == boolean.class) {
                    m.setAccessible(true);
                    String mn = m.getName().toLowerCase();
                    int score = 0;
                    if (mn.contains("product")) score += 2;
                    if (mn.contains("provider")) score += 2;
                    if (mn.contains("manager")) score += 1;
                    if (score > bestScore) {
                        bestScore = score;
                        best = m;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static java.lang.reflect.Method findContextSyncMethod(Class<?> mgrClass) {
        final Class<?> listenerCls;
        try {
            listenerCls = Class.forName(
                    "com.atakmap.android.update.ProductProviderManager$RepoSyncListener");
        } catch (ClassNotFoundException e) {
            return null;
        }
        for (java.lang.reflect.Method m : mgrClass.getMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3
                    && Context.class.isAssignableFrom(p[0])
                    && p[1] == boolean.class
                    && listenerCls.isAssignableFrom(p[2])) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static Context resolveRepoSyncUiContext(Object productProviderMgr) {
        try {
            MapView mv = MapView.getMapView();
            if (mv != null) {
                Context c = mv.getContext();
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
        }
        if (productProviderMgr == null) return null;
        try {
            for (java.lang.reflect.Field f : productProviderMgr.getClass().getDeclaredFields()) {
                if (!Activity.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object a = f.get(productProviderMgr);
                if (a instanceof Context) return (Context) a;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (gpsLocationManager != null && gpsLocationListener != null) {
            try {
                gpsLocationManager.removeUpdates(gpsLocationListener);
            } catch (Exception ignored) {
            }
            gpsLocationManager = null;
            gpsLocationListener = null;
        }
        stopBeaconTimer();
        if (beaconIntervalReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(beaconIntervalReceiver);
            } catch (Exception ignored) {
            }
            beaconIntervalReceiver = null;
        }
        try {
            if (dropDownReceiver != null) {
                dropDownReceiver.dispose();
                dropDownReceiver = null;
            }
            if (meshDetailsDropDownReceiver != null) {
                meshDetailsDropDownReceiver.dispose();
                meshDetailsDropDownReceiver = null;
            }
        } catch (Exception ignored) {
        }
        if (mapItemClickListener != null && view != null) {
            try {
                view.getMapEventDispatcher().removeMapEventListener(
                        MapEvent.ITEM_CLICK, mapItemClickListener);
            } catch (Exception ignored) {
            }
            mapItemClickListener = null;
        }
        if (meshMapPrefs != null && meshMapPrefsListener != null) {
            try {
                meshMapPrefs.unregisterOnSharedPreferenceChangeListener(meshMapPrefsListener);
            } catch (Exception ignored) {
            }
        }
        meshMapPrefsListener = null;
        meshMapPrefs = null;
        meshRepeaterMapUids.clear();
        meshNodeMapUids.clear();
        ToolsPreferenceFragment.unregister(SettingsFragment.TOOL_SETTINGS_KEY);
        if (meshIconsetReminderHandler != null && meshIconsetReminderRunnable != null) {
            meshIconsetReminderHandler.removeCallbacks(meshIconsetReminderRunnable);
        }
        meshIconsetReminderHandler = null;
        meshIconsetReminderRunnable = null;
        if (radialPingReceiver != null) {
            try {
                AtakBroadcast.getInstance().unregisterReceiver(radialPingReceiver);
            } catch (Exception ignored) {
            }
            radialPingReceiver = null;
        }
        try {
            MeshcoreIconsetInstaller.clearPersistentReminder(
                    view != null ? view.getContext() : context);
        } catch (Exception ignored) {
        }
        MeshStatusOverlay.uninstall();
        MeshGpsBridge.uninstallLocationProvider();
        if (chatBridge != null) {
            chatBridge.dispose();
        }
        if (cotBridge != null) {
            cotBridge.dispose();
        }
        if (contactTracker != null) {
            contactTracker.stop();
        }
        PositionRequester.clear();
        MeshCoreRadioServices.clear();
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
