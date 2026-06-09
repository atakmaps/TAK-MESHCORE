package com.atakmaps.meshcore.plugin.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.gui.PanPreference;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.app.SettingsActivity;
import com.atakmaps.meshcore.plugin.protocol.NetSlotConfig;
import com.atakmaps.meshcore.plugin.protocol.MeshCoreRadioServices;

/**
 * Settings screen for the MeshCore plugin.
 *
 * Provides configuration for:
 * - Callsign
 * - Beacon interval
 * - Chat relay toggle
 * - CoT relay toggle
 * - Auto-reconnect toggle
 */
public class SettingsFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int COLOR_STD_BLUE = 0xFF1976D2;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    /** Key registered with {@code ToolsPreferenceFragment} in {@link com.atakmaps.meshcore.plugin.MeshCoreMapComponent}. */
    public static final String TOOL_SETTINGS_KEY = "meshcorePreference";

    public static final String PREF_AUTO_RECONNECT = "meshcore_auto_reconnect";
    public static final String PREF_ENCRYPTION_ENABLED = "meshcore_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "meshcore_encryption_passphrase";
    public static final String PREF_RETRY_INTERVAL_MIN = "meshcore_retry_interval_min";
    public static final String PREF_RETRY_MAX = "meshcore_retry_max";
    public static final String PREF_SA_RELAY_ENABLED = "meshcore_sa_relay_enabled";
    public static final String PREF_RF_TO_TAK_UPLINK_ENABLED = "meshcore_rf_to_tak_uplink_enabled";
    public static final String PREF_PING_REPLY_ENABLED = "meshcore_ping_reply_enabled";
    public static final String PREF_BEACON_INTERVAL = "meshcore_beacon_interval";
    /** National-only mesh periodic beacons — hidden until administrative unlock. */
    public static final String PREF_MESH_BEACON_ENABLED = "meshcore_mesh_beacon_enabled";

    public static final String KEY_UNLOCK_ADMIN = "meshcore_admin_access";
    public static final String KEY_CAT_ADMINISTRATION = "meshcore_cat_administration";
    public static final String KEY_ADMIN_LEADERSHIP_WARNING = "meshcore_admin_leadership_warning";
    public static final String KEY_DISTRIBUTE_NET_SLOTS = "meshcore_distribute_net_slots";
    public static final String KEY_ADMIN_CURRENT_SLOT_STATUS = "meshcore_admin_current_slot_status";

    /** Injected after inflate — some ATAK builds omit custom Pan* prefs from XML. */
    public static final String KEY_BLUETOOTH_DEVICES = "meshcore_bluetooth_devices";
    public static final String KEY_CAT_RADIO = "meshcore_cat_radio";

    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final String DEFAULT_BEACON_INTERVAL = "60";
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";

    private static Context staticPluginContext;

    private PreferenceCategory adminCategory;
    private boolean adminCategoryRemoved = false;

    /**
     * Zero-arg constructor required by Android fragment system.
     * Only valid after the 1-arg constructor has been called once.
     */
    public SettingsFragment() {
        super(staticPluginContext, getResourceId());
    }

    public SettingsFragment(final Context pluginContext) {
        super(pluginContext, getResourceId());
        staticPluginContext = pluginContext;
    }

    private static int getResourceId() {
        if (staticPluginContext == null) return 0;
        return staticPluginContext.getResources().getIdentifier(
                "preferences", "xml", staticPluginContext.getPackageName());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = getContext();
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        if (ctx != null) {
            NetSlotConfig.ensureDefaults(ctx);
        }
        ensureBluetoothDevicesPreference();
        wireAdministrationPreferences();
        wireAdminAccessPreference();
    }

    /**
     * Removes the legacy "Bluetooth Devices" (favorites manager) preference if a saved
     * preference XML or older install still surfaces it. Favorites have been retired.
     */
    private void ensureBluetoothDevicesPreference() {
        try {
            Preference existing = findPreference(KEY_BLUETOOTH_DEVICES);
            if (existing == null) return;
            PreferenceGroup parent = (PreferenceGroup) findPreference("meshcore_cat_bluetooth");
            if (parent == null) parent = (PreferenceGroup) findPreference(KEY_CAT_RADIO);
            if (parent != null) {
                parent.removePreference(existing);
            } else {
                android.preference.PreferenceScreen root = getPreferenceScreen();
                if (root != null) root.removePreference(existing);
            }
        } catch (Exception e) {
            android.util.Log.w("MeshCore.Settings", "Could not remove legacy Bluetooth Devices pref", e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        styleAdminLeadershipWarning();
        applyAdminCategoryVisibility();
        updateAdminControlsEnabled();
        updateSummaries();
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          String key) {
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            updateAdminControlsEnabled();
        }
        if (NetSlotConfig.PREF_SLOT_COUNT.equals(key)
                || NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            normalizeSlotPreferences(prefs);
        }
        updateSummaries();
    }

    private void wireAdminAccessPreference() {
        adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        Preference unlock = findPreference(KEY_UNLOCK_ADMIN);
        if (unlock != null) {
            unlock.setOnPreferenceClickListener(preference -> {
                Context ctx = getActivity() != null ? getActivity() : getContext();
                if (ctx == null && MapView.getMapView() != null) {
                    ctx = MapView.getMapView().getContext();
                }
                if (ctx == null) {
                    return true;
                }
                if (AdminAccessGate.isUnlocked(ctx)) {
                    Toast.makeText(ctx, "Administrative Settings already unlocked",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                promptAdministrativeUnlock(ctx, () -> applyAdminCategoryVisibility());
                return true;
            });
        }
        applyAdminCategoryVisibility();
    }

    private void applyAdminCategoryVisibility() {
        Context ctx = getActivity() != null ? getActivity() : getContext();
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        boolean unlocked = AdminAccessGate.isUnlocked(ctx);
        Preference unlock = findPreference(KEY_UNLOCK_ADMIN);
        if (unlock != null) {
            unlock.setSummary(unlocked
                    ? "Unlocked — leadership controls below"
                    : "Tap to enter password");
        }
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (adminCategory == null || screen == null) {
            return;
        }
        if (!unlocked && adminCategory.getParent() != null) {
            screen.removePreference(adminCategory);
            adminCategoryRemoved = true;
        } else if (unlocked && adminCategoryRemoved) {
            screen.addPreference(adminCategory);
            adminCategoryRemoved = false;
            updateAdminControlsEnabled();
            updateSummaries();
        }
    }

    public static void promptAdministrativeUnlock(Context ctx, Runnable onUnlocked) {
        if (ctx == null) {
            return;
        }
        if (!AdminAccessGate.isConfigured()) {
            Toast.makeText(ctx,
                    "Administrative password not configured in this build",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final EditText input = new EditText(ctx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(ctx)
                .setTitle("Administrative Settings")
                .setMessage("Enter password to unlock hidden settings.")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    if (AdminAccessGate.unlock(ctx, input.getText().toString())) {
                        Toast.makeText(ctx, "Administrative Settings unlocked",
                                Toast.LENGTH_SHORT).show();
                        if (onUnlocked != null) {
                            onUnlocked.run();
                        }
                    } else {
                        Toast.makeText(ctx, "Incorrect password", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void wireAdministrationPreferences() {
        Preference distribute = findPreference(KEY_DISTRIBUTE_NET_SLOTS);
        if (distribute != null) {
            distribute.setOnPreferenceClickListener(preference -> {
                Context ctx = getActivity() != null ? getActivity() : getContext();
                if (ctx == null && MapView.getMapView() != null) {
                    ctx = MapView.getMapView().getContext();
                }
                if (ctx == null) {
                    return true;
                }
                if (!AdminAccessGate.isUnlocked(ctx)) {
                    promptAdministrativeUnlock(ctx, () -> applyAdminCategoryVisibility());
                    return true;
                }
                if (!getPrefs(ctx).getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)) {
                    Toast.makeText(ctx, "Enable administrative settings first",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                SharedPreferences prefs = getPrefs(ctx);
                normalizeSlotPreferences(prefs);
                int slots = NetSlotConfig.getSlotCount(ctx);
                float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
                NetSlotConfig.saveLocalSlotSettings(ctx, slots, slotSec);
                if (!MeshCoreRadioServices.isConnected()) {
                    Toast.makeText(ctx, "Connect to radio before distributing slot settings",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
                if (MeshCoreRadioServices.distributeNetSlotConfig(ctx)) {
                    Toast.makeText(ctx,
                            "Slot config sent (" + slots + " slots, "
                                    + slotSec + " s)",
                            Toast.LENGTH_LONG).show();
                    updateSummaries();
                } else {
                    Toast.makeText(ctx, "Failed to send slot config over radio",
                            Toast.LENGTH_LONG).show();
                }
                return true;
            });
        }
        updateAdminControlsEnabled();
    }

    private void styleAdminLeadershipWarning() {
        Preference warning = findPreference(KEY_ADMIN_LEADERSHIP_WARNING);
        if (warning != null) {
            warning.setTitle("For Team Leadership ONLY — Do not use unless directed by higher");
        }
    }

    private void updateAdminControlsEnabled() {
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        boolean adminOn = prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        setPreferenceEnabled(NetSlotConfig.PREF_SLOT_COUNT, adminOn);
        setPreferenceEnabled(NetSlotConfig.PREF_SLOT_TIME_SEC, adminOn);
        setPreferenceEnabled(KEY_DISTRIBUTE_NET_SLOTS, adminOn);
    }

    private void setPreferenceEnabled(String key, boolean enabled) {
        Preference p = findPreference(key);
        if (p != null) {
            p.setEnabled(enabled);
        }
    }

    private void normalizeSlotPreferences(SharedPreferences prefs) {
        int slots = NetSlotConfig.getSlotCount(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : staticPluginContext);
        float sec = NetSlotConfig.getSlotTimeSec(
                MapView.getMapView() != null
                        ? MapView.getMapView().getContext()
                        : staticPluginContext);
        prefs.edit()
                .putString(NetSlotConfig.PREF_SLOT_COUNT, String.valueOf(slots))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC, String.valueOf(sec))
                .apply();
    }

    private void updateSummaries() {
        SharedPreferences prefs =
                getPreferenceManager().getSharedPreferences();

        Preference beaconPref = findPreference(PREF_BEACON_INTERVAL);
        if (beaconPref != null) {
            int interval = getBeaconIntervalSec(
                    getActivity() != null ? getActivity() : staticPluginContext);
            beaconPref.setSummary("Every " + interval + " seconds (when Mesh Beacon enabled)");
        }

        Preference retryIntervalPref = findPreference(PREF_RETRY_INTERVAL_MIN);
        if (retryIntervalPref != null) {
            String mins = prefs.getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
            retryIntervalPref.setSummary("Retry after " + mins + " minute(s) with no ACK");
        }

        Preference retryMaxPref = findPreference(PREF_RETRY_MAX);
        if (retryMaxPref != null) {
            String max = prefs.getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
            retryMaxPref.setSummary("Up to " + max + " retransmit attempt(s) before failure");
        }

        Preference saRelayPref = findPreference(PREF_SA_RELAY_ENABLED);
        if (saRelayPref != null) {
            boolean on = prefs.getBoolean(PREF_SA_RELAY_ENABLED, false);
            saRelayPref.setSummary(on
                    ? "On — network PLI/markers/routes relayed over radio when connected"
                    : "Off");
        }

        Preference pingReplyPref = findPreference(PREF_PING_REPLY_ENABLED);
        if (pingReplyPref != null) {
            boolean on = prefs.getBoolean(PREF_PING_REPLY_ENABLED, true);
            pingReplyPref.setSummary(on
                    ? "On — reply to incoming pings with your position"
                    : "Off");
        }

        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx != null) {
            int slots = NetSlotConfig.getSlotCount(ctx);
            float slotSec = NetSlotConfig.getSlotTimeSec(ctx);
            Preference slotCountPref = findPreference(NetSlotConfig.PREF_SLOT_COUNT);
            if (slotCountPref != null) {
                slotCountPref.setSummary("Ping-reply slots: " + slots
                        + " (slot index from callsign hash)");
            }
            Preference slotTimePref = findPreference(NetSlotConfig.PREF_SLOT_TIME_SEC);
            if (slotTimePref != null) {
                slotTimePref.setSummary(String.format(
                        java.util.Locale.US,
                        "Seconds between slot starts: %.1f s", slotSec));
            }
            Preference currentStatus = findPreference(KEY_ADMIN_CURRENT_SLOT_STATUS);
            if (currentStatus != null) {
                String status = String.format(java.util.Locale.US,
                        "Slot count: %d — Slot time: %.1f s", slots, slotSec);
                String issuer = prefs.getString(NetSlotConfig.PREF_LAST_NET_SLOT_ISSUER, "");
                int seq = prefs.getInt(NetSlotConfig.PREF_NET_SLOT_CONFIG_SEQ, 0);
                if (seq > 0 && issuer != null && !issuer.isEmpty()) {
                    status += "\nLast net update from " + issuer;
                }
                currentStatus.setSummary(status);
            }
        }
    }

    @Override
    public String getSubTitle() {
        return "MeshCore Settings";
    }

    /**
     * Convenience: Get a preference value from any context.
     */
    /**
     * Get the ATAK-process SharedPreferences (uses ATAK context, not plugin context).
     */
    private static android.content.SharedPreferences getPrefs(Context context) {
        // Plugin context can't write to its own shared_prefs dir because it
        // runs inside ATAK's process. Always use ATAK's context.
        Context ctx = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        return PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static String getCallsign(Context context) {
        try {
            com.atakmap.android.maps.MapView mv = com.atakmap.android.maps.MapView.getMapView();
            if (mv != null && mv.getSelfMarker() != null) {
                return mv.getSelfMarker().getMetaString("callsign", "UNKNOWN");
            }
        } catch (Exception e) {
        }
        return "UNKNOWN";
    }

    public static int getBeaconIntervalSec(Context context) {
        String val = getPrefs(context)
                .getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    public static boolean isMeshBeaconEnabled(Context context) {
        if (context == null) {
            return false;
        }
        if (!AdminAccessGate.isUnlocked(context)) {
            return false;
        }
        return getPrefs(context).getBoolean(PREF_MESH_BEACON_ENABLED, false);
    }

    public static void setMeshBeaconEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        getPrefs(context).edit().putBoolean(PREF_MESH_BEACON_ENABLED, enabled).apply();
    }

    public static long getRetryIntervalMs(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        try {
            return Long.parseLong(val) * 60_000L;
        } catch (NumberFormatException e) {
            return 2 * 60_000L;
        }
    }

    public static int getMaxChatRetries(Context context) {
        String val = getPrefs(context)
                .getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        try {
            return Math.max(1, Integer.parseInt(val));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    public static boolean isAutoReconnectEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT);
    }

    /**
     * Use ATAK's team color ("locationTeam") rather than a plugin-managed setting, so
     * radio contacts match the operator's configured team consistently.
     */
    public static String getAtakTeamColor(Context context) {
        try {
            String team = ChatManagerMapComponent.getTeamName();
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        try {
            com.atakmap.android.preference.AtakPreferences prefs =
                    com.atakmap.android.preference.AtakPreferences.getInstance(
                            com.atakmap.android.maps.MapView.getMapView() != null
                                    ? com.atakmap.android.maps.MapView.getMapView().getContext()
                                    : context);
            String team = prefs.get("locationTeam", "Cyan");
            if (team != null && !team.trim().isEmpty()) return team.trim();
        } catch (Exception ignored) {
        }
        return "Cyan";
    }

    public static boolean isSaRelayEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_SA_RELAY_ENABLED, false);
    }

    public static boolean isPingReplyEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_PING_REPLY_ENABLED, true);
    }

    public static boolean isRfToTakUplinkEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false);
    }

    public static boolean isEncryptionEnabled(Context context) {
        return getPrefs(context)
                .getBoolean(PREF_ENCRYPTION_ENABLED, false);
    }

    public static String getEncryptionPassphrase(Context context) {
        return getPrefs(context)
                .getString(PREF_ENCRYPTION_PASSPHRASE, "");
    }

    /**
     * Opens ATAK Tool Preferences for this plugin (Settings → Tool Preferences → MeshCore).
     */
    public static void openToolPreferences(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, null, context);
    }

    private static void launchPluginSettings(String toolKey, String prefKey, Context context) {
        try {
            SettingsActivity.start(toolKey, prefKey);
        } catch (Exception e) {
            android.util.Log.w("MeshCore.Settings", "launchPluginSettings failed: " + e.getMessage());
            if (context != null) {
                try {
                    android.content.Intent intent = new android.content.Intent(
                            "com.atakmap.app.ADVANCED_SETTINGS");
                    intent.putExtra("toolkey", toolKey);
                    if (prefKey != null) {
                        intent.putExtra("prefkey", prefKey);
                    }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    AtakBroadcast.getInstance().sendBroadcast(intent);
                } catch (Exception e2) {
                    Toast.makeText(context,
                            "Open Settings → Tool Preferences → MeshCore Settings",
                            Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    /**
     * Administration block for ping-reply slot net config (Tools prefs or plugin dialog).
     */
    public static final class AdministrationUi {
        public Switch adminEnabled;
        public Switch meshBeaconEnabled;
        public EditText editSlotCount;
        public EditText editSlotTime;
        public Button btnDistribute;
        public TextView currentStatus;
        View[] gatedViews;
    }

    /** Adds Administration section views to a scrollable dialog layout. */
    public static AdministrationUi appendAdministrationSection(Context ctx, LinearLayout layout) {
        if (!AdminAccessGate.isUnlocked(ctx)) {
            return null;
        }
        AdministrationUi ui = new AdministrationUi();

        TextView header = new TextView(ctx);
        header.setText("\nAdministrative Settings");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        TextView warning = new TextView(ctx);
        warning.setText("For Team Leadership ONLY — Do not use unless directed by higher");
        warning.setTextColor(0xFFFFFFFF);
        warning.setTextSize(12);
        warning.setTypeface(Typeface.DEFAULT_BOLD);
        warning.setPadding(0, 8, 0, 8);
        layout.addView(warning);

        TextView meshBeaconWarning = new TextView(ctx);
        meshBeaconWarning.setText(
                "Mesh Beacon: activated by national only. Do not use unless directed by your team leader.");
        meshBeaconWarning.setTextColor(0xFFFFFFFF);
        meshBeaconWarning.setTextSize(12);
        meshBeaconWarning.setTypeface(Typeface.DEFAULT_BOLD);
        meshBeaconWarning.setPadding(0, 4, 0, 8);
        layout.addView(meshBeaconWarning);

        LinearLayout rowMeshBeacon = new LinearLayout(ctx);
        rowMeshBeacon.setOrientation(LinearLayout.HORIZONTAL);
        rowMeshBeacon.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelMeshBeacon = new TextView(ctx);
        labelMeshBeacon.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelMeshBeacon.setText("Enable Mesh Beacon");
        labelMeshBeacon.setTextColor(0xFFFFFFFF);
        labelMeshBeacon.setTextSize(13);
        rowMeshBeacon.addView(labelMeshBeacon);
        ui.meshBeaconEnabled = new Switch(ctx);
        ui.meshBeaconEnabled.setChecked(isMeshBeaconEnabled(ctx));
        rowMeshBeacon.addView(ui.meshBeaconEnabled);
        layout.addView(rowMeshBeacon);

        LinearLayout rowAdmin = new LinearLayout(ctx);
        rowAdmin.setOrientation(LinearLayout.HORIZONTAL);
        rowAdmin.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelAdmin = new TextView(ctx);
        labelAdmin.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelAdmin.setText("Enable administrative settings");
        labelAdmin.setTextColor(0xFFFFFFFF);
        labelAdmin.setTextSize(13);
        rowAdmin.addView(labelAdmin);
        ui.adminEnabled = new Switch(ctx);
        ui.adminEnabled.setChecked(getPrefs(ctx).getBoolean(
                NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        rowAdmin.addView(ui.adminEnabled);
        layout.addView(rowAdmin);

        TextView labelSlots = new TextView(ctx);
        labelSlots.setText("\nSlot count");
        labelSlots.setTextColor(0xFFAAAAAA);
        layout.addView(labelSlots);
        ui.editSlotCount = new EditText(ctx);
        ui.editSlotCount.setText(String.valueOf(NetSlotConfig.getSlotCount(ctx)));
        ui.editSlotCount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        ui.editSlotCount.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editSlotCount);

        TextView labelTime = new TextView(ctx);
        labelTime.setText("Slot time (seconds)");
        labelTime.setTextColor(0xFFAAAAAA);
        layout.addView(labelTime);
        ui.editSlotTime = new EditText(ctx);
        ui.editSlotTime.setText(String.valueOf(NetSlotConfig.getSlotTimeSec(ctx)));
        ui.editSlotTime.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        ui.editSlotTime.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editSlotTime);

        ui.btnDistribute = new Button(ctx);
        ui.btnDistribute.setText("Distribute to net");
        ui.btnDistribute.setTextColor(COLOR_WHITE);
        ui.btnDistribute.setBackgroundColor(COLOR_STD_BLUE);
        ui.btnDistribute.setOnClickListener(v -> distributeNetSlotsFromUi(ctx, ui));
        layout.addView(ui.btnDistribute);

        TextView distributeHint = new TextView(ctx);
        distributeHint.setText(
                "Ensure all stations are in radio range to receive slot assignments");
        distributeHint.setTextColor(0xFF888888);
        distributeHint.setTextSize(11);
        distributeHint.setPadding(0, 4, 0, 12);
        layout.addView(distributeHint);

        ui.currentStatus = new TextView(ctx);
        ui.currentStatus.setTextColor(0xFF00BCD4);
        ui.currentStatus.setTextSize(12);
        ui.currentStatus.setPadding(0, 4, 0, 8);
        layout.addView(ui.currentStatus);

        ui.gatedViews = new View[]{
                ui.editSlotCount, ui.editSlotTime, ui.btnDistribute
        };
        ui.adminEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                applyAdministrationGating(ui, isChecked));
        applyAdministrationGating(ui, ui.adminEnabled.isChecked());
        refreshAdministrationStatus(ctx, ui);
        return ui;
    }

    public static void saveAdministrationFromUi(Context ctx, AdministrationUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        SharedPreferences prefs = getPrefs(ctx);
        prefs.edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                        ui.adminEnabled.isChecked())
                .putBoolean(PREF_MESH_BEACON_ENABLED,
                        ui.meshBeaconEnabled != null && ui.meshBeaconEnabled.isChecked())
                .apply();
        try {
            int slots = Integer.parseInt(ui.editSlotCount.getText().toString().trim());
            float sec = Float.parseFloat(ui.editSlotTime.getText().toString().trim());
            NetSlotConfig.saveLocalSlotSettings(ctx, slots, sec);
        } catch (Exception ignored) {
            NetSlotConfig.saveLocalSlotSettings(ctx,
                    NetSlotConfig.DEFAULT_SLOT_COUNT,
                    NetSlotConfig.DEFAULT_SLOT_TIME_SEC);
        }
    }

    public static void refreshAdministrationStatus(Context ctx, AdministrationUi ui) {
        if (ctx == null || ui == null || ui.currentStatus == null) {
            return;
        }
        int slots = NetSlotConfig.getSlotCount(ctx);
        float sec = NetSlotConfig.getSlotTimeSec(ctx);
        String status = String.format(java.util.Locale.US,
                "Currently set on this device: %d slots, %.1f s slot time", slots, sec);
        SharedPreferences prefs = getPrefs(ctx);
        String issuer = prefs.getString(NetSlotConfig.PREF_LAST_NET_SLOT_ISSUER, "");
        int seq = prefs.getInt(NetSlotConfig.PREF_NET_SLOT_CONFIG_SEQ, 0);
        if (seq > 0 && issuer != null && !issuer.isEmpty()) {
            status += "\nLast net update from " + issuer;
        }
        ui.currentStatus.setText(status);
    }

    private static void applyAdministrationGating(AdministrationUi ui, boolean enabled) {
        if (ui.gatedViews == null) {
            return;
        }
        float alpha = enabled ? 1f : 0.38f;
        for (View v : ui.gatedViews) {
            if (v != null) {
                v.setEnabled(enabled);
                v.setAlpha(alpha);
            }
        }
    }

    private static void distributeNetSlotsFromUi(Context ctx, AdministrationUi ui) {
        if (!ui.adminEnabled.isChecked()) {
            Toast.makeText(ctx, "Enable administrative settings first", Toast.LENGTH_LONG).show();
            return;
        }
        saveAdministrationFromUi(ctx, ui);
        if (!MeshCoreRadioServices.isConnected()) {
            Toast.makeText(ctx, "Connect to radio before distributing slot settings",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int slots = NetSlotConfig.getSlotCount(ctx);
        float sec = NetSlotConfig.getSlotTimeSec(ctx);
        if (MeshCoreRadioServices.distributeNetSlotConfig(ctx)) {
            Toast.makeText(ctx,
                    "Slot config sent (" + slots + " slots, " + sec + " s)",
                    Toast.LENGTH_LONG).show();
            refreshAdministrationStatus(ctx, ui);
        } else {
            Toast.makeText(ctx, "Failed to send slot config over radio", Toast.LENGTH_LONG).show();
        }
    }

    private static int dp(Context ctx, int value) {
        if (ctx == null) {
            return value;
        }
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
