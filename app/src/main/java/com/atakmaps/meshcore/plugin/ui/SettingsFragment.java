package com.atakmaps.meshcore.plugin.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
    public static final String TOOL_SETTINGS_KEY = "uvproPreference";

    public static final String PREF_BEACON_INTERVAL = "uvpro_beacon_interval";
    public static final String PREF_AUTO_RECONNECT = "uvpro_auto_reconnect";
    public static final String PREF_ENCRYPTION_ENABLED = "uvpro_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "uvpro_encryption_passphrase";
    public static final String PREF_RETRY_INTERVAL_MIN = "uvpro_retry_interval_min";
    public static final String PREF_RETRY_MAX = "uvpro_retry_max";
    public static final String PREF_SA_RELAY_ENABLED = "uvpro_sa_relay_enabled";
    public static final String PREF_RF_TO_TAK_UPLINK_ENABLED = "uvpro_rf_to_tak_uplink_enabled";
    public static final String PREF_PING_REPLY_ENABLED = "uvpro_ping_reply_enabled";

    public static final String PREF_APRS_CALLSIGN = "uvpro_aprs_callsign";
    public static final String PREF_APRS_SSID = "uvpro_aprs_ssid";
    public static final String PREF_APRS_SYMBOL_TABLE = "uvpro_aprs_symbol_table";
    public static final String PREF_APRS_SYMBOL_CODE = "uvpro_aprs_symbol_code";
    public static final String PREF_APRS_ICON_SELECTED = "uvpro_aprs_icon_selected";
    public static final String PREF_APRS_MESSAGE = "uvpro_aprs_message";
    public static final String PREF_APRS_TX_ARMED = "uvpro_aprs_tx_armed";
    public static final String PREF_APRS_DISABLE_ATAK_TRAFFIC = "uvpro_aprs_disable_atak_traffic";

    public static final String KEY_CAT_APRS = "uvpro_cat_aprs";
    public static final String KEY_APRS_ICON = "uvpro_aprs_icon";

    public static final String KEY_CAT_ADMINISTRATION = "uvpro_cat_administration";
    public static final String KEY_ADMIN_LEADERSHIP_WARNING = "uvpro_admin_leadership_warning";
    public static final String KEY_DISTRIBUTE_NET_SLOTS = "uvpro_distribute_net_slots";
    public static final String KEY_ADMIN_CURRENT_SLOT_STATUS = "uvpro_admin_current_slot_status";

    /** Injected after inflate — some ATAK builds omit custom Pan* prefs from XML. */
    public static final String KEY_BLUETOOTH_DEVICES = "uvpro_bluetooth_devices";
    public static final String KEY_CAT_RADIO = "uvpro_cat_radio";

    public static final String DEFAULT_BEACON_INTERVAL = "300";
    public static final boolean DEFAULT_AUTO_RECONNECT = true;
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";

    private static Context staticPluginContext;

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
        wireAprsPreferences();
        wireAdministrationPreferences();
    }

    /**
     * Ensures "Bluetooth Devices" appears under Radio Settings. Preference is added
     * programmatically so it survives ATAK preference-inflation quirks.
     */
    private void ensureBluetoothDevicesPreference() {
        android.util.Log.d("MeshCore.Settings", "ensureBluetoothDevicesPreference called");
        Preference existing = findPreference(KEY_BLUETOOTH_DEVICES);
        if (existing != null) {
            android.util.Log.d("MeshCore.Settings", "bluetooth pref already exists, wiring click");
            wireBluetoothDevicesClick(existing);
            return;
        }

        // Try the radio category first, fall back to the bluetooth category
        PreferenceCategory radio = (PreferenceCategory) findPreference(KEY_CAT_RADIO);
        android.util.Log.d("MeshCore.Settings", "uvpro_cat_radio lookup: " + radio);
        if (radio == null) {
            radio = (PreferenceCategory) findPreference("uvpro_cat_bluetooth");
            android.util.Log.d("MeshCore.Settings", "uvpro_cat_bluetooth fallback: " + radio);
        }
        if (radio == null) {
            // Last resort: add directly to the root preference screen
            android.preference.PreferenceScreen root = getPreferenceScreen();
            android.util.Log.d("MeshCore.Settings", "preferenceScreen: " + root
                    + (root != null ? ", count=" + root.getPreferenceCount() : ""));
            if (root == null) return;
            Context ctx = getContext();
            if (ctx == null) ctx = staticPluginContext;
            if (ctx == null) return;
            try {
                PanPreference p = new PanPreference(ctx);
                p.setKey(KEY_BLUETOOTH_DEVICES);
                p.setTitle("Bluetooth Devices");
                p.setSummary("Radios you have connected — rename, favorite, delete");
                p.setPersistent(false);
                p.setSelectable(true);
                root.addPreference(p);
                wireBluetoothDevicesClick(p);
                android.util.Log.d("MeshCore.Settings", "added bluetooth pref to root screen");
            } catch (Exception e) {
                android.util.Log.e("MeshCore.Settings", "Could not add bluetooth pref to root", e);
            }
            return;
        }

        Context ctx = getContext();
        if (ctx == null) ctx = staticPluginContext;
        if (ctx == null) {
            android.util.Log.e("MeshCore.Settings", "context is null, cannot create PanPreference");
            return;
        }
        try {
            PanPreference p = new PanPreference(ctx);
            p.setKey(KEY_BLUETOOTH_DEVICES);
            p.setTitle("Bluetooth Devices");
            p.setSummary("Radios you have connected — rename, favorite, delete");
            p.setPersistent(false);
            p.setSelectable(true);
            p.setOrder(-1000);
            radio.addPreference(p);
            wireBluetoothDevicesClick(p);
            android.util.Log.d("MeshCore.Settings", "added bluetooth pref to category: " + radio.getKey());
        } catch (Exception e) {
            android.util.Log.e("MeshCore.Settings",
                    "Could not add Bluetooth Devices preference", e);
        }
    }

    private void wireBluetoothDevicesClick(Preference bt) {
        bt.setOnPreferenceClickListener(preference -> {
            Context c = getActivity() != null ? getActivity() : getContext();
            try {
                if (c == null && MapView.getMapView() != null) {
                    c = MapView.getMapView().getContext();
                }
            } catch (Exception ignored) {
            }
            if (c != null) {
                BluetoothDevicesManagement.show(c, null);
            }
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        styleAdminLeadershipWarning();
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

    private void wireAprsPreferences() {
        Preference iconPref = findPreference(KEY_APRS_ICON);
        if (iconPref != null) {
            updateAprsIconSummary(iconPref);
            iconPref.setOnPreferenceClickListener(p -> {
                Context ctx = getContext();
                if (ctx == null) {
                    ctx = staticPluginContext;
                }
                if (ctx != null) {
                    com.atakmaps.meshcore.plugin.aprs.AprsIconPickerDialog.show(
                            ctx, staticPluginContext,
                            () -> updateAprsIconSummary(iconPref));
                }
                return true;
            });
        }
    }

    private void updateAprsIconSummary(Preference iconPref) {
        Context ctx = getContext();
        if (ctx == null) {
            ctx = staticPluginContext;
        }
        if (ctx == null || iconPref == null) {
            return;
        }
        if (!isAprsIconSelected(ctx)) {
            iconPref.setSummary("(not set) — tap to choose");
            return;
        }
        char t = getAprsSymbolTable(ctx);
        char c = getAprsSymbolCode(ctx);
        iconPref.setSummary(com.atakmaps.meshcore.plugin.aprs.AprsSymbolCatalog.labelFor(t, c)
                + " (" + t + c + ")");
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
            String interval = prefs.getString(
                    PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
            beaconPref.setSummary("Every " + interval + " seconds");
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

        Preference aprsIconPref = findPreference(KEY_APRS_ICON);
        if (aprsIconPref != null) {
            updateAprsIconSummary(aprsIconPref);
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

    public static String getAprsCallsign(Context context) {
        String cs = getPrefs(context).getString(PREF_APRS_CALLSIGN, "");
        if (cs == null) {
            return "";
        }
        String out = cs.trim().toUpperCase(java.util.Locale.US);
        // Accept "CALL-SSID" input in FCC field by normalizing to base callsign.
        int dash = out.indexOf('-');
        if (dash > 0) {
            out = out.substring(0, dash);
        }
        return out;
    }

    public static int getAprsSsid(Context context) {
        String val = getPrefs(context).getString(PREF_APRS_SSID, "9");
        try {
            int ssid = Integer.parseInt(val);
            if (ssid < 0) {
                return 0;
            }
            if (ssid > 15) {
                return 15;
            }
            return ssid;
        } catch (NumberFormatException e) {
            return 9;
        }
    }

    public static boolean isAprsIconSelected(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_ICON_SELECTED, false);
    }

    public static char getAprsSymbolTable(Context context) {
        String s = getPrefs(context).getString(PREF_APRS_SYMBOL_TABLE, "/");
        if (s == null || s.isEmpty()) {
            return '/';
        }
        return s.charAt(0);
    }

    public static char getAprsSymbolCode(Context context) {
        String s = getPrefs(context).getString(PREF_APRS_SYMBOL_CODE, ">");
        if (s == null || s.isEmpty()) {
            return '>';
        }
        return s.charAt(0);
    }

    public static String getAprsMessage(Context context) {
        String m = getPrefs(context).getString(PREF_APRS_MESSAGE, "");
        return m != null ? m : "";
    }

    public static boolean isAprsTxArmed(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_TX_ARMED, false);
    }

    public static void setAprsTxArmed(Context context, boolean armed) {
        getPrefs(context).edit().putBoolean(PREF_APRS_TX_ARMED, armed).apply();
    }

    public static boolean isAprsDisableAtakTraffic(Context context) {
        return getPrefs(context).getBoolean(PREF_APRS_DISABLE_ATAK_TRAFFIC, false);
    }

    public static void setAprsDisableAtakTraffic(Context context, boolean disabled) {
        getPrefs(context).edit().putBoolean(PREF_APRS_DISABLE_ATAK_TRAFFIC, disabled).apply();
    }

    /** Ham base call: 1 letter + digit + 1–3 letters, or 2 letters + digit + 1–3 letters. */
    public static boolean isValidAprsCallsign(String baseCall) {
        if (baseCall == null) {
            return false;
        }
        String c = baseCall.trim().toUpperCase(java.util.Locale.US);
        return c.matches("^[A-Z][0-9][A-Z]{1,3}$")
                || c.matches("^[A-Z]{2}[0-9][A-Z]{1,3}$");
    }

    public static String formatAprsDisplayCall(Context context) {
        String base = getAprsCallsign(context);
        if (!isValidAprsCallsign(base)) {
            return "(not set)";
        }
        int ssid = getAprsSsid(context);
        return ssid > 0 ? base + "-" + ssid : base;
    }

    /**
     * Opens ATAK Tool Preferences for this plugin (Settings → Tool Preferences → MeshCore).
     */
    public static void openToolPreferences(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, null, context);
    }

    /**
     * Opens plugin settings scrolled to the APRS category.
     */
    public static void openAprsSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_APRS, context);
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
     * APRS outbound fields for the in-panel Plugin Settings dialog.
     */
    public static final class AprsSettingsUi {
        public EditText editCallsign;
        public Spinner spinnerSsid;
        public ImageView iconPreview;
        public TextView iconNotSet;
        public EditText editMessage;
        public String[] ssidValues;
    }

    /**
     * Adds APRS section to the Plugin Settings dialog (same window as Actions → Plugin Settings).
     */
    public static AprsSettingsUi appendAprsSettingsSection(Context mapCtx, Context pluginCtx,
                                                           LinearLayout layout) {
        AprsSettingsUi ui = new AprsSettingsUi();
        if (mapCtx == null || layout == null) {
            return ui;
        }

        TextView header = new TextView(mapCtx);
        header.setText("\nAPRS");
        header.setTextColor(0xFF00BCD4);
        header.setTextSize(14);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(header);

        TextView labelCall = new TextView(mapCtx);
        labelCall.setText("FCC Call Sign");
        labelCall.setTextColor(0xFFAAAAAA);
        layout.addView(labelCall);
        ui.editCallsign = new EditText(mapCtx);
        ui.editCallsign.setText(getAprsCallsign(mapCtx));
        ui.editCallsign.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        ui.editCallsign.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editCallsign);

        TextView labelSsid = new TextView(mapCtx);
        labelSsid.setText("\nAPRS suffix (SSID)");
        labelSsid.setTextColor(0xFFAAAAAA);
        layout.addView(labelSsid);
        ui.spinnerSsid = new Spinner(mapCtx);
        if (pluginCtx != null) {
            android.content.res.Resources res = pluginCtx.getResources();
            String pkg = pluginCtx.getPackageName();
            int labelsId = res.getIdentifier("aprs_ssid_labels", "array", pkg);
            int valuesId = res.getIdentifier("aprs_ssid_values", "array", pkg);
            if (labelsId != 0 && valuesId != 0) {
                String[] labels = res.getStringArray(labelsId);
                ui.ssidValues = res.getStringArray(valuesId);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(mapCtx,
                        android.R.layout.simple_spinner_item, labels) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        View v = super.getView(position, convertView, parent);
                        if (v instanceof TextView) {
                            TextView tv = (TextView) v;
                            tv.setTextColor(COLOR_WHITE);
                            tv.setBackgroundColor(COLOR_STD_BLUE);
                            tv.setPadding(dp(mapCtx, 10), dp(mapCtx, 8),
                                    dp(mapCtx, 10), dp(mapCtx, 8));
                        }
                        return v;
                    }

                    @Override
                    public View getDropDownView(int position, View convertView, ViewGroup parent) {
                        View v = super.getDropDownView(position, convertView, parent);
                        if (v instanceof TextView) {
                            TextView tv = (TextView) v;
                            tv.setTextColor(COLOR_WHITE);
                            tv.setBackgroundColor(COLOR_STD_BLUE);
                            tv.setPadding(dp(mapCtx, 12), dp(mapCtx, 10),
                                    dp(mapCtx, 12), dp(mapCtx, 10));
                        }
                        return v;
                    }
                };
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                ui.spinnerSsid.setAdapter(adapter);
                ui.spinnerSsid.setBackgroundColor(COLOR_STD_BLUE);
                int ssid = getAprsSsid(mapCtx);
                if (ssid >= 0 && ssid < labels.length) {
                    ui.spinnerSsid.setSelection(ssid);
                }
            }
        }
        layout.addView(ui.spinnerSsid);

        TextView labelIcon = new TextView(mapCtx);
        labelIcon.setText("\nAPRS icon");
        labelIcon.setTextColor(0xFFAAAAAA);
        layout.addView(labelIcon);

        LinearLayout iconRow = new LinearLayout(mapCtx);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setGravity(Gravity.CENTER_VERTICAL);
        ui.iconPreview = new ImageView(mapCtx);
        int iconPx = (int) (40 * mapCtx.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconPx, iconPx);
        iconLp.setMargins(0, 0, 16, 0);
        ui.iconPreview.setLayoutParams(iconLp);
        ui.iconPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconRow.addView(ui.iconPreview);
        ui.iconNotSet = new TextView(mapCtx);
        ui.iconNotSet.setText("(not set)");
        ui.iconNotSet.setTextColor(0xFF888888);
        ui.iconNotSet.setTextSize(12);
        iconRow.addView(ui.iconNotSet);
        layout.addView(iconRow);

        Button btnPickIcon = new Button(mapCtx);
        btnPickIcon.setText("Choose APRS Icon");
        btnPickIcon.setTextColor(COLOR_WHITE);
        btnPickIcon.setBackgroundColor(COLOR_STD_BLUE);
        btnPickIcon.setOnClickListener(v ->
                com.atakmaps.meshcore.plugin.aprs.AprsIconPickerDialog.show(
                        mapCtx, pluginCtx, () ->
                        refreshAprsIconPreviewInDialog(mapCtx, pluginCtx, ui)));
        layout.addView(btnPickIcon);
        refreshAprsIconPreviewInDialog(mapCtx, pluginCtx, ui);

        TextView labelMsg = new TextView(mapCtx);
        labelMsg.setText("\nAPRS message (comment on position)");
        labelMsg.setTextColor(0xFFAAAAAA);
        layout.addView(labelMsg);
        ui.editMessage = new EditText(mapCtx);
        ui.editMessage.setText(getAprsMessage(mapCtx));
        ui.editMessage.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        ui.editMessage.setTextColor(0xFFFFFFFF);
        layout.addView(ui.editMessage);

        return ui;
    }

    private static void refreshAprsIconPreviewInDialog(Context mapCtx, Context pluginCtx,
                                                         AprsSettingsUi ui) {
        if (ui == null || ui.iconPreview == null || ui.iconNotSet == null) {
            return;
        }
        if (!isAprsIconSelected(mapCtx)) {
            ui.iconPreview.setVisibility(View.GONE);
            ui.iconPreview.setImageDrawable(null);
            ui.iconNotSet.setVisibility(View.VISIBLE);
            return;
        }
        android.graphics.Bitmap bmp = com.atakmaps.meshcore.plugin.aprs.AprsIconPreviewLoader
                .loadSelectedIconBitmap(mapCtx, pluginCtx);
        if (bmp != null) {
            ui.iconPreview.setImageBitmap(bmp);
            ui.iconPreview.setVisibility(View.VISIBLE);
            ui.iconNotSet.setVisibility(View.GONE);
        } else {
            ui.iconPreview.setVisibility(View.GONE);
            ui.iconNotSet.setVisibility(View.VISIBLE);
            ui.iconNotSet.setText("(not set)");
        }
    }

    public static void saveAprsSettingsFromUi(Context ctx, AprsSettingsUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        SharedPreferences.Editor editor = getPrefs(ctx).edit();
        if (ui.editCallsign != null) {
            editor.putString(PREF_APRS_CALLSIGN,
                    ui.editCallsign.getText().toString().trim().toUpperCase(java.util.Locale.US));
        }
        if (ui.spinnerSsid != null && ui.ssidValues != null) {
            int pos = ui.spinnerSsid.getSelectedItemPosition();
            if (pos >= 0 && pos < ui.ssidValues.length) {
                editor.putString(PREF_APRS_SSID, ui.ssidValues[pos]);
            }
        }
        if (ui.editMessage != null) {
            editor.putString(PREF_APRS_MESSAGE, ui.editMessage.getText().toString());
        }
        editor.apply();
    }

    /**
     * Administration block for ping-reply slot net config (Tools prefs or plugin dialog).
     */
    public static final class AdministrationUi {
        public Switch adminEnabled;
        public EditText editSlotCount;
        public EditText editSlotTime;
        public Button btnDistribute;
        public TextView currentStatus;
        View[] gatedViews;
    }

    /** Adds Administration section views to a scrollable dialog layout. */
    public static AdministrationUi appendAdministrationSection(Context ctx, LinearLayout layout) {
        AdministrationUi ui = new AdministrationUi();

        TextView header = new TextView(ctx);
        header.setText("\nAdministration");
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
