package com.atakmaps.meshcore.plugin;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
import com.atakmaps.meshcore.plugin.contacts.ContactTracker;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.mesh.MeshDetailsDropDownReceiver;
import com.atakmaps.meshcore.plugin.protocol.PacketRouter;
import com.atakmaps.meshcore.plugin.ax25.MeshcoreIconsetInstaller;
import com.atakmaps.meshcore.plugin.ui.MeshStatusOverlay;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * MeshCore-only map component.
 */
public class MeshCoreMapComponent extends DropDownMapComponent {

    private static final String TAG = "MeshCore";
    private static final long POST_CONNECT_BEACON_DELAY_MS = 30_000L;

    public static final String PLUGIN_PACKAGE = "com.atakmaps.meshcore.plugin";
    public static final String ACTION_BEACON_INTERVAL_CHANGED =
            "com.atakmaps.meshcore.plugin.BEACON_INTERVAL_CHANGED";
    private static final String PREF_MESH_SHOW_REPEATERS = "meshcore_mesh_show_repeaters";
    private static final String PREF_MESH_SHOW_NODES = "meshcore_mesh_show_nodes";
    private static final String PREF_MESH_REPEATER_CACHE = "meshcore_mesh_repeater_cache_v1";
    private static final String PREF_MESH_NODE_CACHE = "meshcore_mesh_node_cache_v1";
    private static final long MESH_REPEATER_TTL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long MESH_NODE_TTL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int MESH_NODE_CACHE_MAX = 100;

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
    private final Runnable postConnectBeaconRunnable = this::sendPostConnectBeacon;
    private final Runnable waitForPositionBeaconRunnable = this::waitForPositionThenScheduleBeacon;
    private Handler meshIconsetReminderHandler;
    private Runnable meshIconsetReminderRunnable;

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
        btConnectionManager.addMeshAdvertListener(advert -> {
            if (advert == null || mapView == null || !advert.hasValidPosition()) {
                return;
            }
            boolean isRepeater = advert.advertType == 0x02;
            String pub = advert.pubKeyHex != null ? advert.pubKeyHex : "";
            String uid = (isRepeater ? "MESHCORE-RPTR-" : "MESHCORE-NODE-") + pub;
            String advertName = (advert.name != null && !advert.name.trim().isEmpty())
                    ? advert.name.trim() : uid;
            // Always persist the node/repeater location regardless of display toggles.
            if (isRepeater) {
                persistRepeaterAdvert(advert, advertName);
            } else {
                persistNodeAdvert(advert, advertName);
            }
            boolean display = isRepeater
                    ? isMeshShowRepeatersPreferenceEnabled()
                    : isMeshNodeDisplayEnabled();
            if (cotBridge != null && display) {
                renderMeshAdvertMarker(pub, advertName, advert.latitude, advert.longitude, isRepeater);
            }
        });
        btConnectionManager.addListener(new BtConnectionManager.ConnectionListener() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                MeshStatusOverlay.setConnected(true);
                if (cotBridge != null) {
                    cotBridge.setBtManager(btConnectionManager);
                    cotBridge.refreshSendableMapItems();
                    if (mapView != null) {
                        mapView.post(() -> {
                            if (cotBridge != null) {
                                cotBridge.refreshSendableMapItems();
                            }
                        });
                    }
                }
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
        dropDownReceiver.setEncryptionManager(encryptionManager);
        packetRouter.setPacketCountListener(dropDownReceiver);

        AtakBroadcast.DocumentedIntentFilter filter =
                new AtakBroadcast.DocumentedIntentFilter();
        filter.addAction(MeshCoreDropDownReceiver.SHOW_PLUGIN);
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

        Log.i(TAG, "MeshCore plugin initialized");
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

    private void renderMeshAdvertMarker(String pub, String advertName,
                                        double lat, double lon, boolean isRepeater) {
        if (cotBridge == null) {
            return;
        }
        String uid = (isRepeater ? "MESHCORE-RPTR-" : "MESHCORE-NODE-")
                + (pub != null ? pub : "");
        String details = "Name: " + advertName + "\n"
                + "Pubkey: " + (pub != null ? pub : "") + "\n"
                + "Type: " + (isRepeater ? "Repeater" : "Node");
        cotBridge.upsertMeshAdvertMarker(uid, advertName, details, lat, lon, isRepeater);
        cotBridge.setMeshMarkerDetails(uid, details);
        cotBridge.promoteMeshContactMapItem(uid, advertName);
        if (isRepeater) {
            meshRepeaterMapUids.add(uid);
        } else {
            meshNodeMapUids.add(uid);
        }
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
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (key.isEmpty()) {
                    continue;
                }
                long firstSeenMs = o.optLong("firstSeenMs", 0L);
                if (firstSeenMs > 0L && (now - firstSeenMs) > MESH_REPEATER_TTL_MS) {
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
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String key = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                if (key.isEmpty()) {
                    continue;
                }
                long lastSeenMs = o.optLong("lastSeenMs", o.optLong("firstSeenMs", 0L));
                if (lastSeenMs > 0L && (now - lastSeenMs) > MESH_NODE_TTL_MS) {
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

            if (byKey.size() > MESH_NODE_CACHE_MAX) {
                String oldestKey = null;
                long oldestSeenMs = Long.MAX_VALUE;
                for (Map.Entry<String, JSONObject> e : byKey.entrySet()) {
                    JSONObject candidate = e.getValue();
                    long seenMs = candidate.optLong("lastSeenMs",
                            candidate.optLong("firstSeenMs", 0L));
                    if (seenMs <= 0L) {
                        seenMs = Long.MIN_VALUE;
                    }
                    if (seenMs < oldestSeenMs) {
                        oldestSeenMs = seenMs;
                        oldestKey = e.getKey();
                    }
                }
                if (oldestKey != null && byKey.size() > MESH_NODE_CACHE_MAX) {
                    byKey.remove(oldestKey);
                }
            }

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

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                String display = o.optString("display", "Mesh Repeater").trim();
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                long firstSeenMs = o.optLong("firstSeenMs", 0L);
                if (pubKey.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)
                        || firstSeenMs <= 0L || (now - firstSeenMs) > MESH_REPEATER_TTL_MS) {
                    continue;
                }
                kept.put(o);
                if (isMeshShowRepeatersPreferenceEnabled()) {
                    renderMeshAdvertMarker(pubKey, display, lat, lon, true);
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

            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String pubKey = o.optString("pubKeyHex", "").trim().toUpperCase(Locale.US);
                String display = o.optString("display", "Mesh Node").trim();
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                long firstSeenMs = o.optLong("firstSeenMs", 0L);
                long lastSeenMs = o.optLong("lastSeenMs", firstSeenMs);
                if (pubKey.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)
                        || lastSeenMs <= 0L || (now - lastSeenMs) > MESH_NODE_TTL_MS) {
                    continue;
                }
                kept.put(o);
                if (isMeshNodeDisplayEnabled()) {
                    renderMeshAdvertMarker(pubKey, display, lat, lon, false);
                }
            }
            while (kept.length() > MESH_NODE_CACHE_MAX) {
                int oldestIdx = -1;
                long oldestSeenMs = Long.MAX_VALUE;
                for (int i = 0; i < kept.length(); i++) {
                    JSONObject o = kept.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    long seenMs = o.optLong("lastSeenMs", o.optLong("firstSeenMs", 0L));
                    if (seenMs < oldestSeenMs) {
                        oldestSeenMs = seenMs;
                        oldestIdx = i;
                    }
                }
                if (oldestIdx < 0) {
                    break;
                }
                JSONArray trimmed = new JSONArray();
                for (int i = 0; i < kept.length(); i++) {
                    if (i == oldestIdx) {
                        continue;
                    }
                    JSONObject o = kept.optJSONObject(i);
                    if (o != null) {
                        trimmed.put(o);
                    }
                }
                kept = trimmed;
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

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
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
        cancelPostConnectBeacon();
        if (meshIconsetReminderHandler != null && meshIconsetReminderRunnable != null) {
            meshIconsetReminderHandler.removeCallbacks(meshIconsetReminderRunnable);
        }
        meshIconsetReminderHandler = null;
        meshIconsetReminderRunnable = null;
        try {
            MeshcoreIconsetInstaller.clearPersistentReminder(
                    view != null ? view.getContext() : context);
        } catch (Exception ignored) {
        }
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
