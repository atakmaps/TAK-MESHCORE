package com.uvpro.plugin;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapView;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry;
import com.uvpro.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.contacts.ContactTracker;
import com.uvpro.plugin.contacts.RadioContact;
import com.uvpro.plugin.protocol.PacketRouter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * MeshCore-only dropdown UI.
 */
public class UVProDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener,
        BtConnectionManager.ConnectionListener,
        ContactTracker.ContactListener,
        PacketRouter.PacketCountListener {

    public static final String SHOW_PLUGIN = "com.uvpro.plugin.SHOW_PLUGIN";
    private static final String TAG = "MeshCore.UI";
    private static final int MAX_LOG_LINES = 50;
    private static final int COLOR_PILL_BUTTON_PRIMARY = 0xFF455A64;
    private static final int PILL_CORNER_RADIUS_DP = 20;
    private static final int EDIT_SELECTION_STROKE_DP = 3;

    private final Context pluginContext;
    private final BtConnectionManager btManager;
    private final ContactTracker contactTracker;

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

    private final List<BluetoothDevice> foundDevices = new ArrayList<>();
    private final LinkedList<String> logLines = new LinkedList<>();
    private int txCount = 0;
    private int rxCount = 0;

    private ValueAnimator connectPulseAnimator;
    private GradientDrawable connectPulseDrawable;

    public UVProDropDownReceiver(MapView mapView,
                                 Context pluginContext,
                                 BtConnectionManager btManager,
                                 ContactTracker contactTracker) {
        super(mapView);
        this.pluginContext = pluginContext;
        this.btManager = btManager;
        this.contactTracker = contactTracker;
        btManager.addListener(this);
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
            stopConnectButtonPulse(true);
        } else {
            updateConnectionUI(false, null);
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
                    com.uvpro.plugin.ui.BluetoothDevicesManagement.show(
                            getMapView().getContext(),
                            () -> getMapView().post(() -> {
                                refreshFavoriteStrip();
                                updateScanButtonText();
                            })));
        }
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

        String target = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
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
        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, "");
        refreshFavoriteStrip();
        updateScanButtonText();
        appendLog("Scanning for MeshCore devices...");
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
                        BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, selected.getAddress());
                        appendLog("Connecting to " + names[which] + "...");
                        updateScanButtonText();
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
        String tgt = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
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

        String selected = BluetoothDeviceRegistry.getMeshConnectTargetAddress(ctx);
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
        connectPulseDrawable = buildPillButtonBackground(COLOR_PILL_BUTTON_PRIMARY, 0x11FFEB3B);
        btnScan.setBackground(connectPulseDrawable);
        connectPulseAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), 0x11FFEB3B, 0xFFFFEB3B);
        connectPulseAnimator.setDuration(220L);
        connectPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        connectPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        connectPulseAnimator.addUpdateListener(animation -> {
            if (connectPulseDrawable == null || btnScan == null) return;
            int color = (Integer) animation.getAnimatedValue();
            connectPulseDrawable.setStroke(dip(getMapView().getContext(), EDIT_SELECTION_STROKE_DP), color);
            btnScan.invalidate();
        });
        connectPulseAnimator.start();
    }

    private void stopConnectButtonPulse(boolean restoreBackground) {
        ValueAnimator animator = connectPulseAnimator;
        connectPulseAnimator = null;
        if (animator != null) {
            animator.cancel();
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
            BluetoothDeviceRegistry.setMeshConnectTargetAddress(ctx, device.getAddress());
        }
        String display = device != null
                ? resolveDeviceDisplayName(getMapView().getContext(), device)
                : "MeshCore";
        getMapView().post(() -> {
            stopConnectButtonPulse(true);
            updateConnectionUI(true, display);
            refreshFavoriteStrip();
            appendLog("Connected to " + display);
        });
    }

    @Override
    public void onDisconnected(String reason) {
        getMapView().post(() -> {
            stopConnectButtonPulse(true);
            updateConnectionUI(false, null);
            appendLog("Disconnected: " + reason);
        });
    }

    @Override
    public void onError(String error) {
        getMapView().post(() -> {
            stopConnectButtonPulse(true);
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
    }

    @Override
    public void onScanComplete() {
        getMapView().post(this::showDevicePicker);
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
        contactTracker.setListener(null);
        stopConnectButtonPulse(true);
    }
}
