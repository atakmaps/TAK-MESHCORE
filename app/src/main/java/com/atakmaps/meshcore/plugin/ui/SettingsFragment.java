package com.atakmaps.meshcore.plugin.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.atakmap.android.chat.ChatManagerMapComponent;
import com.atakmap.android.gui.PanCheckBoxPreference;
import com.atakmap.android.gui.PanEditTextPreference;
import com.atakmap.android.gui.PanPreference;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.atakmap.app.SettingsActivity;
import com.atakmaps.meshcore.plugin.MeshCoreMapComponent;
import com.atakmaps.meshcore.plugin.R;
import com.atakmaps.meshcore.plugin.beacon.SmartBeacon;
import com.atakmaps.meshcore.plugin.protocol.NetSlotConfig;
import com.atakmaps.meshcore.plugin.protocol.MeshCoreRadioServices;

public class SettingsFragment extends PluginPreferenceFragment
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int COLOR_STD_BLUE = 0xFF1976D2;
    private static final int COLOR_CATEGORY_YELLOW = 0xFFFFEB3B;
    private static final int COLOR_VALUE_GREEN = 0xFF4CAF50;
    private static final int COLOR_DISABLED_GREY = 0xFF757575;
    private static final int COLOR_WARNING_RED = 0xFFFF5252;
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int COLOR_PILL_BUTTON_STROKE = 0xFF00BCD4;
    private static final int PILL_BUTTON_TEXT_SP = 15;
    private static final int PILL_BUTTON_MIN_HEIGHT_DP = 40;
    private static final int PILL_BUTTON_PAD_HORIZONTAL_DP = 16;
    private static final int PILL_BUTTON_PAD_VERTICAL_DP = 8;
    private static final int PILL_BUTTON_ROW_MARGIN_VERTICAL_DP = 4;
    private static final String EMBEDDED_PILL_BUTTON_TAG = "meshcore_embedded_pill_button";
    private static final String EMBEDDED_CHECKBOX_TAG = "meshcore_embedded_checkbox";
    private static final int ROW_PREF_KEY_TAG = R.id.meshcore_row_pref_key;
    private static final int SUMMARY_WATCHER_TAG = R.id.meshcore_summary_watcher;
    private static final int SUMMARY_REBIND_TAG = R.id.meshcore_summary_rebind;
    private static final int PREFERENCE_TITLE_TEXT_SP = 16;
    private static final float CATEGORY_TITLE_TEXT_SP = 18f;
    private static final float DISABLED_ROW_ALPHA = 0.38f;

    private final Map<String, Preference.OnPreferenceClickListener> preferenceClickHandlers =
            new HashMap<>();

    private Runnable pendingRowStyleApply;
    private Runnable pendingValueSummaryRebind;
    private boolean rowStylePreDrawListenerAttached;

    private static final int COLOR_WHITE = 0xFFFFFFFF;

    /** Beacon interval + Smart Beacon (Tool Preferences section). */
    public static final String KEY_RESTORE_ALL_DEFAULTS = "meshcore_restore_all_defaults";
    public static final String KEY_CAT_BEACON = "meshcore_cat_beacon";
    public static final String KEY_RESTORE_BEACON_DEFAULTS = "meshcore_restore_beacon_defaults";
    public static final String KEY_RESTORE_ADMIN_DEFAULTS = "meshcore_restore_admin_defaults";
    public static final String KEY_SMART_BEACON_SECTION_HEADER =
            "meshcore_smart_beacon_section_header";
    private static final String BEACON_INTERVAL_DESC =
            "Sets the ATAK call sign beacon interval";
    private static final String SMART_BEACON_SECTION_DESC =
            "Sets automatic beacons based off of movement";
    private static final String PING_REPLY_DESC =
            "Automatically reply to incoming pings with your position";
    private static final String RETRY_INTERVAL_DESC =
            "How long to wait before retransmitting an unacknowledged message";
    private static final String RETRY_MAX_DESC =
            "Number of retransmit attempts before declaring delivery failure";

    /** Key registered with {@code ToolsPreferenceFragment} in {@link com.atakmaps.meshcore.plugin.MeshCoreMapComponent}. */
    public static final String TOOL_SETTINGS_KEY = "meshcorePreference";

    public static final String PREF_ENCRYPTION_ENABLED = "meshcore_encryption_enabled";
    public static final String PREF_ENCRYPTION_PASSPHRASE = "meshcore_encryption_passphrase";
    public static final String KEY_CAT_SECURITY = "meshcore_cat_security";
    private static final String ENCRYPTION_PASSPHRASE_DESC =
            "Same value on every radio that uses RF encryption";
    public static final String PREF_RETRY_INTERVAL_MIN = "meshcore_retry_interval_min";
    public static final String PREF_RETRY_MAX = "meshcore_retry_max";
    public static final String PREF_SA_RELAY_ENABLED = "meshcore_sa_relay_enabled";
    public static final String PREF_RF_TO_TAK_UPLINK_ENABLED = "meshcore_rf_to_tak_uplink_enabled";
    /** Admin-only — UI persistence; runtime limiting wired separately. */
    public static final String PREF_DISABLE_MESH_BEACON_LIMITING =
            "meshcore_disable_mesh_beacon_limiting";
    /** Same copy as UV-PRO {@code DISABLE_MESH_BEACON_LIMITING_DESC}. */
    public static final String DISABLE_MESH_BEACON_LIMITING_DESC =
            "When disabled, this allows mesh beaconing to follow all smart beacon "
                    + "settings without mesh limits";
    private static final String DISABLE_MESH_BEACON_LIMITING_TITLE =
            "Disable Mesh Beacon Limiting";
    public static final String PREF_PING_REPLY_ENABLED = "meshcore_ping_reply_enabled";
    public static final boolean DEFAULT_PING_REPLY_ENABLED = true;
    public static final String PREF_BEACON_INTERVAL = "meshcore_beacon_interval";

    public static final String KEY_UNLOCK_ADMIN = "meshcore_admin_access";
    public static final String KEY_CAT_ADMINISTRATION = "meshcore_cat_administration";
    public static final String KEY_ADMIN_LEADERSHIP_WARNING = "meshcore_admin_leadership_warning";
    /** Stale keys — stripped on load after plugin updates. */
    private static final String KEY_ADMIN_CURRENT_SLOT_STATUS = "meshcore_admin_current_slot_status";
    private static final String KEY_DISTRIBUTE_NET_SLOTS = "meshcore_distribute_net_slots";

    private static final String[] OBSOLETE_ADMIN_PREF_KEYS = {
            KEY_ADMIN_CURRENT_SLOT_STATUS,
            KEY_DISTRIBUTE_NET_SLOTS,
            NetSlotConfig.PREF_SLOT_COUNT,
    };

    /** Injected after inflate — some ATAK builds omit custom Pan* prefs from XML. */
    public static final String KEY_CAT_RADIO = "meshcore_cat_radio";

    private static final String[] MESH_ADMIN_GATED_PREF_KEYS = {
            PREF_DISABLE_MESH_BEACON_LIMITING,
    };

    private static final String[] REMOVE_FROM_ADMIN_KEYS = {
            KEY_RESTORE_BEACON_DEFAULTS,
            KEY_RESTORE_ALL_DEFAULTS,
    };

    private static final String[] REMOVE_FROM_BEACON_KEYS = {
            KEY_RESTORE_ALL_DEFAULTS,
            KEY_RESTORE_ADMIN_DEFAULTS,
    };

    /** Stale rows from older plugin builds — stripped on load and trigger prefs reload. */
    private static final String[] OBSOLETE_MESH_BEACON_PREF_KEYS = {
            "meshcore_mesh_beacon_national_warning",
            "meshcore_mesh_beacon_enabled",
    };

    private static boolean isCheckboxPreferenceKey(String key) {
        return PREF_PING_REPLY_ENABLED.equals(key)
                || PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)
                || NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key);
    }

    private static boolean isCheckboxPreference(Preference pref) {
        return pref != null
                && (pref instanceof CheckBoxPreference
                || isCheckboxPreferenceKey(pref.getKey()));
    }

    public static final String DEFAULT_BEACON_INTERVAL = "300";
    public static final String DEFAULT_RETRY_INTERVAL_MIN = "2";
    public static final String DEFAULT_RETRY_MAX = "3";

    private static Context staticPluginContext;

    private PreferenceCategory adminCategory;
    private boolean adminUnlockDialogOpen = false;
    /** Blocks spurious enable events fired while disabling admin settings. */
    private boolean suppressAdminPasswordPrompt = false;

    /**
     * Zero-arg constructor required by Android fragment system.
     * Only valid after the 1-arg constructor has been called once.
     */
    public SettingsFragment() {
        super(staticPluginContext, resolvePreferencesResourceId(staticPluginContext));
    }

    public SettingsFragment(final Context pluginContext) {
        super(pluginContext, resolvePreferencesResourceId(pluginContext));
        staticPluginContext = pluginContext;
    }

    private static int resolvePreferencesResourceId(Context ctx) {
        if (ctx == null) {
            return 0;
        }
        return ctx.getResources().getIdentifier(
                "preferences", "xml", ctx.getPackageName());
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            NetSlotConfig.ensureDefaults(ctx);
        }
        ensurePreferenceTreeLoaded();
        removeObsoletePreferences();
        ensureRequiredPreferences();
        normalizeBeaconSection();
        normalizeAdministrationSection();
        normalizeAllRestoreControls();
        wireRestorePreferenceHandlers();
        wireBeaconPreferences();
        wirePersistentPreferenceWriters();
        wireAdministrationPreferences();
        ensureAdminCheckboxPreferences();
        wireAdminSettingsGate();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        ensurePreferenceTreeLoaded();
        removeObsoletePreferences();
        ensureRequiredPreferences();
        normalizeBeaconSection();
        normalizeAdministrationSection();
        normalizeAllRestoreControls();
        wireRestorePreferenceHandlers();
        ensureAdminCheckboxPreferences();
        wireAdministrationPreferences();
        adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        wireAdminSettingsGate();
        updateAdminControlsEnabled();
        syncSmartBeaconPreferenceValues();
        updateSummaries();
        refreshPreferenceList();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.setItemsCanFocus(true);
            scheduleApplyRowStyles();
            list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    scheduleApplyRowStyles();
                }

                @Override
                public void onChildViewRemoved(View parent, View child) {
                }
            });
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(AbsListView view, int scrollState) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        scheduleApplyRowStyles();
                    }
                }

                @Override
                public void onScroll(AbsListView view, int firstVisibleItem,
                                     int visibleItemCount, int totalItemCount) {
                }
            });
            attachRowStylePreDrawListener(list);
            list.post(this::applyRowStyles);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        wireRestorePreferenceHandlers();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
        styleAdminLeadershipWarning();
        syncAdminSettingsGateOnResume();
        updateAdminControlsEnabled();
        syncSmartBeaconPreferenceValues();
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceTreeClick(android.preference.PreferenceScreen preferenceScreen,
                                         Preference preference) {
        if (preference != null && preference.getKey() != null
                && isPillActionPreferenceKey(preference.getKey())) {
            dispatchPillActionClick(preference.getKey());
            return true;
        }
        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs,
                                          String key) {
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            updateAdminControlsEnabled();
        }
        if (NetSlotConfig.PREF_SLOT_TIME_SEC.equals(key)) {
            normalizeSlotPreferences(prefs);
        }
        if (PREF_BEACON_INTERVAL.equals(key) || isSmartBeaconParamKey(key)) {
            if (isSmartBeaconParamKey(key)) {
                persistSmartBeaconFromPreferences(prefs);
            }
            notifyRuntimeSettingsChanged();
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            notifyRuntimeSettingsChanged();
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            MeshCoreRadioServices.syncEncryptionFromSettings(resolveSettingsContext());
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
            notifyRuntimeSettingsChanged();
        }
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    /**
     * ATAK can persist a stale preference tree across plugin updates. If core categories
     * from {@code preferences.xml} are missing, reload the full tree from the plugin XML.
     */
    private void ensurePreferenceTreeLoaded() {
        if (hasCorePreferenceCategories() && !hasObsoleteMeshBeaconPreferences()) {
            return;
        }
        Context pluginCtx = staticPluginContext;
        if (pluginCtx == null && MapView.getMapView() != null) {
            pluginCtx = MapView.getMapView().getContext();
        }
        int resId = resolvePreferencesResourceId(pluginCtx);
        if (resId == 0) {
            android.util.Log.e("MeshCore.Settings",
                    "preferences.xml not found — settings tree cannot load");
            return;
        }
        android.util.Log.w("MeshCore.Settings",
                "Core settings categories missing; reloading preferences.xml");
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            screen.removeAll();
        }
        addPreferencesFromResource(resId);
    }

    private boolean hasCorePreferenceCategories() {
        return findPreference(KEY_CAT_BEACON) != null
                && findPreference(KEY_CAT_RADIO) != null
                && findPreference(KEY_CAT_SECURITY) != null;
    }

    private boolean hasObsoleteMeshBeaconPreferences() {
        for (String key : OBSOLETE_MESH_BEACON_PREF_KEYS) {
            if (findPreference(key) != null) {
                return true;
            }
        }
        for (String key : OBSOLETE_ADMIN_PREF_KEYS) {
            if (findPreference(key) != null) {
                return true;
            }
        }
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin == null) {
            return false;
        }
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (isObsoleteAdminPreference(pref)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isObsoleteAdminPreference(Preference pref) {
        if (pref == null) {
            return false;
        }
        String key = pref.getKey();
        if (key != null) {
            for (String obsoleteKey : OBSOLETE_ADMIN_PREF_KEYS) {
                if (obsoleteKey.equals(key)) {
                    return true;
                }
            }
        }
        if (pref.getTitle() == null) {
            return false;
        }
        String title = pref.getTitle().toString().toLowerCase(java.util.Locale.US);
        if (title.contains("currently set on")) {
            return true;
        }
        if (title.contains("distribute to net")) {
            return true;
        }
        if (title.contains("mesh beacon") && title.contains("national")) {
            return true;
        }
        return "slot count".equals(title);
    }

    private void removeObsoletePreferences() {
        removePreferenceFromScreen(PREF_ENCRYPTION_ENABLED);
        removePreferenceFromScreen(PREF_SA_RELAY_ENABLED);
        removePreferenceFromScreen(PREF_RF_TO_TAK_UPLINK_ENABLED);
        removePreferenceFromScreen("meshcore_cat_sa_relay");
        removePreferenceFromScreen(NetSlotConfig.PREF_SLOT_TIME_SEC);
        removePreferenceFromScreen(KEY_UNLOCK_ADMIN);
        removePreferenceFromScreen("meshcore_cat_bluetooth");
        removePreferenceFromScreen("meshcore_auto_reconnect");
        removePreferenceFromScreen("meshcore_bluetooth_devices");
        for (String key : OBSOLETE_MESH_BEACON_PREF_KEYS) {
            removePreferenceFromScreen(key);
        }
        for (String key : OBSOLETE_ADMIN_PREF_KEYS) {
            removePreferenceFromScreen(key);
        }
    }

    private void removePreferenceFromScreen(String key) {
        Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        PreferenceGroup parent = pref.getParent();
        if (parent != null) {
            parent.removePreference(pref);
        } else {
            android.preference.PreferenceScreen screen = getPreferenceScreen();
            if (screen != null) {
                screen.removePreference(pref);
            }
        }
    }

    /**
     * Some ATAK builds drop custom Pan* prefs from XML inflation. Inject any missing
     * required controls before preference binding runs.
     */
    private void ensureRequiredPreferences() {
        PreferenceCategory radio = (PreferenceCategory) findPreference(KEY_CAT_RADIO);
        if (radio != null) {
            ensureCheckBoxPreferenceOrReplace(radio, PREF_PING_REPLY_ENABLED,
                    "Send Ping Reply", PING_REPLY_DESC, DEFAULT_PING_REPLY_ENABLED);
        }
    }

    private void ensureCheckBoxPreferenceOrReplace(PreferenceGroup parent, String key,
                                                     String title, String summary,
                                                     boolean defaultValue) {
        Preference existing = findPreference(key);
        if (existing instanceof CheckBoxPreference) {
            return;
        }
        if (existing != null && existing.getParent() != null) {
            existing.getParent().removePreference(existing);
        }
        ensureCheckBoxPreference(parent, key, title, summary, defaultValue);
    }

    private void ensureCheckBoxPreference(PreferenceGroup parent, String key, String title,
                                          String summary, boolean defaultValue) {
        if (parent == null || findPreference(key) != null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        if (ctx == null) {
            return;
        }
        PanCheckBoxPreference pref = new PanCheckBoxPreference(ctx);
        pref.setKey(key);
        pref.setTitle(title);
        pref.setSummary(summary);
        pref.setDefaultValue(defaultValue);
        parent.addPreference(pref);
    }

    private void wirePersistentPreferenceWriters() {
        wireEditTextPreference(SmartBeacon.KEY_LOW_SPEED);
        wireEditTextPreference(SmartBeacon.KEY_HIGH_SPEED);
        wireEditTextPreference(SmartBeacon.KEY_SLOW_RATE);
        wireEditTextPreference(SmartBeacon.KEY_FAST_RATE);
        wireEditTextPreference(SmartBeacon.KEY_MIN_TURN_TIME);
        wireEditTextPreference(SmartBeacon.KEY_TURN_THRESHOLD);
        wireEditTextPreference(SmartBeacon.KEY_TURN_SLOPE);
        wireListPreference(PREF_BEACON_INTERVAL);
        wireListPreference(PREF_RETRY_INTERVAL_MIN);
        wireListPreference(PREF_RETRY_MAX);
        wireCheckBoxPreference(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
        wireCheckBoxPreference(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        wireCheckBoxPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        wireEditTextPreference(PREF_ENCRYPTION_PASSPHRASE);
    }

    private void wireCheckBoxPreference(String key, boolean defaultValue) {
        Preference pref = findPreference(key);
        if (!(pref instanceof CheckBoxPreference)) {
            return;
        }
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            boolean checked = Boolean.TRUE.equals(newValue);
            Context ctx = resolveSettingsContext();
            if (ctx != null) {
                getPrefs(ctx).edit().putBoolean(key, checked).apply();
            }
            if (preference instanceof CheckBoxPreference) {
                ((CheckBoxPreference) preference).setChecked(checked);
            }
            if (PREF_PING_REPLY_ENABLED.equals(key)) {
                notifyRuntimeSettingsChanged();
            }
            if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
                notifyRuntimeSettingsChanged();
            }
            if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
                updateAdminControlsEnabled();
            }
            updateSummaries();
            ListView list = getPreferenceListView();
            if (list != null) {
                list.post(this::applyRowStyles);
            }
            return true;
        });
    }

    private void wireEditTextPreference(String key) {
        Preference pref = findPreference(key);
        if (!(pref instanceof EditTextPreference)) {
            return;
        }
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String text = newValue != null ? newValue.toString().trim() : "";
            Context ctx = resolveSettingsContext();
            if (ctx != null) {
                getPrefs(ctx).edit().putString(key, text).apply();
            }
            if (preference instanceof PanEditTextPreference) {
                ((PanEditTextPreference) preference).setText(text);
            } else if (preference instanceof EditTextPreference) {
                ((EditTextPreference) preference).setText(text);
            }
            if (isSmartBeaconParamKey(key) && ctx != null) {
                persistSmartBeaconFromPreferences(getPrefs(ctx));
            }
            if (PREF_BEACON_INTERVAL.equals(key) || isSmartBeaconParamKey(key)) {
                notifyRuntimeSettingsChanged();
            }
            if (PREF_ENCRYPTION_PASSPHRASE.equals(key) && ctx != null) {
                MeshCoreRadioServices.syncEncryptionFromSettings(ctx);
            }
            updateSummaries();
            ListView list = getPreferenceListView();
            if (list != null) {
                list.post(this::applyRowStyles);
            }
            return true;
        });
    }

    private void wireListPreference(String key) {
        Preference pref = findPreference(key);
        if (!(pref instanceof ListPreference)) {
            return;
        }
        pref.setOnPreferenceClickListener(preference -> {
            scheduleApplyRowStyles();
            return false;
        });
        pref.setOnPreferenceChangeListener((preference, newValue) -> {
            String value = newValue != null ? newValue.toString() : "";
            Context ctx = resolveSettingsContext();
            if (ctx != null) {
                getPrefs(ctx).edit().putString(key, value).apply();
            }
            if (preference instanceof ListPreference) {
                ((ListPreference) preference).setValue(value);
            }
            if (PREF_BEACON_INTERVAL.equals(key)) {
                notifyRuntimeSettingsChanged();
            }
            updateSummaries();
            ListView list = getPreferenceListView();
            if (list != null) {
                list.post(this::applyRowStyles);
            }
            return true;
        });
    }

    private void wireBeaconPreferences() {
        Preference header = findPreference(KEY_SMART_BEACON_SECTION_HEADER);
        if (header != null) {
            header.setSummary(SMART_BEACON_SECTION_DESC);
            header.setSelectable(false);
            header.setEnabled(true);
            header.setShouldDisableView(false);
        }
        syncSmartBeaconPreferenceValues();
    }

    private void wireRestorePreferenceHandlers() {
        Preference restoreAll = findPreference(KEY_RESTORE_ALL_DEFAULTS);
        if (restoreAll != null) {
            restoreAll.setSummary("");
            attachPreferencePillClickHandler(restoreAll);
        }
        Preference restoreBeacon = findPreference(KEY_RESTORE_BEACON_DEFAULTS);
        if (restoreBeacon != null) {
            restoreBeacon.setSummary("");
            attachPreferencePillClickHandler(restoreBeacon);
        }
        Preference restoreAdmin = findPreference(KEY_RESTORE_ADMIN_DEFAULTS);
        if (restoreAdmin != null) {
            restoreAdmin.setSummary("");
            attachPreferencePillClickHandler(restoreAdmin);
        }
    }







    /**
     * ATAK can duplicate restore rows across plugin updates. Rebuild exactly three restore
     * controls: global top, beacon section, and admin section.
     */
    private void normalizeAllRestoreControls() {
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return;
        }
        removeAllRestoreControlsFromTree(screen);
        ensureGlobalRestoreAllAtScreenRoot();
        PreferenceCategory beacon = (PreferenceCategory) findPreference(KEY_CAT_BEACON);
        if (beacon != null) {
            ensureBeaconRestoreDefaultsPreference(beacon);
        }
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin != null) {
            ensureAdminRestoreDefaultsPreference(admin);
        }
        wireRestorePreferenceHandlers();
    }

    private void removeAllRestoreControlsFromTree(PreferenceGroup group) {
        if (group == null) {
            return;
        }
        List<Preference> remove = new ArrayList<>();
        collectRestoreControls(group, remove);
        for (Preference pref : remove) {
            PreferenceGroup parent = pref.getParent();
            if (parent != null) {
                parent.removePreference(pref);
            }
        }
    }

    private void collectRestoreControls(PreferenceGroup group, List<Preference> remove) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref == null) {
                continue;
            }
            if (pref instanceof PreferenceGroup) {
                collectRestoreControls((PreferenceGroup) pref, remove);
            }
            if (isRestoreControlPreference(pref)) {
                remove.add(pref);
            }
        }
    }

    private static boolean isRestoreControlKey(String key) {
        return KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key);
    }

    private static boolean isRestoreControlPreference(Preference pref) {
        if (pref == null || pref instanceof PreferenceGroup) {
            return false;
        }
        String key = pref.getKey();
        if (isRestoreControlKey(key)) {
            return true;
        }
        CharSequence title = pref.getTitle();
        if (title == null) {
            return false;
        }
        return "Restore All Defaults".contentEquals(title)
                || "Restore Defaults".contentEquals(title);
    }

    private void ensureGlobalRestoreAllAtScreenRoot() {
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        Context ctx = resolveSettingsContext();
        if (screen == null || ctx == null) {
            return;
        }
        Preference restoreAll = findPreference(KEY_RESTORE_ALL_DEFAULTS);
        if (restoreAll != null && restoreAll.getParent() != null) {
            ((PreferenceGroup) restoreAll.getParent()).removePreference(restoreAll);
        }
        if (restoreAll == null) {
            restoreAll = new Preference(ctx);
            restoreAll.setKey(KEY_RESTORE_ALL_DEFAULTS);
            restoreAll.setTitle("Restore All Defaults");
        }
        restoreAll.setSummary("");
        restoreAll.setPersistent(false);
        restoreAll.setSelectable(true);
        restoreAll.setEnabled(true);
        int topOrder = Preference.DEFAULT_ORDER;
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            Preference pref = screen.getPreference(i);
            if (pref != null) {
                topOrder = Math.min(topOrder, pref.getOrder());
            }
        }
        restoreAll.setOrder(topOrder - 10000);
        screen.addPreference(restoreAll);
    }

    private void ensureBeaconRestoreDefaultsPreference(PreferenceCategory beacon) {
        if (beacon == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        Preference restore = findPreference(KEY_RESTORE_BEACON_DEFAULTS);
        if (restore != null && restore.getParent() != null && restore.getParent() != beacon) {
            ((PreferenceGroup) restore.getParent()).removePreference(restore);
        }
        if (restore == null) {
            restore = new Preference(ctx);
            restore.setKey(KEY_RESTORE_BEACON_DEFAULTS);
            restore.setTitle("Restore Defaults");
        }
        restore.setSummary("");
        restore.setPersistent(false);
        restore.setSelectable(true);
        int minOrder = Preference.DEFAULT_ORDER;
        for (int i = 0; i < beacon.getPreferenceCount(); i++) {
            Preference pref = beacon.getPreference(i);
            if (pref != null && pref != restore) {
                minOrder = Math.min(minOrder, pref.getOrder());
            }
        }
        restore.setOrder(minOrder - 1);
        if (restore.getParent() != beacon) {
            beacon.addPreference(restore);
        }
    }

    private void ensureAdminRestoreDefaultsPreference(PreferenceCategory admin) {
        if (admin == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        Preference restore = findPreference(KEY_RESTORE_ADMIN_DEFAULTS);
        if (restore != null && restore.getParent() != null && restore.getParent() != admin) {
            ((PreferenceGroup) restore.getParent()).removePreference(restore);
        }
        if (restore == null) {
            restore = new Preference(ctx);
            restore.setKey(KEY_RESTORE_ADMIN_DEFAULTS);
            restore.setTitle("Restore Defaults");
        }
        restore.setSummary("");
        restore.setPersistent(false);
        restore.setSelectable(true);
        int minOrder = Preference.DEFAULT_ORDER;
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (pref != null && pref != restore) {
                minOrder = Math.min(minOrder, pref.getOrder());
            }
        }
        restore.setOrder(minOrder - 1);
        if (restore.getParent() != admin) {
            admin.addPreference(restore);
        }
    }

    private void showRestoreConfirmDialog(String title, Runnable onConfirm) {
        Runnable show = () -> {
            Context dialogCtx = getActivity();
            if (dialogCtx == null && MapView.getMapView() != null) {
                dialogCtx = MapView.getMapView().getContext();
            }
            if (dialogCtx == null) {
                dialogCtx = resolveSettingsContext();
            }
            if (dialogCtx == null || onConfirm == null) {
                return;
            }
            try {
                new AlertDialog.Builder(dialogCtx)
                        .setTitle(title)
                        .setMessage("Are you sure?")
                        .setPositiveButton("Confirm", (dialog, which) -> onConfirm.run())
                        .setNegativeButton("Cancel", null)
                        .show();
            } catch (Exception e) {
                android.util.Log.e("MeshCore.Settings", "Restore confirm dialog failed", e);
            }
        };
        if (getActivity() != null) {
            getActivity().runOnUiThread(show);
        } else {
            show.run();
        }
    }

    private void restoreBeaconDefaults(Context ctx) {
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                .apply();
        setListPreferenceValue(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        SmartBeacon.setEnabled(ctx, SmartBeacon.DEFAULT_ENABLED);
        SmartBeacon.saveAll(ctx,
                SmartBeacon.DEFAULT_LOW_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED,
                SmartBeacon.DEFAULT_SLOW_RATE,
                SmartBeacon.DEFAULT_FAST_RATE,
                SmartBeacon.DEFAULT_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_SLOPE);
        refreshSettingsUiAfterRestore(ctx);
    }

    private void restoreAllDefaults(Context ctx) {
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                .putBoolean(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED)
                .putString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN)
                .putString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX)
                .putString(PREF_ENCRYPTION_PASSPHRASE, "")
                .putBoolean(PREF_ENCRYPTION_ENABLED, false)
                .putBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false)
                .putBoolean(PREF_SA_RELAY_ENABLED, false)
                .putBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false)
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                .putString(NetSlotConfig.PREF_SLOT_COUNT,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_COUNT))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_TIME_SEC))
                .apply();
        SmartBeacon.setEnabled(ctx, SmartBeacon.DEFAULT_ENABLED);
        SmartBeacon.saveAll(ctx,
                SmartBeacon.DEFAULT_LOW_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED,
                SmartBeacon.DEFAULT_SLOW_RATE,
                SmartBeacon.DEFAULT_FAST_RATE,
                SmartBeacon.DEFAULT_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_SLOPE);
        AdminAccessGate.lock(ctx);
        setListPreferenceValue(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL);
        setCheckBoxPreferenceValue(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
        setListPreferenceValue(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN);
        setListPreferenceValue(PREF_RETRY_MAX, DEFAULT_RETRY_MAX);
        setEditTextPreferenceText(PREF_ENCRYPTION_PASSPHRASE, "");
        setCheckBoxPreferenceValue(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        setCheckBoxPreferenceValue(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        refreshSettingsUiAfterRestore(ctx);
    }

    private void restoreAdminDefaults(Context ctx) {
        if (ctx == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                .putBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false)
                .putBoolean(PREF_SA_RELAY_ENABLED, false)
                .putBoolean(PREF_RF_TO_TAK_UPLINK_ENABLED, false)
                .putString(NetSlotConfig.PREF_SLOT_COUNT,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_COUNT))
                .putString(NetSlotConfig.PREF_SLOT_TIME_SEC,
                        String.valueOf(NetSlotConfig.DEFAULT_SLOT_TIME_SEC))
                .apply();
        AdminAccessGate.lock(ctx);
        setCheckBoxPreferenceValue(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        setCheckBoxPreferenceValue(PREF_DISABLE_MESH_BEACON_LIMITING, false);
        refreshSettingsUiAfterRestore(ctx);
    }

    private void refreshSettingsUiAfterRestore(Context ctx) {
        syncAllPreferencesFromAtakToUi();
        refreshAdminGateUi();
        MeshCoreRadioServices.syncEncryptionFromSettings(ctx);
        notifyRuntimeSettingsChanged();
    }

    private void syncAllPreferencesFromAtakToUi() {
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            return;
        }
        SharedPreferences atak = getPrefs(ctx);
        setListPreferenceValue(PREF_BEACON_INTERVAL,
                atak.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL));
        setListPreferenceValue(PREF_RETRY_INTERVAL_MIN,
                atak.getString(PREF_RETRY_INTERVAL_MIN, DEFAULT_RETRY_INTERVAL_MIN));
        setListPreferenceValue(PREF_RETRY_MAX,
                atak.getString(PREF_RETRY_MAX, DEFAULT_RETRY_MAX));
        setCheckBoxPreferenceValue(PREF_PING_REPLY_ENABLED,
                atak.getBoolean(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED));
        setCheckBoxPreferenceValue(PREF_DISABLE_MESH_BEACON_LIMITING,
                atak.getBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false));
        setCheckBoxPreferenceValue(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                atak.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        setEditTextPreferenceText(PREF_ENCRYPTION_PASSPHRASE,
                atak.getString(PREF_ENCRYPTION_PASSPHRASE, ""));
        syncSmartBeaconPreferenceValues();
    }

    private void setCheckBoxPreferenceValue(String key, boolean checked) {
        Preference pref = findPreference(key);
        if (pref instanceof CheckBoxPreference) {
            ((CheckBoxPreference) pref).setChecked(checked);
        }
    }

    private void setListPreferenceValue(String key, String value) {
        Preference pref = findPreference(key);
        if (pref instanceof ListPreference) {
            ((ListPreference) pref).setValue(value);
        }
    }

    private void setEditTextPreferenceText(String key, String value) {
        Preference pref = findPreference(key);
        if (pref instanceof EditTextPreference) {
            ((EditTextPreference) pref).setText(value != null ? value : "");
        }
    }

    private void syncSmartBeaconPreferenceValues() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        setEditTextPreferenceText(SmartBeacon.KEY_LOW_SPEED,
                String.valueOf(SmartBeacon.getLowSpeed(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_HIGH_SPEED,
                String.valueOf(SmartBeacon.getHighSpeed(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_SLOW_RATE,
                String.valueOf(SmartBeacon.getSlowRate(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_FAST_RATE,
                String.valueOf(SmartBeacon.getFastRate(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_MIN_TURN_TIME,
                String.valueOf(SmartBeacon.getMinTurnTime(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_TURN_THRESHOLD,
                String.valueOf(SmartBeacon.getTurnThreshold(ctx)));
        setEditTextPreferenceText(SmartBeacon.KEY_TURN_SLOPE,
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));
    }

    private static boolean isSmartBeaconParamKey(String key) {
        return SmartBeacon.KEY_LOW_SPEED.equals(key)
                || SmartBeacon.KEY_HIGH_SPEED.equals(key)
                || SmartBeacon.KEY_SLOW_RATE.equals(key)
                || SmartBeacon.KEY_FAST_RATE.equals(key)
                || SmartBeacon.KEY_MIN_TURN_TIME.equals(key)
                || SmartBeacon.KEY_TURN_THRESHOLD.equals(key)
                || SmartBeacon.KEY_TURN_SLOPE.equals(key);
    }

    private void persistSmartBeaconFromPreferences(SharedPreferences source) {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null || source == null) {
            return;
        }
        int lowSpeed = readSmartBeaconInt(source, SmartBeacon.KEY_LOW_SPEED,
                SmartBeacon.DEFAULT_LOW_SPEED);
        int highSpeed = readSmartBeaconInt(source, SmartBeacon.KEY_HIGH_SPEED,
                SmartBeacon.DEFAULT_HIGH_SPEED);
        int slowRate = readSmartBeaconInt(source, SmartBeacon.KEY_SLOW_RATE,
                SmartBeacon.DEFAULT_SLOW_RATE);
        int fastRate = readSmartBeaconInt(source, SmartBeacon.KEY_FAST_RATE,
                SmartBeacon.DEFAULT_FAST_RATE);
        int minTurnTime = readSmartBeaconInt(source, SmartBeacon.KEY_MIN_TURN_TIME,
                SmartBeacon.DEFAULT_MIN_TURN_TIME);
        int turnThreshold = readSmartBeaconInt(source, SmartBeacon.KEY_TURN_THRESHOLD,
                SmartBeacon.DEFAULT_TURN_THRESHOLD);
        int turnSlope = readSmartBeaconInt(source, SmartBeacon.KEY_TURN_SLOPE,
                SmartBeacon.DEFAULT_TURN_SLOPE);

        if (highSpeed <= lowSpeed) {
            highSpeed = lowSpeed + 10;
        }
        if (fastRate >= slowRate) {
            fastRate = Math.max(1, slowRate / 2);
        }
        fastRate = Math.max(1, fastRate);
        slowRate = Math.max(fastRate + 1, slowRate);
        minTurnTime = Math.max(1, minTurnTime);
        turnThreshold = Math.max(1, turnThreshold);
        turnSlope = Math.max(0, turnSlope);

        SmartBeacon.saveAll(ctx, lowSpeed, highSpeed, slowRate, fastRate,
                minTurnTime, turnThreshold, turnSlope);
        syncSmartBeaconPreferenceValues();
    }

    private static int readSmartBeaconInt(SharedPreferences prefs, String key, int fallback) {
        try {
            return prefs.getInt(key, fallback);
        } catch (ClassCastException ignored) {
            try {
                return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)).trim());
            } catch (Exception ignored2) {
                return fallback;
            }
        }
    }

    private void notifyRuntimeSettingsChanged() {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(MeshCoreMapComponent.ACTION_BEACON_INTERVAL_CHANGED));
        } catch (Exception ignored) {
        }
    }

    private Context resolveSettingsContext() {
        MapView mv = MapView.getMapView();
        if (mv != null && mv.getContext() != null) {
            return mv.getContext();
        }
        if (getActivity() != null) {
            return getActivity();
        }
        if (getContext() != null) {
            return getContext();
        }
        return staticPluginContext;
    }

    private String getListPreferenceValueLabel(String key) {
        Preference pref = findPreference(key);
        if (!(pref instanceof ListPreference)) {
            return null;
        }
        ListPreference listPref = (ListPreference) pref;
        CharSequence label = listPref.getEntry();
        return label != null ? label.toString() : null;
    }





    private void updateSmartBeaconFieldSummaries() {
        Context ctx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : staticPluginContext;
        if (ctx == null) {
            return;
        }
        setSummaryWithValue(SmartBeacon.KEY_LOW_SPEED,
                "Below this speed the slowest rate is used",
                SmartBeacon.getLowSpeed(ctx) + " mph");
        setSummaryWithValue(SmartBeacon.KEY_HIGH_SPEED,
                "Above this speed the fastest rate is used",
                SmartBeacon.getHighSpeed(ctx) + " mph");
        setSummaryWithValue(SmartBeacon.KEY_SLOW_RATE,
                "Max time between beacons when slow or stopped",
                SmartBeacon.getSlowRate(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_FAST_RATE,
                "Min time between beacons when moving fast",
                SmartBeacon.getFastRate(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_MIN_TURN_TIME,
                "Minimum delay between corner-pegging beacons",
                SmartBeacon.getMinTurnTime(ctx) + " s");
        setSummaryWithValue(SmartBeacon.KEY_TURN_THRESHOLD,
                "Heading change needed to trigger an early beacon",
                SmartBeacon.getTurnThreshold(ctx) + "°");
        setSummaryWithValue(SmartBeacon.KEY_TURN_SLOPE,
                "Scales turn sensitivity with speed (higher = less sensitive at low speed)",
                String.valueOf(SmartBeacon.getTurnSlope(ctx)));
    }

    private void wireAdminSettingsGate() {
        adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        Preference adminToggle = findPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED);
        if (!(adminToggle instanceof CheckBoxPreference)) {
            return;
        }
        CheckBoxPreference adminCheck = (CheckBoxPreference) adminToggle;
        adminCheck.setOnPreferenceChangeListener((preference, newValue) ->
                handleAdminSettingsChange((CheckBoxPreference) preference,
                        Boolean.TRUE.equals(newValue)));
    }

    /**
     * @return {@code true} when the preference framework should accept the new checked state
     */
    private boolean handleAdminSettingsChange(CheckBoxPreference checkbox, boolean enable) {
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx == null || checkbox == null) {
            return false;
        }

        if (!enable) {
            suppressAdminPasswordPrompt = true;
            AdminAccessGate.lock(prefsCtx);
            checkbox.setChecked(false);
            refreshAdminGateUi();
            postClearAdminPasswordPromptSuppression();
            return true;
        }

        if (suppressAdminPasswordPrompt) {
            checkbox.setChecked(false);
            return false;
        }

        if (AdminAccessGate.isUnlocked(prefsCtx)) {
            enableAdminSettings(checkbox, prefsCtx);
            return true;
        }

        checkbox.setChecked(false);
        Context dialogCtx = getActivity() != null ? getActivity() : prefsCtx;
        promptAdministrativeUnlockForToggle(dialogCtx, prefsCtx, checkbox);
        return false;
    }

    private void postClearAdminPasswordPromptSuppression() {
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(() -> suppressAdminPasswordPrompt = false);
        } else {
            suppressAdminPasswordPrompt = false;
        }
    }

    private void promptAdministrativeUnlockForToggle(Context dialogCtx, Context prefsCtx,
                                                     CheckBoxPreference checkbox) {
        if (dialogCtx == null || checkbox == null || adminUnlockDialogOpen) {
            return;
        }
        if (prefsCtx == null) {
            prefsCtx = dialogCtx;
        }
        if (!AdminAccessGate.isConfigured()) {
            Toast.makeText(dialogCtx,
                    "Administrative password not configured in this build",
                    Toast.LENGTH_LONG).show();
            checkbox.setChecked(false);
            refreshAdminGateUi();
            return;
        }
        adminUnlockDialogOpen = true;
        final EditText input = new EditText(dialogCtx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Context storeCtx = prefsCtx;
        AlertDialog dialog = new AlertDialog.Builder(dialogCtx)
                .setTitle("Administrative Settings")
                .setMessage("Enter password to unlock hidden settings.")
                .setView(input)
                .setPositiveButton("Unlock", (d, which) -> {
                    if (AdminAccessGate.unlock(storeCtx, input.getText().toString())) {
                        Toast.makeText(dialogCtx, "Administrative Settings unlocked",
                                Toast.LENGTH_SHORT).show();
                        enableAdminSettings(checkbox, storeCtx);
                    } else {
                        Toast.makeText(dialogCtx, "Incorrect password", Toast.LENGTH_LONG).show();
                        checkbox.setChecked(false);
                        refreshAdminGateUi();
                    }
                })
                .setNegativeButton("Cancel", (d, which) -> {
                    checkbox.setChecked(false);
                    refreshAdminGateUi();
                })
                .setOnCancelListener(d -> {
                    checkbox.setChecked(false);
                    refreshAdminGateUi();
                })
                .create();
        dialog.setOnDismissListener(d -> adminUnlockDialogOpen = false);
        dialog.show();
    }

    private void enableAdminSettings(CheckBoxPreference checkbox, Context prefsCtx) {
        getPrefs(prefsCtx).edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, true)
                .apply();
        checkbox.setChecked(true);
        refreshAdminGateUi();
    }

    private void refreshAdminGateUi() {
        updateAdminControlsEnabled();
        updateSummaries();
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private void syncAdminSettingsGateOnResume() {
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx == null) {
            return;
        }
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        Preference adminToggle = findPreference(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED);
        if (!AdminAccessGate.isUnlocked(prefsCtx)) {
            if (prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)) {
                prefs.edit()
                        .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false)
                        .apply();
            }
            if (adminToggle instanceof CheckBoxPreference) {
                ((CheckBoxPreference) adminToggle).setChecked(false);
            }
        } else if (adminToggle instanceof CheckBoxPreference) {
            ((CheckBoxPreference) adminToggle).setChecked(
                    prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false));
        }
    }

    /** Password dialog for Tools prefs and in-plugin settings. */
    public static void promptAdministrativeUnlock(Context dialogCtx, Runnable onUnlocked) {
        Context prefsCtx = MapView.getMapView() != null
                ? MapView.getMapView().getContext()
                : dialogCtx;
        promptAdministrativeUnlock(dialogCtx, prefsCtx, onUnlocked);
    }

    /** @param prefsCtx ATAK context used for {@link AdminAccessGate} read/write */
    public static void promptAdministrativeUnlock(Context dialogCtx, Context prefsCtx,
                                                  Runnable onUnlocked) {
        if (dialogCtx == null) {
            return;
        }
        if (prefsCtx == null) {
            prefsCtx = dialogCtx;
        }
        if (!AdminAccessGate.isConfigured()) {
            Toast.makeText(dialogCtx,
                    "Administrative password not configured in this build",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final EditText input = new EditText(dialogCtx);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final Context storeCtx = prefsCtx;
        new AlertDialog.Builder(dialogCtx)
                .setTitle("Administrative Settings")
                .setMessage("Enter password to unlock hidden settings.")
                .setView(input)
                .setPositiveButton("Unlock", (dialog, which) -> {
                    if (AdminAccessGate.unlock(storeCtx, input.getText().toString())) {
                        Toast.makeText(dialogCtx, "Administrative Settings unlocked",
                                Toast.LENGTH_SHORT).show();
                        if (onUnlocked != null) {
                            onUnlocked.run();
                        }
                    } else {
                        Toast.makeText(dialogCtx, "Incorrect password", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void ensureAdminCheckboxPreferences() {
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin == null) {
            return;
        }
        forceCheckBoxPreference(admin, PREF_DISABLE_MESH_BEACON_LIMITING,
                DISABLE_MESH_BEACON_LIMITING_TITLE,
                DISABLE_MESH_BEACON_LIMITING_DESC,
                false);
        forceCheckBoxPreference(admin, NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                "Enable administrative settings",
                "",
                false);
    }

    private void forceCheckBoxPreference(PreferenceGroup parent, String key, String title,
                                         String summary, boolean defaultValue) {
        if (parent == null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        if (ctx == null) {
            return;
        }
        Preference existing = findPreference(key);
        boolean checked = defaultValue;
        Context prefsCtx = resolveSettingsContext();
        if (prefsCtx != null) {
            checked = getPrefs(prefsCtx).getBoolean(key, defaultValue);
        } else if (existing instanceof CheckBoxPreference) {
            checked = ((CheckBoxPreference) existing).isChecked();
        }
        CheckBoxPreference pref;
        if (existing instanceof CheckBoxPreference) {
            pref = (CheckBoxPreference) existing;
            pref.setTitle(title);
            pref.setSummary(summary);
        } else {
            int order = existing != null ? existing.getOrder() : Preference.DEFAULT_ORDER;
            if (existing != null && existing.getParent() != null) {
                existing.getParent().removePreference(existing);
            }
            PanCheckBoxPreference created = new PanCheckBoxPreference(ctx);
            created.setKey(key);
            created.setTitle(title);
            created.setSummary(summary);
            created.setDefaultValue(defaultValue);
            created.setOrder(order);
            created.setPersistent(true);
            parent.addPreference(created);
            pref = created;
        }
        pref.setChecked(checked);
    }

    private void wireAdministrationPreferences() {
        updateAdminControlsEnabled();
    }

    private void styleAdminLeadershipWarning() {
        Preference warning = findPreference(KEY_ADMIN_LEADERSHIP_WARNING);
        if (warning != null) {
            warning.setTitle("For Team Leadership ONLY");
            warning.setSummary("Do not use unless directed by higher");
            warning.setSelectable(false);
            warning.setEnabled(true);
            warning.setShouldDisableView(false);
            warning.setPersistent(false);
        }
    }

    private void updateAdminControlsEnabled() {
        Context ctx = resolveSettingsContext();
        SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
        boolean adminOn = prefs.getBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED, false);
        boolean unlocked = ctx != null && AdminAccessGate.isUnlocked(ctx);
        boolean enableChildren = adminOn && unlocked;

        if (adminCategory == null) {
            adminCategory = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        }
        if (adminCategory != null) {
            boolean pastAdminToggle = false;
            for (int i = 0; i < adminCategory.getPreferenceCount(); i++) {
                Preference p = adminCategory.getPreference(i);
                if (p == null) {
                    continue;
                }
                String key = p.getKey();
                if (KEY_ADMIN_LEADERSHIP_WARNING.equals(key)) {
                    p.setEnabled(true);
                    p.setShouldDisableView(false);
                    p.setSelectable(false);
                    continue;
                }
                if (KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
                    applyPreferenceEnabled(p, true);
                    continue;
                }
                if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
                    applyPreferenceEnabled(p, true);
                    pastAdminToggle = true;
                    continue;
                }
                if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
                    applyPreferenceEnabled(p, unlocked);
                    continue;
                }
                if (pastAdminToggle) {
                    applyPreferenceEnabled(p, enableChildren);
                }
            }
        } else {
            for (String key : MESH_ADMIN_GATED_PREF_KEYS) {
                if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
                    applyPreferenceEnabled(findPreference(key), unlocked);
                } else {
                    applyPreferenceEnabled(findPreference(key), enableChildren);
                }
            }
        }
        ListView list = getPreferenceListView();
        if (list != null) {
            list.post(this::applyRowStyles);
        }
    }

    private void applyPreferenceEnabled(Preference pref, boolean enabled) {
        if (pref == null) {
            return;
        }
        pref.setEnabled(enabled);
        pref.setShouldDisableView(true);
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
            String beaconValue = getListPreferenceValueLabel(PREF_BEACON_INTERVAL);
            if (beaconValue == null || beaconValue.isEmpty()) {
                beaconValue = prefs.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL)
                        + " seconds";
            }
            beaconPref.setSummary(formatSummaryWithValue(
                    BEACON_INTERVAL_DESC, beaconValue, beaconPref.isEnabled()));
        }

        updateSmartBeaconFieldSummaries();

        setCheckBoxDescriptionSummary(PREF_PING_REPLY_ENABLED, PING_REPLY_DESC);
        setSummaryWithValue(PREF_RETRY_INTERVAL_MIN, RETRY_INTERVAL_DESC,
                getListPreferenceValueLabel(PREF_RETRY_INTERVAL_MIN));
        setSummaryWithValue(PREF_RETRY_MAX, RETRY_MAX_DESC,
                getListPreferenceValueLabel(PREF_RETRY_MAX));
        setSummaryWithValue(PREF_ENCRYPTION_PASSPHRASE, ENCRYPTION_PASSPHRASE_DESC,
                maskSecret(getEditTextPreferenceValue(PREF_ENCRYPTION_PASSPHRASE)));

        setCheckBoxDescriptionSummary(PREF_DISABLE_MESH_BEACON_LIMITING,
                DISABLE_MESH_BEACON_LIMITING_DESC);
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
            return 300;
        }
    }

    public static boolean isDisableMeshBeaconLimiting(Context context) {
        if (context == null) {
            return false;
        }
        return getPrefs(context)
                .getBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, false);
    }

    public static void setDisableMeshBeaconLimiting(Context context, boolean disabled) {
        if (context == null) {
            return;
        }
        getPrefs(context).edit()
                .putBoolean(PREF_DISABLE_MESH_BEACON_LIMITING, disabled)
                .apply();
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
                .getBoolean(PREF_PING_REPLY_ENABLED, DEFAULT_PING_REPLY_ENABLED);
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

    private String getEditTextPreferenceValue(String key) {
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            String stored = getPrefs(ctx).getString(key, "");
            if (stored != null && !stored.isEmpty()) {
                return stored;
            }
        }
        Preference pref = findPreference(key);
        if (pref instanceof EditTextPreference) {
            String text = ((EditTextPreference) pref).getText();
            return text != null ? text : "";
        }
        return "";
    }

    private static String maskSecret(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "(not set)";
        }
        return "••••••••";
    }

    /**
     * Opens plugin Tool Preferences scrolled to Security.
     */
    public static void openSecuritySettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_SECURITY, context);
    }

    /**
     * Opens plugin Tool Preferences scrolled to Radio Settings.
     */
    public static void openRadioSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_RADIO, context);
    }

    /**
     * Opens plugin Tool Preferences scrolled to Beacon Settings.
     */
    public static void openBeaconSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_CAT_BEACON, context);
    }

    /**
     * Opens plugin Tool Preferences scrolled to Smart Beacon Settings.
     */
    public static void openSmartBeaconSettings(Context context) {
        launchPluginSettings(TOOL_SETTINGS_KEY, KEY_SMART_BEACON_SECTION_HEADER, context);
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
     * Administration block for Tools prefs or plugin dialog.
     */
    public static final class AdministrationUi {
        public Switch adminEnabled;
        public CheckBox disableMeshBeaconLimiting;
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

        TextView disableMeshDesc = new TextView(ctx);
        disableMeshDesc.setText(DISABLE_MESH_BEACON_LIMITING_DESC);
        disableMeshDesc.setTextColor(0xFFFFFFFF);
        disableMeshDesc.setTextSize(13);
        disableMeshDesc.setPadding(0, 8, 0, 4);
        layout.addView(disableMeshDesc);

        LinearLayout rowDisableMesh = new LinearLayout(ctx);
        rowDisableMesh.setOrientation(LinearLayout.HORIZONTAL);
        rowDisableMesh.setGravity(Gravity.CENTER_VERTICAL);
        TextView labelDisableMesh = new TextView(ctx);
        labelDisableMesh.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labelDisableMesh.setText(DISABLE_MESH_BEACON_LIMITING_TITLE);
        labelDisableMesh.setTextColor(0xFFFFFFFF);
        labelDisableMesh.setTextSize(13);
        rowDisableMesh.addView(labelDisableMesh);
        ui.disableMeshBeaconLimiting = new CheckBox(ctx);
        ui.disableMeshBeaconLimiting.setChecked(isDisableMeshBeaconLimiting(ctx));
        rowDisableMesh.addView(ui.disableMeshBeaconLimiting);
        layout.addView(rowDisableMesh);

        ui.gatedViews = new View[]{
                disableMeshDesc, ui.disableMeshBeaconLimiting
        };
        ui.adminEnabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                applyAdministrationGating(ui, isChecked));
        applyAdministrationGating(ui, ui.adminEnabled.isChecked());
        return ui;
    }

    public static void saveAdministrationFromUi(Context ctx, AdministrationUi ui) {
        if (ctx == null || ui == null) {
            return;
        }
        getPrefs(ctx).edit()
                .putBoolean(NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED,
                        ui.adminEnabled.isChecked())
                .putBoolean(PREF_DISABLE_MESH_BEACON_LIMITING,
                        ui.disableMeshBeaconLimiting != null
                                && ui.disableMeshBeaconLimiting.isChecked())
                .apply();
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

    private void attachRowStylePreDrawListener(ListView list) {
        if (list == null || rowStylePreDrawListenerAttached) {
            return;
        }
        rowStylePreDrawListenerAttached = true;
        ViewTreeObserver observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                scheduleApplyRowStyles();
                return true;
            }
        });
    }

    private ListView getPreferenceListView() {
        View root = getView();
        if (root == null) {
            return null;
        }
        return root.findViewById(android.R.id.list);
    }

    private void scheduleApplyRowStyles() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        if (pendingRowStyleApply == null) {
            pendingRowStyleApply = this::applyRowStyles;
        }
        if (pendingValueSummaryRebind == null) {
            pendingValueSummaryRebind = this::rebindAllVisibleValueSummaries;
        }
        list.removeCallbacks(pendingRowStyleApply);
        list.removeCallbacks(pendingValueSummaryRebind);
        list.post(pendingRowStyleApply);
        list.post(pendingValueSummaryRebind);
    }

    private void applyRowStyles() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            try {
                View row = list.getChildAt(i);
                Preference byPosition = resolvePreferenceForListRow(list, i);
                if (byPosition != null && isBlueSectionHeaderKey(byPosition.getKey())) {
                    stylePreferenceRow(row, byPosition);
                    continue;
                }
                Preference pillPref = resolvePillActionPreferenceForRow(list, i, row);
                if (pillPref != null) {
                    String pillLabel = pillLabelForPreference(pillPref);
                    if (!hasStablePillTitleRow(row, pillPref, pillLabel)) {
                        stylePillButtonRowForPreference(row, pillPref);
                    } else {
                        TextView pillTitle = row.findViewById(android.R.id.title);
                        if (pillTitle != null) {
                            applyPillTitleStyle(pillTitle, pillPref.isEnabled());
                        }
                        attachPreferencePillClickHandler(pillPref);
                    }
                    continue;
                }
                Preference pref = resolvePreferenceForVisibleRow(list, i, row);
                if (pref != null) {
                    stylePreferenceRow(row, pref);
                } else {
                    forceLeftAlignRow(row);
                }
            } catch (Exception e) {
                android.util.Log.w("MeshCore.Settings", "applyRowStyles failed for row " + i, e);
            }
        }
        rebindAllVisibleValueSummaries();
    }

    private Preference resolvePillActionPreferenceForRow(ListView list, int childIndex, View row) {
        if (row == null) {
            return null;
        }
        Preference byPosition = resolvePreferenceForListRow(list, childIndex);
        if (byPosition != null) {
            if (KEY_ADMIN_LEADERSHIP_WARNING.equals(byPosition.getKey())
                    || isBlueSectionHeaderKey(byPosition.getKey())) {
                return null;
            }
            if (isPillActionPreferenceKey(byPosition.getKey())) {
                return byPosition;
            }
        }
        Object keyTag = row.getTag(ROW_PREF_KEY_TAG);
        if (keyTag instanceof String && isPillActionPreferenceKey((String) keyTag)) {
            Preference keyed = findPreference((String) keyTag);
            if (keyed != null) {
                return keyed;
            }
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView != null && titleView.getText() != null) {
            CharSequence rowTitle = titleView.getText();
            if ("Restore All Defaults".contentEquals(rowTitle)) {
                return findPreference(KEY_RESTORE_ALL_DEFAULTS);
            }
            if ("Restore Defaults".contentEquals(rowTitle)) {
                return inferRestoreDefaultsPreference(list, childIndex);
            }
        }
        return null;
    }

    private Preference inferRestoreDefaultsPreference(ListView list, int childIndex) {
        Preference byPos = resolvePreferenceForListRow(list, childIndex);
        if (byPos != null) {
            String key = byPos.getKey();
            if (KEY_RESTORE_BEACON_DEFAULTS.equals(key) || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
                return byPos;
            }
        }
        int position = list.getFirstVisiblePosition() + childIndex;
        List<Preference> flat = buildFlatPreferenceList();
        for (int i = Math.min(position, flat.size() - 1); i >= 0; i--) {
            Preference pref = flat.get(i);
            if (!(pref instanceof PreferenceCategory)) {
                continue;
            }
            if (KEY_CAT_ADMINISTRATION.equals(pref.getKey())) {
                return findPreference(KEY_RESTORE_ADMIN_DEFAULTS);
            }
            if (KEY_CAT_BEACON.equals(pref.getKey())) {
                return findPreference(KEY_RESTORE_BEACON_DEFAULTS);
            }
        }
        return null;
    }

    private void refreshPreferenceList() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        android.widget.ListAdapter adapter = list.getAdapter();
        if (adapter instanceof android.widget.BaseAdapter) {
            ((android.widget.BaseAdapter) adapter).notifyDataSetChanged();
        }
        list.requestLayout();
    }

    private Preference resolvePreferenceForVisibleRow(ListView list, int childIndex, View row) {
        Preference byTitle = resolvePreferenceForRow(row);
        if (byTitle != null && isPillActionPreferenceKey(byTitle.getKey())) {
            return byTitle;
        }
        Preference byPosition = resolvePreferenceForListRow(list, childIndex);
        if (byPosition != null) {
            return byPosition;
        }
        return byTitle;
    }

    private static boolean isPillActionPreferenceKey(String key) {
        return KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key);
    }

    private static boolean isBlueSectionHeaderKey(String key) {
        return KEY_SMART_BEACON_SECTION_HEADER.equals(key);
    }

    private Context resolveContextForRow(View row) {
        Context ctx = resolveSettingsContext();
        if (ctx != null) {
            return ctx;
        }
        if (row != null && row.getContext() != null) {
            return row.getContext();
        }
        return staticPluginContext;
    }

    private boolean usesValueSummaryLine(Preference pref) {
        if (pref == null || pref.getKey() == null || pref instanceof PreferenceCategory) {
            return false;
        }
        String key = pref.getKey();
        if (pref instanceof CheckBoxPreference || isCheckboxPreferenceKey(key)) {
            return false;
        }
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(key)
                || KEY_ADMIN_LEADERSHIP_WARNING.equals(key)
                || KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
            return false;
        }
        return getDescriptionForPreferenceKey(key) != null;
    }

    private void rebindAllVisibleValueSummaries() {
        ListView list = getPreferenceListView();
        if (list == null) {
            return;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            try {
                View row = list.getChildAt(i);
                if (hasVisibleEmbeddedPillButton(row)) {
                    continue;
                }
                Preference pref = resolvePreferenceForListRow(list, i);
                if (pref == null) {
                    pref = resolvePreferenceForRow(row);
                }
                if (pref == null || !usesValueSummaryLine(pref)) {
                    continue;
                }
                TextView title = row.findViewById(android.R.id.title);
                TextView summary = row.findViewById(android.R.id.summary);
                bindStyledSummary(summary, row, pref);
                if (title != null) {
                    title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
                    title.setEnabled(true);
                    title.setAlpha(1f);
                }
            } catch (Exception e) {
                android.util.Log.w("MeshCore.Settings", "rebindAllVisibleValueSummaries failed for row "
                        + i, e);
            }
        }
    }

    private Preference resolvePreferenceForListRow(ListView list, int childIndex) {
        if (list == null) {
            return null;
        }
        int position = list.getFirstVisiblePosition() + childIndex;
        List<Preference> flat = buildFlatPreferenceList();
        if (position < 0 || position >= flat.size()) {
            return null;
        }
        return flat.get(position);
    }

    private List<Preference> buildFlatPreferenceList() {
        List<Preference> flat = new ArrayList<>();
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen != null) {
            flattenPreferenceGroupInDisplayOrder(screen, flat);
        }
        return flat;
    }

    private static void flattenPreferenceGroupInDisplayOrder(PreferenceGroup group,
                                                             List<Preference> out) {
        List<Preference> children = new ArrayList<>();
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref != null) {
                children.add(pref);
            }
        }
        java.util.Collections.sort(children, (left, right) -> {
            int order = Integer.compare(left.getOrder(), right.getOrder());
            if (order != 0) {
                return order;
            }
            return left.getTitle() != null && right.getTitle() != null
                    ? left.getTitle().toString().compareTo(right.getTitle().toString())
                    : 0;
        });
        for (Preference pref : children) {
            out.add(pref);
            if (pref instanceof PreferenceGroup) {
                flattenPreferenceGroupInDisplayOrder((PreferenceGroup) pref, out);
            }
        }
    }

    private Preference resolvePreferenceForRow(View row) {
        if (row == null) {
            return null;
        }
        Object keyTag = row.getTag(ROW_PREF_KEY_TAG);
        if (keyTag instanceof String) {
            Preference keyed = findPreference((String) keyTag);
            if (keyed != null) {
                return keyed;
            }
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView != null && titleView.getText() != null) {
            CharSequence rowTitle = titleView.getText();
            if ("Restore All Defaults".contentEquals(rowTitle)) {
                return findPreference(KEY_RESTORE_ALL_DEFAULTS);
            }
            if ("Restore Defaults".contentEquals(rowTitle)) {
                ListView list = getPreferenceListView();
                if (list != null) {
                    for (int i = 0; i < list.getChildCount(); i++) {
                        if (list.getChildAt(i) == row) {
                            Preference inferred = inferRestoreDefaultsPreference(list, i);
                            if (inferred != null) {
                                return inferred;
                            }
                            break;
                        }
                    }
                }
                return null;
            }
            if (titleView.getVisibility() == View.VISIBLE) {
                return findPreferenceByTitle(rowTitle.toString());
            }
        }
        return null;
    }

    private static boolean titleMatchesPreference(View row, Preference pref) {
        if (row == null || pref == null || pref.getTitle() == null) {
            return false;
        }
        TextView titleView = row.findViewById(android.R.id.title);
        if (titleView == null || titleView.getText() == null) {
            return false;
        }
        return titleView.getText().toString().equals(pref.getTitle().toString());
    }

    private static boolean hasVisibleEmbeddedPillButton(View row) {
        Button pill = findEmbeddedPillButton(row);
        return pill != null && pill.getVisibility() == View.VISIBLE;
    }

    private static Button findEmbeddedPillButton(View root) {
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Button && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())) {
                return (Button) child;
            }
            if (child instanceof ViewGroup) {
                Button nested = findEmbeddedPillButton(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static String pillLabelForPreference(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return "";
        }
        switch (pref.getKey()) {
            case KEY_RESTORE_ALL_DEFAULTS:
                return "Restore All Defaults";
            case KEY_RESTORE_BEACON_DEFAULTS:
            case KEY_RESTORE_ADMIN_DEFAULTS:
                return "Restore Defaults";
            default:
                return pref.getTitle() != null ? pref.getTitle().toString() : "";
        }
    }

    private static boolean hasStablePillTitleRow(View row, Preference pref, String label) {
        if (row == null || pref == null || pref.getKey() == null || label == null) {
            return false;
        }
        if (!pref.getKey().equals(row.getTag(ROW_PREF_KEY_TAG))) {
            return false;
        }
        TextView title = row.findViewById(android.R.id.title);
        return title != null
                && title.getVisibility() == View.VISIBLE
                && label.contentEquals(title.getText())
                && title.getBackground() != null;
    }

    private void attachPreferencePillClickHandler(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return;
        }
        final String key = pref.getKey();
        pref.setOnPreferenceClickListener(preference -> {
            dispatchPillActionClick(key);
            return true;
        });
        preferenceClickHandlers.put(key, preference -> {
            dispatchPillActionClick(key);
            return true;
        });
    }

    private Preference findPreferenceByTitle(String title) {
        if (title == null || title.isEmpty()) {
            return null;
        }
        android.preference.PreferenceScreen screen = getPreferenceScreen();
        if (screen == null) {
            return null;
        }
        return findPreferenceByTitle(screen, title);
    }

    private Preference findPreferenceByTitle(PreferenceGroup group, String title) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference pref = group.getPreference(i);
            if (pref.getTitle() != null && title.equals(pref.getTitle().toString())) {
                return pref;
            }
            if (pref instanceof PreferenceGroup) {
                Preference nested = findPreferenceByTitle((PreferenceGroup) pref, title);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private void applyGatedRowVisualState(View row, Preference pref) {
        if (row == null || pref == null) {
            return;
        }
        row.setAlpha(pref.isEnabled() ? 1f : DISABLED_ROW_ALPHA);
    }

    private void stylePreferenceRow(View row, Preference pref) {
        if (row == null || pref == null) {
            stripRowLeadingInset(row);
            return;
        }
        if (pref.getKey() != null) {
            row.setTag(ROW_PREF_KEY_TAG, pref.getKey());
        }
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (pref instanceof PreferenceCategory) {
            styleCategoryRow(row, title, summary);
            return;
        }
        if (KEY_RESTORE_BEACON_DEFAULTS.equals(pref.getKey())) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
            return;
        }
        if (KEY_RESTORE_ADMIN_DEFAULTS.equals(pref.getKey())) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
            return;
        }
        if (KEY_RESTORE_ALL_DEFAULTS.equals(pref.getKey())) {
            stylePillActionButtonRow(row, pref, "Restore All Defaults");
            row.setAlpha(1f);
            return;
        }
        forceLeftAlignRow(row);
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(pref.getKey())) {
            styleBlueSectionHeaderRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        if (KEY_ADMIN_LEADERSHIP_WARNING.equals(pref.getKey())) {
            styleAdminLeadershipWarningRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(pref.getKey())) {
            styleCheckBoxPreferenceRow(row, pref);
            row.setAlpha(1f);
            return;
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(pref.getKey())) {
            styleCheckBoxPreferenceRow(row, pref);
            applyGatedRowVisualState(row, pref);
            return;
        }
        if (isCheckboxPreference(pref)) {
            styleCheckBoxPreferenceRow(row, pref);
            applyGatedRowVisualState(row, pref);
            return;
        }
        resetStandardPreferenceRow(row);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT);
            title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            bindStyledSummary(summary, row, pref);
        }
        applyGatedRowVisualState(row, pref);
    }

    private void styleCategoryRow(View row, TextView title, TextView summary) {
        Context ctx = resolveSettingsContext();
        if (ctx == null && row != null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
        if (title != null) {
            styleCategoryTitle(title);
            centerTitleInRow(title);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
    }

    private void styleCenteredPillButtonRow(View row) {
        if (row == null) {
            return;
        }
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setGravity(Gravity.CENTER);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        }
    }

    private void stripRowLeadingInset(View row) {
        if (row == null) {
            return;
        }
        removeHiddenEmbeddedPillButtons(row);
        Context ctx = resolveSettingsContext();
        if (ctx == null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());

        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        }

        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        View contentColumn = findRowContentColumn(row, title);
        collapseLeadingRowSlots(row, contentColumn);
        collapseIconFrameById(row, ctx);
        hidePanListArrowIndicator(row);

        if (contentColumn != null) {
            zeroHorizontalInset(contentColumn);
            if (contentColumn instanceof LinearLayout) {
                ((LinearLayout) contentColumn).setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            }
            ViewGroup.LayoutParams colLp = contentColumn.getLayoutParams();
            if (colLp != null) {
                zeroHorizontalMargins(colLp);
                if (colLp instanceof LinearLayout.LayoutParams && row instanceof LinearLayout) {
                    LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) colLp;
                    llp.width = 0;
                    llp.weight = 1f;
                    llp.gravity = Gravity.START;
                }
                contentColumn.setLayoutParams(colLp);
            }
        }

        resetStandardRowTitle(title);
        resetStandardSummary(summary);
        clearNonCheckboxWidgets(row);
    }

    private static View findRowContentColumn(View row, TextView title) {
        if (row == null || title == null) {
            return null;
        }
        View column = title;
        while (column.getParent() instanceof View) {
            View parent = (View) column.getParent();
            if (parent == row) {
                return column;
            }
            column = parent;
        }
        return title;
    }

    private static void collapseLeadingRowSlots(View row, View contentColumn) {
        if (!(row instanceof ViewGroup) || contentColumn == null) {
            return;
        }
        ViewGroup rowGroup = (ViewGroup) row;
        for (int i = 0; i < rowGroup.getChildCount(); i++) {
            View child = rowGroup.getChildAt(i);
            if (child == contentColumn) {
                break;
            }
            collapseHorizontalSlot(child);
        }
    }

    private static void collapseIconFrameById(View row, Context ctx) {
        if (row == null || ctx == null) {
            return;
        }
        int iconFrameId = ctx.getResources().getIdentifier("icon_frame", "id", "android");
        if (iconFrameId != 0) {
            collapseHorizontalSlot(row.findViewById(iconFrameId));
        }
        collapseHorizontalSlot(row.findViewById(android.R.id.icon));
    }

    private static void hidePanListArrowIndicator(View row) {
        if (!(row instanceof ViewGroup)) {
            return;
        }
        ViewGroup rowGroup = (ViewGroup) row;
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        for (int i = 0; i < rowGroup.getChildCount(); i++) {
            View child = rowGroup.getChildAt(i);
            if (child instanceof ImageView
                    && child.getId() != android.R.id.icon
                    && child != widgetFrame
                    && !(child instanceof ViewGroup)) {
                collapseHorizontalSlot(child);
            }
        }
    }

    private void forceLeftAlignRow(View row) {
        stripRowLeadingInset(row);
    }

    private static void zeroHorizontalInset(View view) {
        if (view == null) {
            return;
        }
        view.setPaddingRelative(0, view.getPaddingTop(), 0, view.getPaddingBottom());
        view.setPadding(0, view.getPaddingTop(), 0, view.getPaddingBottom());
        zeroHorizontalMargins(view.getLayoutParams());
    }

    private static void zeroHorizontalMargins(ViewGroup.LayoutParams lp) {
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
            mlp.setMarginStart(0);
            mlp.setMarginEnd(0);
            mlp.leftMargin = 0;
            mlp.rightMargin = 0;
        }
    }

    private static void collapseHorizontalSlot(View view) {
        if (view == null) {
            return;
        }
        view.setVisibility(View.GONE);
        view.setMinimumWidth(0);
        view.setMinimumHeight(0);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = 0;
            lp.height = 0;
            zeroHorizontalMargins(lp);
            view.setLayoutParams(lp);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collapseHorizontalSlot(group.getChildAt(i));
            }
        }
    }

    private void styleAdminLeadershipWarningRow(View row, Preference pref) {
        removeEmbeddedPillButtons(row);
        resetStandardPreferenceRow(row);
        Context ctx = resolveSettingsContext();
        if (ctx == null && row != null) {
            ctx = row.getContext();
        }
        int edgePad = dp(ctx, 16);
        row.setPaddingRelative(edgePad, row.getPaddingTop(), edgePad, row.getPaddingBottom());
        row.setClickable(false);
        row.setFocusable(false);
        row.setBackground(null);
        if (row instanceof LinearLayout) {
            ((LinearLayout) row).setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        View contentColumn = findRowContentColumn(row, title);
        if (contentColumn instanceof LinearLayout) {
            ((LinearLayout) contentColumn).setGravity(
                    Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        }
        if (title != null) {
            clearPillTitleChrome(title);
            CharSequence titleText = pref != null ? pref.getTitle() : title.getText();
            if (titleText != null) {
                title.setText(titleText);
            }
            centerTitleInRow(title);
            title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(COLOR_WHITE);
            title.setEnabled(true);
            title.setAlpha(1f);
        }
        if (summary != null) {
            clearPillTitleChrome(summary);
            summary.setGravity(Gravity.CENTER_HORIZONTAL);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            ViewGroup.LayoutParams lp = summary.getLayoutParams();
            if (lp != null) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                summary.setLayoutParams(lp);
            }
            CharSequence summaryText = buildStyledSummaryForPreference(pref);
            if (summaryText == null || summaryText.length() == 0) {
                summaryText = pref != null ? pref.getSummary() : null;
            }
            if (summaryText != null && summaryText.length() > 0) {
                summary.setText(summaryText, TextView.BufferType.SPANNABLE);
                summary.setVisibility(View.VISIBLE);
                summary.setEnabled(true);
                summary.setAlpha(1f);
                summary.setTextColor(COLOR_WHITE);
            }
        }
    }

    /** Strip pill-button chrome recycled list rows may retain on the title view. */
    private static void clearPillTitleChrome(TextView view) {
        if (view == null) {
            return;
        }
        view.setBackground(null);
        view.setMinHeight(0);
        view.setPadding(0, 0, 0, 0);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            view.setLayoutParams(lp);
        }
    }

    private void styleCheckBoxPreferenceRow(View row, Preference pref) {
        resetStandardPreferenceRow(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            resetStandardRowTitle(title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT);
            title.setTextColor(pref.isEnabled() ? COLOR_WHITE : COLOR_DISABLED_GREY);
        }
        if (summary != null) {
            resetStandardSummary(summary);
            bindStyledSummary(summary, row, pref);
        }
        ensureCheckBoxWidgetVisible(row, pref);
    }

    private void ensureCheckBoxWidgetVisible(View row, Preference pref) {
        if (!(pref instanceof CheckBoxPreference) || row == null) {
            return;
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        widgetFrame.setVisibility(View.VISIBLE);
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        CheckBox box = null;
        for (int i = 0; i < widgetGroup.getChildCount(); i++) {
            View child = widgetGroup.getChildAt(i);
            if (child instanceof CheckBox && EMBEDDED_CHECKBOX_TAG.equals(child.getTag())) {
                box = (CheckBox) child;
                break;
            }
        }
        if (box == null) {
            widgetGroup.removeAllViews();
            box = new CheckBox(row.getContext());
            box.setTag(EMBEDDED_CHECKBOX_TAG);
            box.setFocusable(false);
            box.setClickable(false);
            widgetGroup.addView(box, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        CheckBoxPreference checkPref = (CheckBoxPreference) pref;
        box.setChecked(checkPref.isChecked());
        box.setEnabled(pref.isEnabled());
        try {
            box.setButtonTintList(ColorStateList.valueOf(
                    pref.isEnabled() ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY));
        } catch (Exception ignored) {
        }
    }

    private void styleBlueSectionHeaderRow(View row, Preference pref) {
        removeEmbeddedPillButtons(row);
        resetStandardPreferenceRow(row);
        row.setBackground(null);
        row.setClickable(false);
        row.setFocusable(false);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        if (title != null) {
            clearPillTitleChrome(title);
            resetStandardRowTitle(title);
            if (pref != null && pref.getTitle() != null) {
                title.setText(pref.getTitle());
            }
            title.setTextColor(COLOR_STD_BLUE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PREFERENCE_TITLE_TEXT_SP);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setEnabled(true);
            title.setAlpha(1f);
        }
        if (summary != null) {
            clearPillTitleChrome(summary);
            resetStandardSummary(summary);
            summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            bindStyledSummary(summary, row, pref);
            summary.setVisibility(View.VISIBLE);
            summary.setEnabled(true);
            summary.setAlpha(1f);
        }
    }

    private void resetStandardPreferenceRow(View row) {
        if (row == null) {
            return;
        }
        restoreStandardRowChildVisibility(row);
        stripRowLeadingInset(row);
    }

    private static void clearNonCheckboxWidgets(View row) {
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        for (int i = 0; i < widgetGroup.getChildCount(); i++) {
            View child = widgetGroup.getChildAt(i);
            if (child instanceof CheckBox && EMBEDDED_CHECKBOX_TAG.equals(child.getTag())) {
                return;
            }
        }
        for (int i = widgetGroup.getChildCount() - 1; i >= 0; i--) {
            View child = widgetGroup.getChildAt(i);
            if (!(child instanceof CompoundButton)) {
                widgetGroup.removeViewAt(i);
            }
        }
        if (widgetGroup.getChildCount() == 0) {
            collapseHorizontalSlot(widgetFrame);
        }
    }

    private static void restoreStandardRowChildVisibility(View row) {
        if (!(row instanceof ViewGroup)) {
            return;
        }
        removeHiddenEmbeddedPillButtons(row);
        ViewGroup rowGroup = (ViewGroup) row;
        for (int i = 0; i < rowGroup.getChildCount(); i++) {
            View child = rowGroup.getChildAt(i);
            if (child instanceof Button && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())) {
                rowGroup.removeViewAt(i);
                i--;
                continue;
            }
            child.setVisibility(View.VISIBLE);
        }
    }

    private void stylePillButtonRowForPreference(View row, Preference pref) {
        if (pref == null) {
            styleCenteredPillButtonRow(row);
            return;
        }
        if (pref.getKey() != null) {
            row.setTag(ROW_PREF_KEY_TAG, pref.getKey());
        }
        String key = pref.getKey();
        if (KEY_RESTORE_BEACON_DEFAULTS.equals(key)) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
        } else if (KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
            stylePillActionButtonRow(row, pref, "Restore Defaults");
            row.setAlpha(1f);
        } else if (KEY_RESTORE_ALL_DEFAULTS.equals(key)) {
            stylePillActionButtonRow(row, pref, "Restore All Defaults");
            row.setAlpha(1f);
        } else {
            styleCenteredPillButtonRow(row);
        }
    }

    private void stylePillActionButtonRow(View row, Preference pref, String label) {
        Context ctx = resolveContextForRow(row);
        if (ctx == null || row == null || pref == null || pref.getKey() == null) {
            return;
        }
        removeEmbeddedPillButtons(row);
        row.setTag(ROW_PREF_KEY_TAG, pref.getKey());

        int edgePad = dp(ctx, 16);
        int vMargin = dp(ctx, PILL_BUTTON_ROW_MARGIN_VERTICAL_DP);
        row.setPaddingRelative(edgePad, vMargin, edgePad, vMargin);
        row.setBackgroundColor(0);

        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        View contentColumn = findRowContentColumn(row, title);
        collapseLeadingRowSlots(row, contentColumn);
        collapseIconFrameById(row, ctx);
        if (contentColumn instanceof LinearLayout) {
            ((LinearLayout) contentColumn).setGravity(Gravity.CENTER_HORIZONTAL);
        }
        if (summary != null) {
            summary.setVisibility(View.GONE);
        }
        View icon = row.findViewById(android.R.id.icon);
        if (icon != null) {
            icon.setVisibility(View.GONE);
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (widgetFrame != null) {
            widgetFrame.setVisibility(View.GONE);
        }
        if (title != null) {
            title.setVisibility(View.VISIBLE);
            title.setText(label);
            applyPillTitleStyle(title, pref.isEnabled());
        }
        styleCenteredPillButtonRow(row);
        pref.setSelectable(true);
        pref.setPersistent(false);
        attachPreferencePillClickHandler(pref);
    }

    private static void applyPillTitleStyle(TextView title, boolean enabled) {
        if (title == null) {
            return;
        }
        Context ctx = title.getContext();
        title.setTextColor(COLOR_WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_BUTTON_TEXT_SP);
        title.setTypeface(Typeface.DEFAULT);
        title.setMinHeight(dp(ctx, PILL_BUTTON_MIN_HEIGHT_DP));
        int hPad = dp(ctx, PILL_BUTTON_PAD_HORIZONTAL_DP);
        int vPad = dp(ctx, PILL_BUTTON_PAD_VERTICAL_DP);
        title.setPadding(hPad, vPad, hPad, vPad);
        title.setBackground(buildPillButtonBackground(ctx,
                enabled ? COLOR_PILL_BUTTON_PRIMARY : COLOR_DISABLED_GREY,
                enabled ? COLOR_PILL_BUTTON_STROKE : COLOR_DISABLED_GREY,
                enabled ? 2 : 0));
        title.setGravity(Gravity.CENTER);
        title.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(lp);
        }
    }

    private static void removeEmbeddedPillButtons(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof Button && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())) {
                group.removeViewAt(i);
            } else {
                removeEmbeddedPillButtons(child);
            }
        }
    }

    private static void removeHiddenEmbeddedPillButtons(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) root;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child instanceof Button
                    && EMBEDDED_PILL_BUTTON_TAG.equals(child.getTag())
                    && child.getVisibility() != View.VISIBLE) {
                group.removeViewAt(i);
            } else if (child instanceof ViewGroup) {
                removeHiddenEmbeddedPillButtons(child);
            }
        }
    }

    private static GradientDrawable buildPillButtonBackground(Context ctx, int fillColor,
                                                     int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(ctx, PILL_CORNER_RADIUS_DP));
        drawable.setColor(fillColor);
        if (strokeDp > 0) {
            drawable.setStroke(dp(ctx, strokeDp), strokeColor);
        }
        return drawable;
    }

    private static void applyPillButtonStyle(Button button, boolean enabled) {
        if (button == null) {
            return;
        }
        Context ctx = button.getContext();
        button.setTextColor(COLOR_WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, PILL_BUTTON_TEXT_SP);
        button.setTypeface(Typeface.DEFAULT);
        button.setAllCaps(false);
        button.setMinHeight(dp(ctx, PILL_BUTTON_MIN_HEIGHT_DP));
        button.setPadding(dp(ctx, PILL_BUTTON_PAD_HORIZONTAL_DP), dp(ctx, PILL_BUTTON_PAD_VERTICAL_DP),
                dp(ctx, PILL_BUTTON_PAD_HORIZONTAL_DP), dp(ctx, PILL_BUTTON_PAD_VERTICAL_DP));
        button.setBackground(buildPillButtonBackground(ctx,
                enabled ? COLOR_PILL_BUTTON_PRIMARY : COLOR_DISABLED_GREY,
                enabled ? COLOR_PILL_BUTTON_STROKE : COLOR_DISABLED_GREY,
                enabled ? 2 : 0));
    }

    private void clearRedundantListValueWidget(View row, Preference pref) {
        if (!(pref instanceof ListPreference)) {
            return;
        }
        View widgetFrame = row.findViewById(android.R.id.widget_frame);
        if (!(widgetFrame instanceof ViewGroup)) {
            return;
        }
        ViewGroup widgetGroup = (ViewGroup) widgetFrame;
        for (int i = widgetGroup.getChildCount() - 1; i >= 0; i--) {
            View child = widgetGroup.getChildAt(i);
            if (!(child instanceof CompoundButton)) {
                widgetGroup.removeViewAt(i);
            }
        }
    }

    private void centerTitleInRow(TextView title) {
        if (title == null) {
            return;
        }
        title.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(lp);
        }
    }

    private void resetStandardRowTitle(TextView title) {
        if (title == null) {
            return;
        }
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
            }
            title.setLayoutParams(lp);
        }
    }

    private void resetStandardSummary(TextView summary) {
        if (summary == null) {
            return;
        }
        summary.setGravity(Gravity.START);
        summary.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        ViewGroup.LayoutParams lp = summary.getLayoutParams();
        if (lp != null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMarginStart(0);
            }
            summary.setLayoutParams(lp);
        }
    }

    private void styleCategoryTitle(TextView title) {
        title.setTextColor(COLOR_CATEGORY_YELLOW);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, CATEGORY_TITLE_TEXT_SP);
        title.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private void refreshSummaryIfMatched(TextView summary, View row, Preference pref) {
        bindStyledSummary(summary, row, pref);
    }

    private void bindStyledSummary(TextView summary, View row, Preference pref) {
        if (summary == null || pref == null) {
            return;
        }
        if (Boolean.TRUE.equals(summary.getTag(SUMMARY_REBIND_TAG))) {
            return;
        }
        summary.setTag(SUMMARY_REBIND_TAG, Boolean.TRUE);
        try {
            if (pref.getKey() != null && row != null) {
                row.setTag(ROW_PREF_KEY_TAG, pref.getKey());
            }
            CharSequence styledSummary = buildStyledSummaryForPreference(pref);
            if (styledSummary == null || styledSummary.length() == 0) {
                styledSummary = pref.getSummary();
            }
            if (styledSummary == null || styledSummary.length() == 0) {
                return;
            }
            if (!styledSummary.equals(pref.getSummary())) {
                pref.setSummary(styledSummary);
            }
            summary.setText(styledSummary, TextView.BufferType.SPANNABLE);
            summary.setVisibility(View.VISIBLE);
            summary.setEnabled(true);
            summary.setAlpha(1f);
            ensureSummaryTextWatcher(summary, row);
        } finally {
            summary.setTag(SUMMARY_REBIND_TAG, null);
        }
    }

    private void ensureSummaryTextWatcher(TextView summary, View row) {
        if (summary == null || row == null) {
            return;
        }
        if (summary.getTag(SUMMARY_WATCHER_TAG) instanceof TextWatcher) {
            return;
        }
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (Boolean.TRUE.equals(summary.getTag(SUMMARY_REBIND_TAG))) {
                    return;
                }
                if (summaryHasGreenValueLine(s)) {
                    return;
                }
                Object keyTag = row.getTag(ROW_PREF_KEY_TAG);
                if (!(keyTag instanceof String)) {
                    return;
                }
                Preference boundPref = findPreference((String) keyTag);
                if (boundPref == null || !usesValueSummaryLine(boundPref)) {
                    return;
                }
                bindStyledSummary(summary, row, boundPref);
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        };
        summary.addTextChangedListener(watcher);
        summary.setTag(SUMMARY_WATCHER_TAG, watcher);
    }

    private static boolean summaryHasGreenValueLine(CharSequence text) {
        if (!(text instanceof Spanned)) {
            return false;
        }
        Spanned spanned = (Spanned) text;
        ForegroundColorSpan[] spans = spanned.getSpans(0, spanned.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) {
            if (span.getForegroundColor() == COLOR_VALUE_GREEN) {
                return true;
            }
        }
        return false;
    }

    private void normalizeBeaconSection() {
        PreferenceCategory beacon = (PreferenceCategory) findPreference(KEY_CAT_BEACON);
        if (beacon == null) {
            return;
        }
        for (String key : REMOVE_FROM_BEACON_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null && pref.getParent() == beacon) {
                beacon.removePreference(pref);
            }
        }
        ensureSmartBeaconSectionHeader(beacon);
    }

    private void normalizeAdministrationSection() {
        PreferenceCategory admin = (PreferenceCategory) findPreference(KEY_CAT_ADMINISTRATION);
        if (admin == null) {
            return;
        }
        for (String key : REMOVE_FROM_ADMIN_KEYS) {
            Preference pref = findPreference(key);
            if (pref != null && pref.getParent() == admin) {
                admin.removePreference(pref);
            }
        }
        removeObsoleteMeshBeaconAdminRows(admin);
    }

    /** Drop cached mesh-beacon warning/toggles ATAK may retain after plugin updates. */
    private void removeObsoleteMeshBeaconAdminRows(PreferenceCategory admin) {
        if (admin == null) {
            return;
        }
        List<Preference> remove = new ArrayList<>();
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (pref == null) {
                continue;
            }
            String key = pref.getKey();
            if (key != null) {
                for (String obsoleteKey : OBSOLETE_MESH_BEACON_PREF_KEYS) {
                    if (obsoleteKey.equals(key)) {
                        remove.add(pref);
                        break;
                    }
                }
            }
            if (!remove.contains(pref) && isObsoleteAdminPreference(pref)) {
                remove.add(pref);
            }
        }
        for (Preference pref : remove) {
            admin.removePreference(pref);
        }
    }

    private void ensureSmartBeaconSectionHeader(PreferenceCategory beacon) {
        if (beacon == null) {
            return;
        }
        Context ctx = getActivity() != null ? getActivity() : staticPluginContext;
        Preference header = findPreference(KEY_SMART_BEACON_SECTION_HEADER);
        if (header == null) {
            if (ctx == null) {
                return;
            }
            header = new Preference(ctx);
            header.setKey(KEY_SMART_BEACON_SECTION_HEADER);
            header.setTitle("Smart Beacon Settings");
            header.setSummary(SMART_BEACON_SECTION_DESC);
            header.setSelectable(false);
            header.setPersistent(false);
            header.setEnabled(true);
            header.setShouldDisableView(false);
        } else if (header.getParent() != null && header.getParent() != beacon) {
            header.getParent().removePreference(header);
        }
        if (header.getParent() == beacon) {
            beacon.removePreference(header);
        }
        Preference lowSpeed = findPreference(SmartBeacon.KEY_LOW_SPEED);
        int order;
        if (lowSpeed != null && lowSpeed.getParent() == beacon) {
            order = lowSpeed.getOrder() - 1;
        } else {
            Preference beaconInterval = findPreference(PREF_BEACON_INTERVAL);
            order = beaconInterval != null
                    ? beaconInterval.getOrder() + 1
                    : Preference.DEFAULT_ORDER;
        }
        header.setOrder(order);
        beacon.addPreference(header);
    }

    private void dedupeAdminPreferencesByTitle(PreferenceCategory admin, String title,
                                                      String keepKey) {
        if (admin == null || title == null) {
            return;
        }
        List<Preference> remove = new ArrayList<>();
        for (int i = 0; i < admin.getPreferenceCount(); i++) {
            Preference pref = admin.getPreference(i);
            if (pref == null || pref.getTitle() == null) {
                continue;
            }
            if (!title.contentEquals(pref.getTitle())) {
                continue;
            }
            if (keepKey != null && keepKey.equals(pref.getKey())) {
                continue;
            }
            remove.add(pref);
        }
        for (Preference pref : remove) {
            admin.removePreference(pref);
        }
    }

    private void dispatchPillActionClick(String prefKey) {
        if (prefKey == null) {
            return;
        }
        Preference pref = findPreference(prefKey);
        if (pref != null && !pref.isEnabled()) {
            return;
        }
        switch (prefKey) {
            case KEY_RESTORE_ALL_DEFAULTS:
                showRestoreConfirmDialog("Restore All Defaults",
                        () -> restoreAllDefaults(resolveSettingsContext()));
                break;
            case KEY_RESTORE_BEACON_DEFAULTS:
                showRestoreConfirmDialog("Restore Defaults",
                        () -> restoreBeaconDefaults(resolveSettingsContext()));
                break;
            case KEY_RESTORE_ADMIN_DEFAULTS:
                showRestoreConfirmDialog("Restore Defaults",
                        () -> restoreAdminDefaults(resolveSettingsContext()));
                break;
            default:
                break;
        }
    }

    private static CharSequence formatSummaryWithValue(String description, String value) {
        return formatSummaryWithValue(description, value, true);
    }

    private static CharSequence formatDescriptionOnly(String description) {
        return formatDescriptionOnly(description, true);
    }

    private static CharSequence formatDescriptionOnly(String description, boolean enabled) {
        if (description == null) {
            description = "";
        }
        int color = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        SpannableStringBuilder sb = new SpannableStringBuilder(description);
        sb.setSpan(new ForegroundColorSpan(color), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static CharSequence formatSummaryWithValue(String description, String value,
                                                       boolean enabled) {
        String display = value;
        if (display == null || display.trim().isEmpty()) {
            display = "(not set)";
        } else {
            display = display.trim();
        }
        SpannableStringBuilder sb = new SpannableStringBuilder(description);
        int descriptionColor = enabled ? COLOR_WHITE : COLOR_DISABLED_GREY;
        int valueColor = enabled ? COLOR_VALUE_GREEN : COLOR_DISABLED_GREY;
        sb.setSpan(new ForegroundColorSpan(descriptionColor), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), 0, description.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int valueStart = sb.length();
        sb.append('\n').append(display);
        sb.setSpan(new AbsoluteSizeSpan(PREFERENCE_TITLE_TEXT_SP, true), valueStart, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(valueColor), valueStart, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static Context resolveAtakContext() {
        MapView mv = MapView.getMapView();
        if (mv != null && mv.getContext() != null) {
            return mv.getContext();
        }
        return null;
    }

    private String getCheckBoxPreferenceValueLabel(String key, boolean defaultChecked) {
        Preference pref = findPreference(key);
        boolean checked = defaultChecked;
        if (pref instanceof CheckBoxPreference) {
            checked = ((CheckBoxPreference) pref).isChecked();
        } else {
            Context ctx = resolveSettingsContext();
            if (ctx != null) {
                checked = getPrefs(ctx).getBoolean(key, defaultChecked);
            }
        }
        return checked ? "On" : "Off";
    }

    private CharSequence buildStyledSummaryForPreference(Preference pref) {
        if (pref == null || pref.getKey() == null) {
            return null;
        }
        String key = pref.getKey();
        if (KEY_SMART_BEACON_SECTION_HEADER.equals(key)) {
            CharSequence summary = pref.getSummary();
            return formatDescriptionOnly(summary != null ? summary.toString() : "", true);
        }
        if (KEY_ADMIN_LEADERSHIP_WARNING.equals(key)) {
            CharSequence summary = pref.getSummary();
            return formatDescriptionOnly(summary != null ? summary.toString() : "", true);
        }
        if (KEY_RESTORE_BEACON_DEFAULTS.equals(key)
                || KEY_RESTORE_ALL_DEFAULTS.equals(key)
                || KEY_RESTORE_ADMIN_DEFAULTS.equals(key)) {
            return pref.getSummary();
        }
        if (pref instanceof CheckBoxPreference || isCheckboxPreferenceKey(key)) {
            String description = getDescriptionForPreferenceKey(key);
            if (description != null) {
                return formatDescriptionOnly(description, pref.isEnabled());
            }
        }
        String description = getDescriptionForPreferenceKey(key);
        if (description == null) {
            CharSequence summary = pref.getSummary();
            return summary != null ? summary : "";
        }
        return formatSummaryWithValue(description, resolvePreferenceDisplayValue(key),
                pref.isEnabled());
    }

    private String getDescriptionForPreferenceKey(String key) {
        if (PREF_BEACON_INTERVAL.equals(key)) {
            return BEACON_INTERVAL_DESC;
        }
        if (SmartBeacon.KEY_LOW_SPEED.equals(key)) {
            return "Below this speed the slowest rate is used";
        }
        if (SmartBeacon.KEY_HIGH_SPEED.equals(key)) {
            return "Above this speed the fastest rate is used";
        }
        if (SmartBeacon.KEY_SLOW_RATE.equals(key)) {
            return "Max time between beacons when slow or stopped";
        }
        if (SmartBeacon.KEY_FAST_RATE.equals(key)) {
            return "Min time between beacons when moving fast";
        }
        if (SmartBeacon.KEY_MIN_TURN_TIME.equals(key)) {
            return "Minimum delay between corner-pegging beacons";
        }
        if (SmartBeacon.KEY_TURN_THRESHOLD.equals(key)) {
            return "Heading change needed to trigger an early beacon";
        }
        if (SmartBeacon.KEY_TURN_SLOPE.equals(key)) {
            return "Scales turn sensitivity with speed (higher = less sensitive at low speed)";
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return "Automatically reply to incoming pings with your position";
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key)) {
            return "How long to wait before retransmitting an unacknowledged message";
        }
        if (PREF_RETRY_MAX.equals(key)) {
            return "Number of retransmit attempts before declaring delivery failure. "
                    + "Will re-attempt upon receipt of beacon";
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            return "Same value on every radio that uses RF encryption";
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
            return DISABLE_MESH_BEACON_LIMITING_DESC;
        }
        return null;
    }

    private String resolvePreferenceDisplayValue(String key) {
        Context ctx = resolveAtakContext();
        if (ctx == null) {
            ctx = resolveSettingsContext();
        }
        if (PREF_BEACON_INTERVAL.equals(key)) {
            String label = getListPreferenceValueLabel(key);
            if (label == null || label.isEmpty()) {
                SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
                label = prefs.getString(PREF_BEACON_INTERVAL, DEFAULT_BEACON_INTERVAL) + " seconds";
            }
            return label;
        }
        if (SmartBeacon.KEY_LOW_SPEED.equals(key) && ctx != null) {
            return SmartBeacon.getLowSpeed(ctx) + " mph";
        }
        if (SmartBeacon.KEY_HIGH_SPEED.equals(key) && ctx != null) {
            return SmartBeacon.getHighSpeed(ctx) + " mph";
        }
        if (SmartBeacon.KEY_SLOW_RATE.equals(key) && ctx != null) {
            return SmartBeacon.getSlowRate(ctx) + " s";
        }
        if (SmartBeacon.KEY_FAST_RATE.equals(key) && ctx != null) {
            return SmartBeacon.getFastRate(ctx) + " s";
        }
        if (SmartBeacon.KEY_MIN_TURN_TIME.equals(key) && ctx != null) {
            return SmartBeacon.getMinTurnTime(ctx) + " s";
        }
        if (SmartBeacon.KEY_TURN_THRESHOLD.equals(key) && ctx != null) {
            return SmartBeacon.getTurnThreshold(ctx) + "°";
        }
        if (SmartBeacon.KEY_TURN_SLOPE.equals(key) && ctx != null) {
            return String.valueOf(SmartBeacon.getTurnSlope(ctx));
        }
        if (PREF_PING_REPLY_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, DEFAULT_PING_REPLY_ENABLED);
        }
        if (PREF_DISABLE_MESH_BEACON_LIMITING.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (NetSlotConfig.PREF_ADMIN_SETTINGS_ENABLED.equals(key)) {
            return getCheckBoxPreferenceValueLabel(key, false);
        }
        if (PREF_RETRY_INTERVAL_MIN.equals(key) || PREF_RETRY_MAX.equals(key)) {
            return getListPreferenceValueLabel(key);
        }
        if (PREF_ENCRYPTION_PASSPHRASE.equals(key)) {
            return maskSecret(getEditTextPreferenceValue(key));
        }
        return "";
    }

    private void setCheckBoxDescriptionSummary(String key, String description) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(formatDescriptionOnly(description, pref.isEnabled()));
        }
    }

    private void setSummaryWithValue(String key, String description, String value) {
        Preference pref = findPreference(key);
        if (pref != null) {
            pref.setSummary(formatSummaryWithValue(description, value, pref.isEnabled()));
        }
    }

    private static int dp(Context ctx, int value) {
        if (ctx == null) {
            return value;
        }
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
