package com.atakmaps.meshcore.plugin.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.atakmaps.meshcore.plugin.protocol.PacketRouter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MeshCore BLE companion transport.
 *
 * <p>This manager connects to MeshCore firmware using the Nordic UART service over BLE and tunnels
 * AX.25 frames as chunked Base64 text messages in a selected MeshCore channel.</p>
 */
public class BtConnectionManager {

    private static final String TAG = "MeshCore.MeshBLE";

    private static final UUID UUID_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_UART_RX =
            UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_UART_TX =
            UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    private static final UUID UUID_CCC =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    // MeshCore companion commands
    private static final byte CMD_APP_START = 0x01;
    private static final byte CMD_SEND_SELF_ADVERT = 0x07;
    private static final byte CMD_SET_ADVERT_NAME = 0x08;
    private static final byte CMD_SET_ADVERT_LATLON = 0x0E;
    private static final byte CMD_SET_RADIO_PARAMS = 0x0B;
    private static final byte CMD_SET_RADIO_TX_POWER = 0x0C;
    private static final byte CMD_SET_OTHER_PARAMS = 0x26;
    private static final byte CMD_SEND_TXT_MSG = 0x02;
    private static final byte TXT_TYPE_PLAIN = 0x00;
    private static final byte CMD_SEND_CHANNEL_MSG = 0x03;
    private static final byte CMD_GET_NEXT_MSG = 0x0A;
    private static final byte CMD_DEVICE_QUERY = 0x16;
    private static final byte CMD_DEVICE_QUERY_ARG = 0x03;
    private static final byte CMD_GET_CONTACT_BY_KEY = 0x1E;
    private static final byte CMD_GET_CHANNEL = 0x1F;
    private static final byte CMD_SET_CHANNEL = 0x20;
    private static final byte CMD_GET_GPS_STATE = 0x28;
    private static final byte CMD_SET_SETTING_TEXT = 0x29;
    private static final byte CMD_SEND_CHANNEL_DATA = 0x3E;
    private static final byte CMD_GET_BATTERY = 0x14;
    private static final byte CMD_GET_STATS = 0x38;
    private static final byte STATS_TYPE_CORE = 0x00;

    // Companion notifications
    private static final byte RESP_CHANNEL_MSG = 0x08;
    private static final byte RESP_BATTERY = 0x0C;
    private static final byte RESP_CODE_STATS = 0x18;
    private static final byte RESP_CONTACT_MSG = 0x07;
    private static final byte RESP_SELF_INFO = 0x05;
    private static final byte RESP_DEVICE_INFO = 0x0D;
    private static final byte RESP_CHANNEL_MSG_V3 = 0x11;
    private static final byte RESP_CONTACT_MSG_V3 = 0x10;
    private static final byte RESP_CHANNEL_INFO = 0x12;
    private static final byte RESP_SETTING_TEXT = 0x15;
    private static final byte RESP_CHANNEL_DATA_RECV = 0x1B;
    private static final byte RESP_NO_MORE_MSGS = 0x0A;
    private static final byte PUSH_MESSAGES_WAITING = (byte) 0x83;
    private static final byte PUSH_CODE_SEND_CONFIRMED = (byte) 0x82;
    private static final byte PUSH_CODE_LOG_RX_DATA = (byte) 0x88;
    private static final byte PUSH_CODE_ADVERT = (byte) 0x80;
    private static final byte PUSH_CODE_NEW_ADVERT = (byte) 0x8A;
    private static final byte RESP_CODE_CONTACT = 0x03;
    private static final int ADV_TYPE_REPEATER = 0x02;

    private static final int MAX_MESH_MESSAGE_LEN = 130; // leave room below 133 chars
    private static final int MAX_RAW_AX25_CHUNK = 57; // 57 bytes -> 76 Base64 chars
    private static final int ADVERT_LOC_NONE = 0;
    private static final int ADVERT_LOC_SHARE = 1;
    private static final String ENV_PREFIX = "UVAX1|";
    private static final String COMPANION_APP_ID = "meshcore-flutter";
    private static final int ATAK_CHANNEL_INDEX = 7;
    private static final String ATAK_CHANNEL_NAME = "ATAK_DATA";
    private static final byte[] ATAK_CHANNEL_SECRET = new byte[]{
            (byte) 0xA3, (byte) 0x74, (byte) 0x1E, (byte) 0x6A,
            (byte) 0x52, (byte) 0x9C, (byte) 0xCF, (byte) 0x31,
            (byte) 0xD0, (byte) 0x4B, (byte) 0x89, (byte) 0xFE,
            (byte) 0x17, (byte) 0x63, (byte) 0xB8, (byte) 0x2D
    };
    private static final int ATAK_DATA_TYPE_AX25 = 0xFF01;
    private static final int ATAK_DATA_TYPE_RAW = 0xFF02;
    private static final int OUT_PATH_FLOOD = 0xFF;

    private final Context context;
    private final PacketRouter packetRouter;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    /** True while Scan & Connect scan/picker is active; blocks auto-connect/reconnect. */
    private final AtomicBoolean scanPickerSessionActive = new AtomicBoolean(false);
    /** One saved-target auto-connect attempt per process (retried if BT permissions were missing). */
    private final AtomicBoolean savedTargetAutoConnectAttempted = new AtomicBoolean(false);
    /** Bumped when the user starts Scan & Connect to invalidate in-flight auto-connect callbacks. */
    private final AtomicInteger autoConnectGeneration = new AtomicInteger(0);
    /** True only while connecting from an explicit picker row tap. */
    private final AtomicBoolean userInitiatedConnect = new AtomicBoolean(false);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicBoolean scanCompleteNotified = new AtomicBoolean(false);
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);
    private final AtomicInteger outboundMsgId = new AtomicInteger(1);
    private final Set<String> seenScanAddresses = new HashSet<>();
    private final Set<String> liveScanAddresses = new HashSet<>();
    private final BleMeshAvailabilityProber availabilityProber = new BleMeshAvailabilityProber();

    private static final long MESH_ACL_DEBOUNCE_MS = 1500L;
    private static final long MESH_PASSIVE_INITIAL_DELAY_MS = 3000L;
    private static final long[] MESH_PASSIVE_BACKOFF_MS = {
            3000L, 8000L, 15000L, 30000L, 60000L
    };
    private static final long MESH_PASSIVE_INTERVAL_MS = 60_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver meshAvailabilityReceiver;
    private boolean meshAvailabilityReceiverRegistered = false;
    private final AtomicBoolean passiveMeshWatchArmed = new AtomicBoolean(false);
    private final AtomicInteger passiveMeshProbeGeneration = new AtomicInteger(0);
    private int passiveMeshWatchAttempt = 0;
    private Runnable passiveMeshWatchRunnable;
    private Runnable pendingMeshAclRunnable;

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshStateListener> meshStateListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshAdvertListener> meshAdvertListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RepeaterAdvertListener> repeaterAdvertListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MeshChannelListener> meshChannelListeners =
            new CopyOnWriteArrayList<>();
    private final Map<String, Long> repeaterToastDedupByPubKeyTs = new ConcurrentHashMap<>();
    private final Map<String, Long> nodeToastDedupByPubKeyTs = new ConcurrentHashMap<>();
    private final Map<String, Long> contactQueryThrottleMsByPubKey = new ConcurrentHashMap<>();
    private final Map<Integer, String> meshChannelNamesByIndex = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, byte[]> channelSecretsByIndex =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<Integer, ChunkAccumulator> chunkBuffers = new ConcurrentHashMap<>();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final ArrayDeque<PendingChannelText> pendingChannelTextSends = new ArrayDeque<>();
    private boolean writeInFlight = false;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothDevice lastDevice;
    private volatile Boolean meshGpsEnabled = null;
    private volatile Boolean sendPositionWithAdvertEnabled = null;
    private volatile MeshNodeSettings latestNodeSettings = null;
    private volatile int latestBatteryMv = -1;
    private volatile int latestBatteryPercent = -1;
    private volatile MeshLocationFix latestSelfLocation = null;
    private volatile String selfPubKeyHex = "";
    private volatile int cachedManualAddContacts = 0;
    private volatile int cachedTelemetryModes = 0;
    private volatile int cachedMultiAcks = 0;
    private BluetoothDevice pendingBondDevice;
    private BroadcastReceiver bondReceiver;
    private final AtomicBoolean bondReceiverRegistered = new AtomicBoolean(false);
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long SCAN_PHASE_FILTERED_MS = 6000L;
    private static final long SCAN_PHASE_FALLBACK_MS = 9000L;

    private final HandlerThread ioThread = new HandlerThread("MeshCore-MeshBLE-IO");
    private Handler ioHandler;
    private final AtomicInteger scanSessionCounter = new AtomicInteger(0);
    private volatile int activeScanSessionId = 0;
    private final Runnable periodicMessagePoll = new Runnable() {
        @Override
        public void run() {
            if (!connected.get()) {
                return;
            }
            enqueueCommand(buildGetNextMessageCommand());
            ioHandler.postDelayed(this, 2500L);
        }
    };
    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (scanPickerSessionActive.get()) {
                Log.d(TAG, "Reconnect skipped: scan/picker session active");
                return;
            }
            if (shouldReconnect.get() && !connected.get() && !connecting.get() && lastDevice != null) {
                Log.i(TAG, "Attempting reconnect #" + reconnectAttempts + "...");
                connectInternal(lastDevice);
            }
        }
    };
    private final Runnable bootAutoConnectRunnable = this::tryAutoConnectToSavedTarget;

    public static final int AVAIL_AVAILABLE = BleMeshAvailabilityProber.AVAILABLE;
    public static final int AVAIL_BUSY = BleMeshAvailabilityProber.BUSY;

    public interface AvailabilityCallback {
        void onResult(int availability);
    }

    public interface ConnectionListener {
        void onConnected(BluetoothDevice device);
        void onDisconnected(String reason);
        void onError(String error);
        void onDeviceFound(BluetoothDevice device);
        default void onScanComplete() {}
    }

    /**
     * Optional listener for raw bytes received from the radio.
     * Return true if the data was consumed and should not be processed as KISS.
     */
    public interface RawDataListener {
        boolean onRawBytes(byte[] data);
    }

    public interface MeshStateListener {
        void onMeshGpsStateChanged(boolean enabled);
        void onSendPositionWithAdvertChanged(boolean enabled);
        void onMeshNodeSettingsUpdated(MeshNodeSettings settings);
        void onMeshSelfLocationUpdated(MeshLocationFix fix);
        void onMeshBatteryUpdated(int batteryPercent, int batteryMv);
    }

    public static final class MeshNodeSettings {
        public final String nodeName;
        public final double frequencyMHz;
        public final double bandwidthKHz;
        public final int spreadingFactor;
        public final int codingRate;
        public final int txPowerDbm;
        public final int maxTxPowerDbm;
        public final long receivedAtMs;

        public MeshNodeSettings(String nodeName,
                                double frequencyMHz,
                                double bandwidthKHz,
                                int spreadingFactor,
                                int codingRate,
                                int txPowerDbm,
                                int maxTxPowerDbm,
                                long receivedAtMs) {
            this.nodeName = nodeName;
            this.frequencyMHz = frequencyMHz;
            this.bandwidthKHz = bandwidthKHz;
            this.spreadingFactor = spreadingFactor;
            this.codingRate = codingRate;
            this.txPowerDbm = txPowerDbm;
            this.maxTxPowerDbm = maxTxPowerDbm;
            this.receivedAtMs = receivedAtMs;
        }
    }

    public interface MeshAdvertListener {
        void onMeshAdvert(MeshAdvert advert);
    }

    public static final class MeshAdvert {
        public final int advertType;
        public final String pubKeyHex;
        public final String name;
        public final long advertTimestampSec;
        public final double latitude;
        public final double longitude;
        public final boolean hasPosition;

        public MeshAdvert(int advertType,
                          String pubKeyHex,
                          String name,
                          long advertTimestampSec,
                          double latitude,
                          double longitude,
                          boolean hasPosition) {
            this.advertType = advertType;
            this.pubKeyHex = pubKeyHex;
            this.name = name;
            this.advertTimestampSec = advertTimestampSec;
            this.latitude = latitude;
            this.longitude = longitude;
            this.hasPosition = hasPosition;
        }

        public boolean hasValidPosition() {
            return hasPosition
                    && !Double.isNaN(latitude)
                    && !Double.isNaN(longitude)
                    && latitude >= -90.0 && latitude <= 90.0
                    && longitude >= -180.0 && longitude <= 180.0
                    && !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }

        public boolean isRepeater() {
            return advertType == ADV_TYPE_REPEATER;
        }
    }

    public interface RepeaterAdvertListener {
        void onRepeaterAdvert(RepeaterAdvert advert);
    }

    public static final class RepeaterAdvert {
        public final String pubKeyHex;
        public final String name;
        public final long advertTimestampSec;
        public final double latitude;
        public final double longitude;
        public final boolean hasPosition;

        public RepeaterAdvert(String pubKeyHex,
                              String name,
                              long advertTimestampSec,
                              double latitude,
                              double longitude,
                              boolean hasPosition) {
            this.pubKeyHex = pubKeyHex;
            this.name = name;
            this.advertTimestampSec = advertTimestampSec;
            this.latitude = latitude;
            this.longitude = longitude;
            this.hasPosition = hasPosition;
        }

        public boolean hasValidPosition() {
            return hasPosition
                    && !Double.isNaN(latitude)
                    && !Double.isNaN(longitude)
                    && latitude >= -90.0 && latitude <= 90.0
                    && longitude >= -180.0 && longitude <= 180.0
                    && !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }
    }

    public interface MeshChannelListener {
        void onChannelInfo(MeshChannelInfo info);
        void onChannelMessage(MeshChannelMessage message);
    }

    public static final class MeshChannelInfo {
        public final int index;
        public final String name;

        public MeshChannelInfo(int index, String name) {
            this.index = index;
            this.name = name;
        }
    }

    public static final class MeshChannelMessage {
        public final int channelIndex;
        public final String text;
        public final long receivedAtMs;
        public final boolean outbound;
        public final String statusText;
        public final Integer snrQuarterDb;
        public final Integer pathLen;
        public final Integer senderTimestampSec;

        public MeshChannelMessage(int channelIndex, String text, long receivedAtMs,
                                  boolean outbound, String statusText,
                                  Integer snrQuarterDb, Integer pathLen,
                                  Integer senderTimestampSec) {
            this.channelIndex = channelIndex;
            this.text = text;
            this.receivedAtMs = receivedAtMs;
            this.outbound = outbound;
            this.statusText = statusText;
            this.snrQuarterDb = snrQuarterDb;
            this.pathLen = pathLen;
            this.senderTimestampSec = senderTimestampSec;
        }
    }

    private static final class PendingChannelText {
        final int channelIndex;
        final String text;
        final long queuedAtMs;

        PendingChannelText(int channelIndex, String text, long queuedAtMs) {
            this.channelIndex = channelIndex;
            this.text = text;
            this.queuedAtMs = queuedAtMs;
        }
    }

    public static final class MeshLocationFix {
        public final double latitude;
        public final double longitude;
        public final long receivedAtMs;
        public final String nodeName;

        public MeshLocationFix(double latitude, double longitude, long receivedAtMs, String nodeName) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.receivedAtMs = receivedAtMs;
            this.nodeName = nodeName;
        }

        public boolean isValid() {
            if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
                return false;
            }
            if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
                return false;
            }
            return !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
        }
    }

    public BtConnectionManager(Context context, PacketRouter packetRouter) {
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
        registerMeshAvailabilityReceiver();
    }

    /**
     * Starts a two-phase BLE scan:
     *  1) UUID-filtered scan for Mesh UART/Meshtastic services
     *  2) If phase 1 finds nothing, fallback to unfiltered scan gated by mesh heuristics
     */
    public void startScan() {
        prepareForUserScan();
        if (btAdapter == null) {
            notifyError("Bluetooth not available on this device");
            return;
        }
        if (!btAdapter.isEnabled()) {
            notifyError("Bluetooth is disabled. Please enable it.");
            return;
        }
        if (!checkBtPermissions()) {
            notifyError("Bluetooth permission denied. Grant in Settings > Apps.");
            return;
        }

        stopScanInternal(true);
        cancelBootAutoConnect();
        scanCompleteNotified.set(false);
        synchronized (seenScanAddresses) {
            seenScanAddresses.clear();
        }
        synchronized (liveScanAddresses) {
            liveScanAddresses.clear();
        }
        availabilityProber.cancelAll();
        // Favorites were removed: the picker shows live (in-range) devices plus the single saved
        // last-connected device (shown greyed if it isn't currently advertising). We no longer
        // flood the list with every bonded/registry device.
        emitSavedTargetCandidate();

        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            notifyScanComplete();
            return;
        }
        int sessionId = scanSessionCounter.incrementAndGet();
        activeScanSessionId = sessionId;
        startScanPhase(sessionId, true);
    }

    private void startScanPhase(int sessionId, boolean filteredPhase) {
        if (bleScanner == null || sessionId != activeScanSessionId) {
            return;
        }
        stopScanInternal(false);

        List<ScanFilter> filters = new ArrayList<>();
        if (filteredPhase) {
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(MeshBleDeviceMatcher.UUID_UART_SERVICE))
                    .build());
        }
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (!MeshBleDeviceMatcher.isMeshDevice(context, result, device, filteredPhase)) {
                    return;
                }
                noteScanCandidate(device, true);
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "BLE scan failed: " + errorCode);
                notifyError("BLE scan failed: " + errorCode);
                stopScanInternal(true);
                if (scanCompleteNotified.compareAndSet(false, true)) {
                    notifyScanComplete();
                }
            }
        };
        try {
            bleScanner.startScan(filters, settings, scanCallback);
            ioHandler.postDelayed(() -> {
                if (sessionId != activeScanSessionId) {
                    return;
                }
                boolean foundAny;
                synchronized (seenScanAddresses) {
                    foundAny = !seenScanAddresses.isEmpty();
                }
                if (filteredPhase && !foundAny) {
                    Log.i(TAG, "Filtered BLE scan found no Mesh nodes; falling back to heuristic scan");
                    startScanPhase(sessionId, false);
                    return;
                }
                stopScanInternal(true);
                if (scanCompleteNotified.compareAndSet(false, true)) {
                    notifyScanComplete();
                }
            }, filteredPhase ? SCAN_PHASE_FILTERED_MS : SCAN_PHASE_FALLBACK_MS);
        } catch (Exception e) {
            Log.w(TAG, "BLE scan start failed", e);
            stopScanInternal(true);
            if (scanCompleteNotified.compareAndSet(false, true)) {
                notifyScanComplete();
            }
        }
    }

    /**
     * Emits only the saved last-connected target so it appears in the picker (greyed if it isn't
     * advertising right now). This replaces the old favorite/bonded/registry flood.
     */
    private void emitSavedTargetCandidate() {
        if (btAdapter == null) {
            return;
        }
        try {
            String tgt = BluetoothDeviceRegistry.getConnectTargetAddress(context);
            if (tgt == null || tgt.isEmpty()) {
                return;
            }
            BluetoothDevice device = btAdapter.getRemoteDevice(tgt);
            if (device != null) {
                noteScanCandidate(device, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "emitSavedTargetCandidate failed", e);
        }
    }

    private void emitBondedMeshCandidates() {
        if (btAdapter == null) {
            return;
        }
        try {
            Set<BluetoothDevice> bonded = btAdapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) {
                return;
            }
            for (BluetoothDevice device : bonded) {
                if (!MeshBleDeviceMatcher.isMeshDevice(context, device)) {
                    continue;
                }
                noteScanCandidate(device, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate bonded MeshCore devices", e);
        }
    }

    private void emitRegistryMeshCandidates() {
        if (btAdapter == null) {
            return;
        }
        try {
            for (BluetoothDeviceRegistry.BtDeviceRecord record
                    : BluetoothDeviceRegistry.getAllSortedForDisplay(context)) {
                if (record == null || record.address == null || record.address.isEmpty()) {
                    continue;
                }
                BluetoothDevice device = btAdapter.getRemoteDevice(record.address);
                noteScanCandidate(device, false);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate saved MeshCore devices", e);
        }
    }

    private void noteScanCandidate(BluetoothDevice device, boolean fromLiveScan) {
        if (device == null) {
            return;
        }
        String address = device.getAddress();
        if (fromLiveScan && address != null) {
            synchronized (liveScanAddresses) {
                liveScanAddresses.add(normalizeBtAddress(address));
            }
        }
        boolean isNew = false;
        if (address != null) {
            synchronized (seenScanAddresses) {
                if (!seenScanAddresses.contains(address)) {
                    seenScanAddresses.add(address);
                    isNew = true;
                }
            }
        } else {
            isNew = true;
        }
        if (isNew) {
            notifyDeviceFound(device);
        }
    }

    /** True when the device was seen advertising during the current BLE scan. */
    public boolean isLiveScanDevice(@Nullable BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        return isLiveScanDevice(device.getAddress());
    }

    public boolean isLiveScanDevice(@Nullable String address) {
        if (address == null) {
            return false;
        }
        synchronized (liveScanAddresses) {
            return liveScanAddresses.contains(normalizeBtAddress(address));
        }
    }

    /**
     * Full GATT availability probes trigger Android pairing on unbonded devices.
     * Only bonded devices are safe to probe during Scan & Connect picker.
     */
    public boolean isSafeForAvailabilityProbe(@Nullable BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (Exception e) {
            return false;
        }
    }

    public void probeDeviceAvailability(BluetoothDevice device, AvailabilityCallback callback) {
        availabilityProber.probe(context, ioHandler, device, callback::onResult);
    }

    /** Picker-safe probe for unbonded nodes (UART discovery only, no pairing handshake). */
    public void probeDeviceAvailabilityLight(BluetoothDevice device, AvailabilityCallback callback) {
        availabilityProber.probeLight(context, ioHandler, device, callback::onResult);
    }

    /** Bonded nodes get a full handshake probe; unbonded nodes get a light probe. */
    public void probeDeviceAvailabilityForPicker(BluetoothDevice device,
                                                 AvailabilityCallback callback) {
        if (isSafeForAvailabilityProbe(device)) {
            probeDeviceAvailability(device, callback);
        } else {
            probeDeviceAvailabilityLight(device, callback);
        }
    }

    public void cancelAvailabilityProbes() {
        availabilityProber.cancelAll();
    }

    /** Stop BLE scan and clear probes before the picker runs availability checks. */
    public void prepareForAvailabilityProbes() {
        stopScanInternal(true);
        availabilityProber.cancelAll();
    }

    private static String normalizeBtAddress(String address) {
        return address.trim().toUpperCase(java.util.Locale.US);
    }

    /** No-op in BLE mode (kept for compatibility with existing UI flow). */
    public void addProbeSocket(String address, android.bluetooth.BluetoothSocket socket) {}
    /** No-op in BLE mode (kept for compatibility with existing UI flow). */
    public void clearProbeSockets() {}


    /**
     * Check (and request if possible) Bluetooth runtime permissions for Android 12+.
     * @return true if permissions are granted
     */
    private boolean checkBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true; // Pre-Android 12 doesn't need runtime BT permissions
        }
        boolean connectGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
        boolean scanGranted = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;

        if (connectGranted && scanGranted) {
            return true;
        }

        // Try to request permissions if we can get an Activity
        requestBtPermissions();
        return false;
    }

    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            // ATAK MapView.getMapView().getContext() returns the Activity
            Context ctx = context;
            if (ctx instanceof Activity) {
                ((Activity) ctx).requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, 1001);
            } else {
                // Try via MapView
                com.atakmap.android.maps.MapView mv =
                        com.atakmap.android.maps.MapView.getMapView();
                if (mv != null && mv.getContext() instanceof Activity) {
                    ((Activity) mv.getContext()).requestPermissions(
                            new String[]{
                                    Manifest.permission.BLUETOOTH_CONNECT,
                                    Manifest.permission.BLUETOOTH_SCAN
                            }, 1001);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not request BT permissions", e);
        }
    }

    /** Ends the Scan & Connect guard so normal auto-reconnect may resume. */
    public void endScanPickerSession() {
        scanPickerSessionActive.set(false);
    }

    public boolean isScanPickerSessionActive() {
        return scanPickerSessionActive.get();
    }

    /**
     * Called when the user explicitly starts Scan & Connect. Blocks and tears down any
     * boot auto-connect probe/connection so only a picker selection may connect.
     */
    public void prepareForUserScan() {
        Log.i(TAG, "prepareForUserScan: Scan & Connect — blocking auto-connect");
        scanPickerSessionActive.set(true);
        autoConnectGeneration.incrementAndGet();
        cancelBootAutoConnect();
        cancelPendingReconnect();
        cancelAvailabilityProbes();
        cancelPassiveMeshWatch();
        shouldReconnect.set(false);
        reconnectAttempts = 0;
        pendingBondDevice = null;
        userInitiatedConnect.set(false);
        if (connecting.get() || gatt != null) {
            Log.i(TAG, "prepareForUserScan: aborting in-flight BLE connection");
            connecting.set(false);
            connected.set(false);
            ioHandler.removeCallbacks(periodicMessagePoll);
            ioHandler.post(this::closeGattInternal);
        }
    }

    private void cancelPendingReconnect() {
        if (ioHandler != null) {
            ioHandler.removeCallbacks(reconnectRunnable);
        }
    }

    // -------------------------------------------------------------------------
    // Passive mesh watch (late power-on / ACL, mirrors UV-PRO Classic BT recovery)
    // -------------------------------------------------------------------------

    private void registerMeshAvailabilityReceiver() {
        if (meshAvailabilityReceiverRegistered) {
            return;
        }
        meshAvailabilityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null) {
                    return;
                }
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device != null) {
                        onMeshAclConnected(device);
                    }
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        onBluetoothEnabledForMesh();
                    }
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(meshAvailabilityReceiver, filter,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(meshAvailabilityReceiver, filter);
            }
            meshAvailabilityReceiverRegistered = true;
            Log.d(TAG, "Mesh availability receiver registered");
        } catch (Exception e) {
            Log.w(TAG, "Could not register mesh availability receiver", e);
        }
    }

    @Nullable
    private String getSavedMeshTargetAddress() {
        try {
            return BluetoothDeviceRegistry.getConnectTargetAddress(context);
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private BluetoothDevice resolveSavedMeshDevice() {
        String tgt = getSavedMeshTargetAddress();
        if (tgt == null || tgt.isEmpty() || btAdapter == null) {
            return null;
        }
        try {
            return btAdapter.getRemoteDevice(tgt);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isSavedMeshTarget(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        String tgt = getSavedMeshTargetAddress();
        return tgt != null && tgt.equalsIgnoreCase(address);
    }

    private void onMeshAclConnected(BluetoothDevice device) {
        if (device == null || device.getAddress() == null) {
            return;
        }
        if (!shouldReconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            cancelPendingMeshAclProbe();
            return;
        }
        String addr = device.getAddress();
        Log.d(TAG, "ACL connected " + addr + " (saved mesh="
                + getSavedMeshTargetAddress() + ")");
        if (!isSavedMeshTarget(addr)) {
            return;
        }
        Log.i(TAG, "ACL connected for saved mesh " + addr + " — scheduling probe");
        scheduleMeshAclProbe(device, "acl-connected");
    }

    private void onBluetoothEnabledForMesh() {
        if (!shouldReconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        BluetoothDevice device = resolveSavedMeshDevice();
        if (device == null) {
            return;
        }
        Log.i(TAG, "Bluetooth enabled — arming mesh passive watch for "
                + device.getAddress());
        armPassiveMeshWatch();
    }

    private void scheduleMeshAclProbe(BluetoothDevice device, String reason) {
        if (device == null) {
            return;
        }
        cancelPendingMeshAclProbe();
        final BluetoothDevice target = device;
        pendingMeshAclRunnable = () -> {
            pendingMeshAclRunnable = null;
            probeAndConnectSavedMesh(reason, target);
        };
        mainHandler.postDelayed(pendingMeshAclRunnable, MESH_ACL_DEBOUNCE_MS);
    }

    private void cancelPendingMeshAclProbe() {
        if (pendingMeshAclRunnable != null) {
            mainHandler.removeCallbacks(pendingMeshAclRunnable);
            pendingMeshAclRunnable = null;
        }
    }

    private void armPassiveMeshWatch() {
        if (!shouldReconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        if (resolveSavedMeshDevice() == null) {
            return;
        }
        passiveMeshWatchArmed.set(true);
        passiveMeshWatchAttempt = 0;
        cancelPassiveMeshWatchScheduled();
        Log.i(TAG, "Mesh passive watch armed (target=" + getSavedMeshTargetAddress() + ")");
        scheduleNextPassiveMeshWatch(MESH_PASSIVE_INITIAL_DELAY_MS);
    }

    private void cancelPassiveMeshWatch() {
        passiveMeshWatchArmed.set(false);
        passiveMeshWatchAttempt = 0;
        passiveMeshProbeGeneration.incrementAndGet();
        cancelPassiveMeshWatchScheduled();
        cancelPendingMeshAclProbe();
    }

    private void cancelPassiveMeshWatchScheduled() {
        if (passiveMeshWatchRunnable != null) {
            mainHandler.removeCallbacks(passiveMeshWatchRunnable);
            passiveMeshWatchRunnable = null;
        }
    }

    private boolean shouldRunPassiveMeshWatch() {
        if (!passiveMeshWatchArmed.get() || !shouldReconnect.get()
                || scanPickerSessionActive.get()) {
            return false;
        }
        if (connected.get() || connecting.get()) {
            return false;
        }
        String tgt = getSavedMeshTargetAddress();
        return tgt != null && !tgt.isEmpty();
    }

    private void scheduleNextPassiveMeshWatch(long delayMs) {
        if (!shouldRunPassiveMeshWatch()) {
            cancelPassiveMeshWatch();
            return;
        }
        if (passiveMeshWatchRunnable != null) {
            mainHandler.removeCallbacks(passiveMeshWatchRunnable);
        }
        passiveMeshWatchRunnable = () -> {
            passiveMeshWatchRunnable = null;
            if (!shouldRunPassiveMeshWatch()) {
                cancelPassiveMeshWatch();
                return;
            }
            BluetoothDevice device = resolveSavedMeshDevice();
            if (device != null) {
                probeAndConnectSavedMesh("passive-watch", device);
            }
            scheduleNextPassiveMeshWatch(nextPassiveMeshWatchDelay());
        };
        mainHandler.postDelayed(passiveMeshWatchRunnable, delayMs);
    }

    private long nextPassiveMeshWatchDelay() {
        int idx = passiveMeshWatchAttempt++;
        if (idx < MESH_PASSIVE_BACKOFF_MS.length) {
            return MESH_PASSIVE_BACKOFF_MS[idx];
        }
        return MESH_PASSIVE_INTERVAL_MS;
    }

    private void probeAndConnectSavedMesh(String reason, BluetoothDevice device) {
        if (device == null || !shouldReconnect.get() || scanPickerSessionActive.get()) {
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        if (!checkBtPermissions()) {
            return;
        }
        if (!isSafeForAvailabilityProbe(device)) {
            return;
        }
        final int gen = passiveMeshProbeGeneration.incrementAndGet();
        final String addr = device.getAddress();
        Log.i(TAG, reason + ": probing saved mesh " + addr);
        probeDeviceAvailability(device, availability -> {
            if (gen != passiveMeshProbeGeneration.get()) {
                return;
            }
            if (!shouldReconnect.get() || scanPickerSessionActive.get()) {
                return;
            }
            if (connected.get() || connecting.get()) {
                return;
            }
            if (availability == AVAIL_AVAILABLE) {
                Log.i(TAG, reason + ": connecting to " + addr);
                connectInternal(device);
            } else {
                Log.d(TAG, reason + ": mesh " + addr + " not available (avail=" + availability + ")");
            }
        });
    }

    /** Schedules auto-connect to the last successful mesh device after ATAK boot. */
    public void scheduleBootAutoConnect() {
        if (ioHandler == null) {
            return;
        }
        ioHandler.removeCallbacks(bootAutoConnectRunnable);
        ioHandler.postDelayed(bootAutoConnectRunnable, 2500L);
    }

    public void cancelBootAutoConnect() {
        if (ioHandler != null) {
            ioHandler.removeCallbacks(bootAutoConnectRunnable);
        }
    }

    /**
     * Probes the saved connect target and connects when the node is available.
     * Skipped during Scan & Connect; attempted once per process unless deferred for permissions.
     */
    public void tryAutoConnectToSavedTarget() {
        if (scanPickerSessionActive.get()) {
            Log.d(TAG, "Auto-connect to saved target skipped: scan/picker session active");
            return;
        }
        if (connected.get() || connecting.get()) {
            return;
        }
        if (btAdapter == null || !btAdapter.isEnabled()) {
            return;
        }
        if (!checkBtPermissions()) {
            Log.i(TAG, "Auto-connect to saved target deferred: missing Bluetooth permissions");
            return;
        }

        String target = BluetoothDeviceRegistry.getConnectTargetAddress(context);
        if (target == null || target.isEmpty()) {
            return;
        }
        if (!savedTargetAutoConnectAttempted.compareAndSet(false, true)) {
            return;
        }

        final BluetoothDevice device;
        try {
            device = btAdapter.getRemoteDevice(target);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid saved connect target: " + target, e);
            return;
        }

        // Only auto-connect to devices that are already bonded. Probing/connecting an
        // unbonded saved target in the background grabs the node's single BLE connection
        // slot and stops its advertising, which blocks the user from pairing it manually
        // (in Android settings or the MeshCore app). Leave unbonded targets alone until the
        // user explicitly taps Scan & Connect or the favorite chip.
        int savedTargetBondState = BluetoothDevice.BOND_NONE;
        try {
            savedTargetBondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (savedTargetBondState != BluetoothDevice.BOND_BONDED) {
            Log.i(TAG, "Auto-connect: saved target " + target
                    + " is not bonded — skipping background auto-connect (manual pairing first)");
            return;
        }

        Log.i(TAG, "Probing saved mesh device for auto-connect: "
                + MeshBleDeviceMatcher.resolveName(null, device));
        final int probeGeneration = autoConnectGeneration.get();
        AvailabilityCallback onProbeResult = availability -> {
            if (probeGeneration != autoConnectGeneration.get()) {
                Log.i(TAG, "Auto-connect probe result stale; ignored (Scan & Connect started)");
                return;
            }
            if (scanPickerSessionActive.get()) {
                Log.d(TAG, "Auto-connect aborted: scan/picker started during probe");
                return;
            }
            if (connected.get() || connecting.get()) {
                return;
            }
            if (availability == AVAIL_AVAILABLE) {
                Log.i(TAG, "Saved mesh device available; auto-connecting");
                connectInternal(device);
            } else {
                Log.i(TAG, "Saved mesh device not available (availability=" + availability + ")");
                armPassiveMeshWatch();
            }
        };
        if (isSafeForAvailabilityProbe(device)) {
            probeDeviceAvailability(device, onProbeResult);
        } else {
            probeDeviceAvailabilityLight(device, onProbeResult);
        }
    }

    /**
     * Connect after explicit user selection from the Scan & Connect picker.
     */
    public void connectUserSelected(BluetoothDevice device) {
        userInitiatedConnect.set(true);
        endScanPickerSession();
        connectInternal(device);
    }

    /**
     * Connect to a device (auto-reconnect / last-known flows). Blocked during scan/picker.
     */
    public void connect(BluetoothDevice device) {
        if (scanPickerSessionActive.get()) {
            Log.i(TAG, "Connect ignored during scan/picker session for "
                    + (device != null ? device.getAddress() : "null"));
            return;
        }
        connectInternal(device);
    }

    private void connectInternal(BluetoothDevice device) {
        if (device == null) return;
        if (scanPickerSessionActive.get() && !userInitiatedConnect.get()) {
            Log.i(TAG, "connectInternal blocked during Scan & Connect session");
            return;
        }
        stopScanInternal();
        cancelAvailabilityProbes();
        if (connected.get()) {
            disconnect();
        }
        if (connecting.getAndSet(true)) {
            Log.w(TAG, "Already connecting, ignoring duplicate request");
            return;
        }

        lastDevice = device;
        shouldReconnect.set(true);
        reconnectAttempts = 0;
        clearQueues();
        registerBondReceiver();
        if (requestPairingIfNeeded(device)) {
            // Bonding is in progress. Do NOT connect now — the bond receiver issues the single
            // connect once pairing completes. Connecting here would create a second GATT once the
            // bond receiver fires, which closes the working link and trips a supervision timeout
            // (the connection "drops right after connecting"). connecting stays true; the bond
            // receiver connects or clears it.
            return;
        }
        connectGattNow(device);
    }

    /** @return true if a pairing flow was started and the connect should wait for the bond receiver. */
    private boolean requestPairingIfNeeded(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        int bondState = BluetoothDevice.BOND_NONE;
        try {
            bondState = device.getBondState();
        } catch (Exception ignored) {
        }
        if (bondState == BluetoothDevice.BOND_BONDED) {
            pendingBondDevice = null;
            return false;
        }

        pendingBondDevice = device;
        boolean requested = false;
        try {
            if (bondState != BluetoothDevice.BOND_BONDING) {
                requested = device.createBond();
            } else {
                requested = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "createBond failed", e);
        }
        if (requested) {
            notifyError(MeshBleDeviceMatcher.pairingHintMessage(device));
            return true;
        }
        notifyError("Could not start pairing for "
                + MeshBleDeviceMatcher.resolveName(null, device)
                + ". Attempting BLE connect...");
        // Fall through: some devices pair lazily during GATT access.
        return false;
    }

    private void connectGattNow(BluetoothDevice device) {
        if (device == null) {
            connecting.set(false);
            return;
        }
        ioHandler.post(() -> {
            try {
                closeGattInternal();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    gatt = device.connectGatt(context.getApplicationContext(),
                            false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                } else {
                    gatt = device.connectGatt(context.getApplicationContext(),
                            false, gattCallback);
                }
                if (gatt == null) {
                    connecting.set(false);
                    notifyError("BLE connectGatt failed");
                }
            } catch (Exception e) {
                connecting.set(false);
                notifyError("BLE connect failed: " + e.getMessage());
            }
        });
    }

    private void registerBondReceiver() {
        if (bondReceiverRegistered.get()) {
            return;
        }
        bondReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || !BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
                    return;
                }
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || pendingBondDevice == null) {
                    return;
                }
                String target = pendingBondDevice.getAddress();
                if (target == null || !target.equalsIgnoreCase(device.getAddress())) {
                    return;
                }
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    if (scanPickerSessionActive.get()) {
                        Log.i(TAG, "Bond complete during scan/picker; not auto-connecting");
                        pendingBondDevice = null;
                        return;
                    }
                    BluetoothDevice bondedDevice = pendingBondDevice;
                    pendingBondDevice = null;
                    lastDevice = bondedDevice;
                    notifyError("Pairing complete. Connecting...");
                    // Guard against a duplicate connection: if a link is already up or a GATT is
                    // already in flight, don't open a second one (that collision drops it).
                    if (connected.get() || gatt != null) {
                        Log.i(TAG, "Bond complete; connection already active/in-flight — not reconnecting");
                        return;
                    }
                    connecting.set(true);
                    connectGattNow(bondedDevice);
                } else if (state == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) {
                    pendingBondDevice = null;
                    connecting.set(false);
                    notifyError("Pairing cancelled or failed.");
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            context.registerReceiver(bondReceiver, filter);
            bondReceiverRegistered.set(true);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register bond receiver", e);
        }
    }

    /**
     * Connect to the last known device.
     */
    public void connectToLastDevice() {
        if (scanPickerSessionActive.get()) {
            Log.i(TAG, "connectToLastDevice ignored during scan/picker session");
            return;
        }
        if (lastDevice != null) {
            connectInternal(lastDevice);
        } else {
            startScan();
        }
    }

    /**
     * Disconnect from the radio.
     */
    public void disconnect() {
        // Full stop: a user-initiated disconnect halts ALL auto-connect activity for the rest of
        // this session (boot auto-connect, any in-flight probe/reconnect). The saved target is
        // intentionally NOT cleared, so the next app launch will still auto-connect to it.
        shouldReconnect.set(false);
        autoConnectGeneration.incrementAndGet();
        cancelBootAutoConnect();
        cancelPendingReconnect();
        cancelPassiveMeshWatch();
        savedTargetAutoConnectAttempted.set(true);
        connecting.set(false);
        connected.set(false);
        pendingBondDevice = null;
        userInitiatedConnect.set(false);
        latestBatteryMv = -1;
        latestBatteryPercent = -1;
        cancelAvailabilityProbes();
        stopScanInternal();
        ioHandler.removeCallbacks(periodicMessagePoll);
        ioHandler.removeCallbacksAndMessages(null);
        ioHandler.post(() -> {
            runBeforeDisconnectHooks();
            closeGattInternal();
        });
        notifyDisconnected("User disconnected");
    }

    /**
     * Cancel any in-progress connect attempt and stop auto-reconnect.
     * Useful when the user explicitly wants to scan/select another device.
     */
    public void cancelConnectionAttempts() {
        shouldReconnect.set(false);
        reconnectAttempts = 0;
        cancelPassiveMeshWatch();
        connecting.set(false);
        connected.set(false);
        pendingBondDevice = null;
        userInitiatedConnect.set(false);
        latestBatteryMv = -1;
        latestBatteryPercent = -1;
        cancelAvailabilityProbes();
        stopScanInternal();
        ioHandler.removeCallbacks(periodicMessagePoll);
        ioHandler.post(() -> {
            runBeforeDisconnectHooks();
            closeGattInternal();
        });
        notifyDisconnected("Connection attempt cancelled");
    }

    /**
     * Send AX.25 frame over MeshCore channel using chunked Base64 text envelopes.
     */
    public boolean sendKissFrame(byte[] ax25Frame) {
        if (!connected.get()) {
            Log.w(TAG, "Cannot send: not connected");
            return false;
        }
        if (radioSilenceEnabled.get()) {
            Log.w(TAG, "TX blocked by Radio Silence");
            return false;
        }

        if (ax25Frame == null || ax25Frame.length == 0) {
            return false;
        }

        int channel = getMeshChannelIndex();
        int msgId = outboundMsgId.getAndIncrement() & 0x7fffffff;
        int total = (ax25Frame.length + MAX_RAW_AX25_CHUNK - 1) / MAX_RAW_AX25_CHUNK;
        Log.d(TAG, "sendKissFrame over mesh bytes=" + ax25Frame.length + " chunks=" + total);
        for (int i = 0; i < total; i++) {
            int off = i * MAX_RAW_AX25_CHUNK;
            int len = Math.min(MAX_RAW_AX25_CHUNK, ax25Frame.length - off);
            byte[] chunk = new byte[len];
            System.arraycopy(ax25Frame, off, chunk, 0, len);
            String b64 = Base64.encodeToString(chunk, Base64.NO_WRAP);
            String payload = ENV_PREFIX + msgId + "|" + (i + 1) + "|" + total + "|" + b64;
            if (payload.length() > MAX_MESH_MESSAGE_LEN) {
                Log.w(TAG, "Mesh payload too long, dropping frame");
                return false;
            }
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            Log.d(TAG, "chunk " + (i + 1) + "/" + total + " datagramBytes=" + payloadBytes.length);
            enqueueCommand(buildSendChannelDataCommand(channel, ATAK_DATA_TYPE_AX25, payloadBytes));
        }
        packetRouter.notifyPacketTransmitted();
        return true;
    }

    /**
     * Send control bytes as a Base64 envelope over MeshCore transport.
     */
    public boolean sendRawBytes(byte[] data) {
        if (!connected.get() || data == null || data.length == 0) {
            Log.w(TAG, "Cannot send raw bytes: not connected");
            return false;
        }
        String payload = "UVRAW|" + Base64.encodeToString(data, Base64.NO_WRAP);
        if (payload.length() > MAX_MESH_MESSAGE_LEN) {
            return false;
        }
        enqueueCommand(buildSendChannelDataCommand(
                getMeshChannelIndex(),
                ATAK_DATA_TYPE_RAW,
                payload.getBytes(StandardCharsets.UTF_8)));
        return true;
    }

    /**
     * Radio Silence blocks outbound KISS/AX.25 (chat, ACKs, beacons, CoT) while receive and
     * Bluetooth radio-control commands ({@link #sendRawBytes}) remain active.
     */
    public void setRadioSilenceEnabled(boolean enabled) {
        radioSilenceEnabled.set(enabled);
        Log.i(TAG, "Radio Silence " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isRadioSilenceEnabled() {
        return radioSilenceEnabled.get();
    }

    private void handleConnectionLost() {
        connected.set(false);
        connecting.set(false);
        ioHandler.removeCallbacks(periodicMessagePoll);
        clearQueues();
        ioHandler.post(this::closeGattInternal);
        shouldReconnect.set(true);
        cancelPendingReconnect();
        notifyDisconnected("Connection lost");
        if (!scanPickerSessionActive.get()) {
            armPassiveMeshWatch();
        }
    }

    private void scheduleReconnect() {
        if (scanPickerSessionActive.get()) {
            return;
        }
        if (lastDevice == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS + "). Giving up.");
            notifyError("Reconnect failed after " + MAX_RECONNECT_ATTEMPTS + " attempts. Tap Scan to retry.");
            return;
        }

        reconnectAttempts++;
        int delaySec = 5 * reconnectAttempts; // Back off: 5s, 10s, 15s...
        Log.i(TAG, "Scheduling reconnect #" + reconnectAttempts + " in " + delaySec + " seconds...");
        ioHandler.postDelayed(reconnectRunnable, delaySec * 1000L);
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isConnecting() {
        return connecting.get();
    }

    public long getLastIoActivityMs() {
        return lastIoActivityMs.get();
    }

    public boolean hasRecentIo(long withinMs) {
        long last = lastIoActivityMs.get();
        if (last <= 0L) {
            return false;
        }
        long window = Math.max(0L, withinMs);
        return (System.currentTimeMillis() - last) <= window;
    }

    public String getConnectedDeviceName() {
        if (!connected.get()) return null;
        if (lastDevice != null) {
            String name = lastDevice.getName();
            return name != null ? name : lastDevice.getAddress();
        }
        return "MeshCore";
    }

    // --- Listener management ---

    public void addListener(ConnectionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public void addRawDataListener(RawDataListener listener) {
        rawDataListeners.add(listener);
    }

    public void removeRawDataListener(RawDataListener listener) {
        rawDataListeners.remove(listener);
    }

    public void addMeshStateListener(MeshStateListener listener) {
        if (listener != null) {
            meshStateListeners.add(listener);
        }
    }

    public void removeMeshStateListener(MeshStateListener listener) {
        meshStateListeners.remove(listener);
    }

    public void addMeshAdvertListener(MeshAdvertListener listener) {
        if (listener != null) {
            meshAdvertListeners.add(listener);
        }
    }

    public void removeMeshAdvertListener(MeshAdvertListener listener) {
        meshAdvertListeners.remove(listener);
    }

    public void addRepeaterAdvertListener(RepeaterAdvertListener listener) {
        if (listener != null) {
            repeaterAdvertListeners.addIfAbsent(listener);
        }
    }

    public void removeRepeaterAdvertListener(RepeaterAdvertListener listener) {
        repeaterAdvertListeners.remove(listener);
    }

    public void addMeshChannelListener(MeshChannelListener listener) {
        if (listener != null) {
            meshChannelListeners.addIfAbsent(listener);
        }
    }

    public void removeMeshChannelListener(MeshChannelListener listener) {
        meshChannelListeners.remove(listener);
    }

    public Map<Integer, String> getKnownChannelNamesSnapshot() {
        return new ConcurrentHashMap<>(meshChannelNamesByIndex);
    }

    public String getMeshChannelName(int index) {
        return meshChannelNamesByIndex.get(index);
    }

    public byte[] getChannelSecret(int idx) {
        return channelSecretsByIndex.get(idx);
    }

    public boolean setChannelSlot(int idx, String name, byte[] secret) {
        if (!connected.get() || idx < 0 || idx > 7) return false;
        if (secret != null) channelSecretsByIndex.put(idx, java.util.Arrays.copyOf(secret, secret.length));
        enqueueCommand(buildSetChannelCommand(idx, name != null ? name : "", secret));
        enqueueCommand(buildGetChannelInfoCommand(idx));
        return true;
    }

    public boolean clearChannelSlot(int idx) {
        return setChannelSlot(idx, "", new byte[16]);
    }

    public void requestAllChannelInfo() {
        if (!connected.get()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            enqueueCommand(buildGetChannelInfoCommand(i));
        }
    }

    public boolean sendChannelText(int channelIndex, String text) {
        if (!connected.get()) {
            return false;
        }
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        synchronized (pendingChannelTextSends) {
            pendingChannelTextSends.addLast(new PendingChannelText(
                    Math.max(0, Math.min(7, channelIndex)),
                    trimmed,
                    System.currentTimeMillis()));
            while (pendingChannelTextSends.size() > 64) {
                pendingChannelTextSends.removeFirst();
            }
        }
        enqueueCommand(buildSendChannelMessageCommand(channelIndex, trimmed));
        notifyMeshChannelMessage(new MeshChannelMessage(
                Math.max(0, Math.min(7, channelIndex)),
                trimmed,
                System.currentTimeMillis(),
                true,
                "queued",
                null,
                null,
                null));
        return true;
    }

    /**
     * Send a native MeshCore direct (contact) message to a node identified by pubkey, using
     * {@code CMD_SEND_TXT_MSG (0x02)}. This is the standard pubkey-to-pubkey DM that native
     * MeshCore clients understand — unlike the {@code 0xFF01} channel datagram used for the
     * AX.25 tunnel. The recipient must be a contact on this node (firmware looks it up by the
     * first 6 bytes of the pubkey); otherwise the node replies {@code ERR_CODE_NOT_FOUND}.
     *
     * @param pubKeyHex recipient pubkey (>= 12 hex chars; first 6 bytes are used as the prefix)
     * @param text      plain UTF-8 message
     */
    public boolean sendContactTextMessage(String pubKeyHex, String text) {
        if (!connected.get()) {
            return false;
        }
        if (radioSilenceEnabled.get()) {
            return false;
        }
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        byte[] prefix = pubKeyPrefixBytes(pubKeyHex, 6);
        if (prefix == null) {
            Log.w(TAG, "Native DM aborted: invalid pubkey hex");
            return false;
        }
        byte[] cmd = buildSendTxtMsgCommand(prefix, text.trim());
        if (cmd == null) {
            return false;
        }
        enqueueCommand(cmd);
        packetRouter.notifyPacketTransmitted();
        Log.d(TAG, "Native MeshCore DM queued pubkeyPrefix="
                + bytesToHex(prefix, 0, prefix.length) + " len=" + text.trim().length());
        return true;
    }

    public boolean setAdvertLatLon(double latitude, double longitude, double altitudeMeters) {
        if (!connected.get()) {
            return false;
        }
        if (Double.isNaN(latitude) || Double.isNaN(longitude)
                || latitude < -90.0 || latitude > 90.0
                || longitude < -180.0 || longitude > 180.0) {
            return false;
        }
        int latE6 = (int) Math.round(latitude * 1_000_000.0);
        int lonE6 = (int) Math.round(longitude * 1_000_000.0);
        int alt = Double.isNaN(altitudeMeters) ? 0 : (int) Math.round(altitudeMeters);
        byte[] out = new byte[13];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(CMD_SET_ADVERT_LATLON);
        bb.putInt(latE6);
        bb.putInt(lonE6);
        bb.putInt(alt);
        enqueueCommand(out);
        return true;
    }

    private static byte[] pubKeyPrefixBytes(String pubKeyHex, int byteCount) {
        if (pubKeyHex == null) {
            return null;
        }
        String hex = pubKeyHex.trim();
        if (hex.length() < byteCount * 2) {
            return null;
        }
        byte[] out = new byte[byteCount];
        try {
            for (int i = 0; i < byteCount; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    public Boolean getMeshGpsEnabled() {
        return meshGpsEnabled;
    }

    public Boolean getSendPositionWithAdvertEnabled() {
        return sendPositionWithAdvertEnabled;
    }

    public MeshNodeSettings getLatestNodeSettings() {
        return latestNodeSettings;
    }

    public MeshLocationFix getLatestSelfLocation() {
        return latestSelfLocation;
    }

    public int getLatestBatteryPercent() {
        return latestBatteryPercent;
    }

    public int getLatestBatteryMv() {
        return latestBatteryMv;
    }

    public void requestBattery() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(new byte[]{CMD_GET_BATTERY});
        enqueueCommand(new byte[]{CMD_GET_STATS, STATS_TYPE_CORE});
    }

    public static int meshBatteryMvToPercent(int batteryMv) {
        if (batteryMv <= 0) {
            return -1;
        }
        final int minMv = 3300;
        final int maxMv = 4200;
        if (batteryMv <= minMv) {
            return 0;
        }
        if (batteryMv >= maxMv) {
            return 100;
        }
        return Math.round(100f * (batteryMv - minMv) / (maxMv - minMv));
    }

    public String getSelfPubKeyHex() {
        return selfPubKeyHex != null ? selfPubKeyHex : "";
    }

    public void queryMeshGpsEnabled() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
    }

    public void setMeshGpsEnabled(boolean enabled) {
        if (!connected.get()) {
            return;
        }
        byte[] txt = ("gps:" + (enabled ? "1" : "0")).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + txt.length];
        out[0] = CMD_SET_SETTING_TEXT;
        System.arraycopy(txt, 0, out, 1, txt.length);
        enqueueCommand(out);
        enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
    }

    public void setSendPositionWithAdvertEnabled(boolean enabled) {
        if (!connected.get()) {
            return;
        }
        byte[] out = new byte[5];
        out[0] = CMD_SET_OTHER_PARAMS;
        out[1] = (byte) (cachedManualAddContacts & 0xFF);
        out[2] = (byte) (cachedTelemetryModes & 0xFF);
        out[3] = (byte) ((enabled ? ADVERT_LOC_SHARE : ADVERT_LOC_NONE) & 0xFF);
        out[4] = (byte) (cachedMultiAcks & 0xFF);
        enqueueCommand(out);
        // Refresh the authoritative state from node self-info.
        enqueueCommand(buildAppStartCommand());
    }

    public boolean setNodeAdvertName(String nodeName) {
        if (!connected.get() || nodeName == null) {
            return false;
        }
        String trimmed = nodeName.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        byte[] nameBytes = trimmed.getBytes(StandardCharsets.UTF_8);
        int maxLen = 31; // firmware stores node_name[32] including null terminator
        int n = Math.min(nameBytes.length, maxLen);
        byte[] out = new byte[1 + n];
        out[0] = CMD_SET_ADVERT_NAME;
        System.arraycopy(nameBytes, 0, out, 1, n);
        enqueueCommand(out);
        enqueueCommand(buildAppStartCommand());
        return true;
    }

    public boolean setRadioParams(double frequencyMHz, double bandwidthKHz, int sf, int cr) {
        if (!connected.get()) {
            return false;
        }
        int freqKHz = (int) Math.round(frequencyMHz * 1000.0);
        int bwHz = (int) Math.round(bandwidthKHz * 1000.0);
        if (freqKHz < 150000 || freqKHz > 2500000
                || bwHz < 7000 || bwHz > 500000
                || sf < 5 || sf > 12
                || cr < 5 || cr > 8) {
            return false;
        }
        byte[] out = new byte[11];
        ByteBuffer bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(CMD_SET_RADIO_PARAMS);
        bb.putInt(freqKHz);
        bb.putInt(bwHz);
        bb.put((byte) (sf & 0xFF));
        bb.put((byte) (cr & 0xFF));
        enqueueCommand(out);
        enqueueCommand(buildAppStartCommand());
        return true;
    }

    public boolean setRadioTxPowerDbm(int txPowerDbm) {
        if (!connected.get()) {
            return false;
        }
        byte[] out = new byte[2];
        out[0] = CMD_SET_RADIO_TX_POWER;
        out[1] = (byte) txPowerDbm;
        enqueueCommand(out);
        enqueueCommand(buildAppStartCommand());
        return true;
    }

    public void requestSelfInfo() {
        if (!connected.get()) {
            return;
        }
        enqueueCommand(buildAppStartCommand());
    }

    public boolean sendSelfAdvert() {
        if (!connected.get()) {
            return false;
        }
        enqueueCommand(new byte[]{CMD_SEND_SELF_ADVERT});
        return true;
    }

    public void addBeforeDisconnectHook(Runnable hook) {
        if (hook != null) {
            beforeDisconnectHooks.add(hook);
        }
    }

    public void removeBeforeDisconnectHook(Runnable hook) {
        beforeDisconnectHooks.remove(hook);
    }

    private void runBeforeDisconnectHooks() {
        for (Runnable hook : beforeDisconnectHooks) {
            try {
                hook.run();
            } catch (Exception e) {
                Log.w(TAG, "beforeDisconnect hook failed: " + e.getMessage());
            }
        }
    }

    private void notifyConnected(BluetoothDevice device) {
        cancelPassiveMeshWatch();
        // Remember this as the startup auto-connect target (replaces the old favorite mechanism).
        if (device != null && device.getAddress() != null) {
            try {
                BluetoothDeviceRegistry.setConnectTargetAddress(context, device.getAddress());
            } catch (Exception e) {
                Log.w(TAG, "Could not persist last-connected mesh target", e);
            }
        }
        for (ConnectionListener l : listeners) l.onConnected(device);
    }

    private void notifyDisconnected(String reason) {
        for (ConnectionListener l : listeners) l.onDisconnected(reason);
    }

    private void notifyError(String error) {
        for (ConnectionListener l : listeners) l.onError(error);
    }

    private void notifyDeviceFound(BluetoothDevice device) {
        for (ConnectionListener l : listeners) l.onDeviceFound(device);
    }

    private void notifyScanComplete() {
        for (ConnectionListener l : listeners) l.onScanComplete();
    }

    private void notifyMeshGpsStateChanged(boolean enabled) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshGpsStateChanged(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifySendPositionWithAdvertChanged(boolean enabled) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onSendPositionWithAdvertChanged(enabled);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshNodeSettingsUpdated(MeshNodeSettings settings) {
        if (settings == null) {
            return;
        }
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshNodeSettingsUpdated(settings);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshSelfLocation(MeshLocationFix fix) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshSelfLocationUpdated(fix);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshBatteryUpdated(int batteryMv, int batteryPercent) {
        for (MeshStateListener l : meshStateListeners) {
            try {
                l.onMeshBatteryUpdated(batteryPercent, batteryMv);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshAdvert(MeshAdvert advert) {
        for (MeshAdvertListener l : meshAdvertListeners) {
            try {
                l.onMeshAdvert(advert);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyRepeaterAdvert(RepeaterAdvert advert) {
        for (RepeaterAdvertListener l : repeaterAdvertListeners) {
            try {
                l.onRepeaterAdvert(advert);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshChannelInfo(MeshChannelInfo info) {
        for (MeshChannelListener l : meshChannelListeners) {
            try {
                l.onChannelInfo(info);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyMeshChannelMessage(MeshChannelMessage message) {
        for (MeshChannelListener l : meshChannelListeners) {
            try {
                l.onChannelMessage(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }

    private void stopScanInternal() {
        stopScanInternal(true);
    }

    private void stopScanInternal(boolean invalidateSession) {
        if (bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }
        scanCallback = null;
        if (invalidateSession) {
            activeScanSessionId = 0;
        }
    }

    private void closeGattInternal() {
        try {
            if (gatt != null) {
                gatt.disconnect();
                gatt.close();
            }
        } catch (Exception ignored) {}
        gatt = null;
        rxCharacteristic = null;
        txCharacteristic = null;
    }

    public void shutdown() {
        pendingBondDevice = null;
        if (bondReceiverRegistered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(bondReceiver);
            } catch (Exception ignored) {
            }
        }
        bondReceiver = null;
    }

    private void clearQueues() {
        synchronized (writeQueue) {
            writeQueue.clear();
            writeInFlight = false;
        }
        chunkBuffers.clear();
    }

    private void enqueueCommand(byte[] cmd) {
        if (cmd == null || cmd.length == 0) return;
        int type = cmd[0] & 0xFF;
        synchronized (writeQueue) {
            writeQueue.addLast(cmd);
            Log.d(TAG, "Queue cmd type=0x" + Integer.toHexString(type)
                    + " len=" + cmd.length + " q=" + writeQueue.size());
            if (writeInFlight) {
                return;
            }
            writeInFlight = true;
        }
        ioHandler.post(this::drainWriteQueue);
    }

    private void drainWriteQueue() {
        while (connected.get()) {
            byte[] next;
            synchronized (writeQueue) {
                next = writeQueue.peekFirst();
                if (next == null) {
                    writeInFlight = false;
                    return;
                }
            }
            if (gatt == null || rxCharacteristic == null) {
                synchronized (writeQueue) {
                    writeInFlight = false;
                }
                return;
            }
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rxCharacteristic.setValue(next);
            boolean started = gatt.writeCharacteristic(rxCharacteristic);
            if (!started) {
                Log.w(TAG, "writeCharacteristic returned false; retrying cmd type=0x"
                        + Integer.toHexString(next[0] & 0xFF));
                synchronized (writeQueue) {
                    writeInFlight = false;
                }
                ioHandler.postDelayed(this::drainWriteQueue, 150L);
                return;
            }
            return; // wait for callback
        }
        synchronized (writeQueue) {
            writeInFlight = false;
        }
    }

    private byte[] buildAppStartCommand() {
        byte[] app = COMPANION_APP_ID.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[8 + app.length];
        out[0] = CMD_APP_START;
        System.arraycopy(app, 0, out, 8, app.length);
        return out;
    }

    private byte[] buildDeviceQueryCommand() {
        return new byte[]{CMD_DEVICE_QUERY, CMD_DEVICE_QUERY_ARG};
    }

    private byte[] buildGetNextMessageCommand() {
        return new byte[]{CMD_GET_NEXT_MSG};
    }

    private byte[] buildGetContactByKeyCommand(byte[] advertFrame) {
        if (advertFrame == null || advertFrame.length < 33) {
            return null;
        }
        byte[] out = new byte[33];
        out[0] = CMD_GET_CONTACT_BY_KEY;
        System.arraycopy(advertFrame, 1, out, 1, 32);
        return out;
    }

    private byte[] buildGetChannelInfoCommand(int idx) {
        return new byte[]{CMD_GET_CHANNEL, (byte) (idx & 0xFF)};
    }

    private byte[] buildSetChannelCommand(int idx, String name, byte[] secret) {
        byte[] out = new byte[1 + 1 + 32 + 16];
        out[0] = CMD_SET_CHANNEL;
        out[1] = (byte) (idx & 0xFF);
        byte[] nameBytes = name != null
                ? name.getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        int nameLen = Math.min(32, nameBytes.length);
        System.arraycopy(nameBytes, 0, out, 2, nameLen);
        if (secret != null) {
            int secLen = Math.min(16, secret.length);
            System.arraycopy(secret, 0, out, 34, secLen);
        }
        return out;
    }

    private byte[] buildSendChannelMessageCommand(int channel, String text) {
        byte[] msg = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(7 + msg.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_CHANNEL_MSG);
        buf.put((byte) 0x00);
        buf.put((byte) Math.max(0, Math.min(7, channel)));
        buf.putInt((int) (System.currentTimeMillis() / 1000L));
        buf.put(msg);
        Log.d(TAG, "TX channel msg channel=" + channel + " textLen=" + msg.length);
        return buf.array();
    }

    private byte[] buildSendTxtMsgCommand(byte[] pubKeyPrefix6, String text) {
        if (pubKeyPrefix6 == null || pubKeyPrefix6.length != 6) {
            return null;
        }
        byte[] msg = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(13 + msg.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_TXT_MSG);
        buf.put(TXT_TYPE_PLAIN);
        buf.put((byte) 0x00); // attempt
        buf.putInt((int) (System.currentTimeMillis() / 1000L));
        buf.put(pubKeyPrefix6);
        buf.put(msg);
        return buf.array();
    }

    private byte[] buildSendChannelDataCommand(int channel, int dataType, byte[] payload) {
        int payloadLen = payload != null ? payload.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(6 + payloadLen);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put(CMD_SEND_CHANNEL_DATA);
        buf.put((byte) Math.max(0, Math.min(7, channel)));
        buf.put((byte) OUT_PATH_FLOOD);
        buf.put((byte) (dataType & 0xFF));
        buf.put((byte) ((dataType >> 8) & 0xFF));
        if (payloadLen > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    private int getMeshChannelIndex() {
        return ATAK_CHANNEL_INDEX;
    }

    private void handleCompanionPacket(byte[] pkt) {
        if (pkt == null || pkt.length == 0) return;
        markIoActivity();
        pruneStaleChunks();
        byte t = pkt[0];
        Log.d(TAG, "RX pkt type=0x" + Integer.toHexString(t & 0xFF) + " len=" + pkt.length);
        if (t == PUSH_MESSAGES_WAITING) {
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == PUSH_CODE_LOG_RX_DATA) {
            handleLogRxData(pkt);
            // Some firmware builds emit LOG_RX_DATA first, then queue follow-up companion frames
            // (including advert/contact refreshes). Prompt an immediate drain.
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == PUSH_CODE_SEND_CONFIRMED) {
            handleSendConfirmed(pkt);
            return;
        }
        if (t == RESP_NO_MORE_MSGS) {
            return;
        }
        if (t == RESP_SETTING_TEXT) {
            applySettingText(pkt);
            return;
        }
        if (t == RESP_CHANNEL_INFO) {
            applyChannelInfo(pkt);
            return;
        }
        if (t == RESP_SELF_INFO) {
            logSelfInfo(pkt);
            return;
        }
        if (t == RESP_BATTERY) {
            applyBatteryInfo(pkt);
            return;
        }
        if (t == RESP_CODE_STATS) {
            applyStatsBattery(pkt);
            return;
        }
        if (t == RESP_DEVICE_INFO) {
            logDeviceInfo(pkt);
            return;
        }
        if (t == RESP_CHANNEL_DATA_RECV) {
            handleChannelData(pkt);
            enqueueCommand(buildGetNextMessageCommand());
            return;
        }
        if (t == PUSH_CODE_NEW_ADVERT || t == PUSH_CODE_ADVERT || t == RESP_CODE_CONTACT) {
            if (t == PUSH_CODE_ADVERT) {
                requestFullContactForAdvertRefresh(pkt);
            }
            MeshAdvert meshAdvert = parseMeshAdvert(pkt);
            if (meshAdvert != null) {
                if (!meshAdvert.isRepeater()) {
                    maybeToastNodeDiscovery(meshAdvert);
                }
                notifyMeshAdvert(meshAdvert);
                if (meshAdvert.isRepeater()) {
                    RepeaterAdvert advert = repeaterAdvertFromMesh(meshAdvert);
                    maybeToastRepeaterDiscovery(advert);
                    notifyRepeaterAdvert(advert);
                }
            }
            if (t == PUSH_CODE_NEW_ADVERT || t == PUSH_CODE_ADVERT) {
                return;
            }
        }

        String message = null;
        if (t == RESP_CHANNEL_MSG) {
            message = extractChannelText(pkt, false);
        } else if (t == RESP_CHANNEL_MSG_V3) {
            message = extractChannelText(pkt, true);
        } else if (t == RESP_CONTACT_MSG) {
            message = extractContactText(pkt, false);
        } else if (t == RESP_CONTACT_MSG_V3) {
            message = extractContactText(pkt, true);
        }
        if (message != null) {
            int envPathLen = 0;
            if (t == RESP_CHANNEL_MSG || t == RESP_CHANNEL_MSG_V3) {
                ChannelMessageMeta meta = extractChannelMessageMeta(pkt, t == RESP_CHANNEL_MSG_V3);
                String statusText = extractChannelStatusText(message);
                notifyMeshChannelMessage(new MeshChannelMessage(
                        meta.channelIndex,
                        message,
                        System.currentTimeMillis(),
                        false,
                        statusText,
                        meta.snrQuarterDb,
                        meta.pathLen,
                        meta.senderTimestampSec));
                envPathLen = meta.pathLen != null ? meta.pathLen : 0;
            }
            String routed = extractRoutableEnvelope(message);
            if (routed != null) {
                Log.d(TAG, "RX mesh env len=" + routed.length());
                handleMeshMessage(routed, envPathLen);
            } else if (t == RESP_CONTACT_MSG || t == RESP_CONTACT_MSG_V3) {
                // Native pubkey-to-pubkey DM (plain text, no UVAX1|/__UVGW__ envelope).
                // Route it into ATAK GeoChat keyed by the sender's pubkey prefix.
                String senderPrefixHex = extractContactSenderPubKeyPrefix(pkt, t == RESP_CONTACT_MSG_V3);
                if (senderPrefixHex != null && !senderPrefixHex.isEmpty()
                        && !message.trim().isEmpty()) {
                    packetRouter.routeNativeMeshDm(senderPrefixHex, message.trim());
                }
            } else {
                Log.d(TAG, "RX non-env text len=" + message.length());
            }
            // Drain firmware queue quickly while messages are available.
            enqueueCommand(buildGetNextMessageCommand());
        }
    }

    private String extractRoutableEnvelope(String text) {
        if (text == null) {
            return null;
        }
        int p = text.indexOf(ENV_PREFIX);
        if (p < 0) {
            return null;
        }
        return text.substring(p).trim();
    }

    private void handleChannelData(byte[] pkt) {
        if (pkt == null || pkt.length < 9) {
            return;
        }
        int dataType = ((pkt[7] & 0xFF) << 8) | (pkt[6] & 0xFF);
        int dataLen = pkt[8] & 0xFF;
        int available = pkt.length - 9;
        if (available <= 0 || dataLen <= 0) {
            return;
        }
        int copyLen = Math.min(dataLen, available);
        if (dataType != ATAK_DATA_TYPE_AX25 && dataType != ATAK_DATA_TYPE_RAW) {
            return;
        }
        String text = new String(pkt, 9, copyLen, StandardCharsets.UTF_8);
        String routed = extractRoutableEnvelope(text);
        if (routed != null) {
            Log.d(TAG, "RX mesh datagram env len=" + routed.length());
            handleMeshMessage(routed, 0);
        }
    }

    private void applyChannelInfo(byte[] pkt) {
        if (pkt == null || pkt.length < 50) {
            return;
        }
        int idx = pkt[1] & 0xFF;
        if (idx < 0 || idx > 7) {
            return;
        }
        // bytes 2..33 are null-padded UTF-8 channel name
        String raw = new String(pkt, 2, 32, StandardCharsets.UTF_8);
        int nul = raw.indexOf('\0');
        String name = (nul >= 0 ? raw.substring(0, nul) : raw).trim();
        String secretFp = channelSecretFingerprint(pkt, 34, 16);
        byte[] secret = new byte[16];
        System.arraycopy(pkt, 34, secret, 0, 16);
        channelSecretsByIndex.put(idx, secret);
        meshChannelNamesByIndex.put(idx, name);
        notifyMeshChannelInfo(new MeshChannelInfo(idx, name));
        Log.d(TAG, "Channel slot " + idx + " name='" + name + "' secretFp=" + secretFp);
        if (idx == ATAK_CHANNEL_INDEX) {
            Log.i(TAG, "ATAK channel slot " + idx + " name='" + name + "' secretFp=" + secretFp);
        }
    }

    private void logSelfInfo(byte[] pkt) {
        // Layout from companion firmware:
        // [0]=code [1]=advType [2]=txPower [3]=maxTx [4..35]=pubkey
        // [36..39]=latE6 [40..43]=lonE6 [44]=multiAck [45]=advertLoc [46]=telem
        // [47]=manualAdd [48..51]=freqHz [52..55]=bwHz [56]=sf [57]=cr [58..]=name
        try {
            if (pkt.length < 58) {
                Log.d(TAG, "SELF info short len=" + pkt.length);
                return;
            }
            applySelfInfoOtherParams(pkt);
            applySelfInfoNodeSettings(pkt);
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            int latE6 = bb.getInt(36);
            int lonE6 = bb.getInt(40);
            selfPubKeyHex = bytesToHex(pkt, 4, 32);
            long freqHz = ((long) bb.getInt(48)) & 0xFFFFFFFFL;
            long bwHz = ((long) bb.getInt(52)) & 0xFFFFFFFFL;
            int sf = pkt[56] & 0xFF;
            int cr = pkt[57] & 0xFF;
            String node = "";
            if (pkt.length > 58) {
                node = new String(pkt, 58, pkt.length - 58, StandardCharsets.UTF_8).trim();
            }
            double lat = latE6 / 1_000_000.0;
            double lon = lonE6 / 1_000_000.0;
            Log.i(TAG, "Node self-info name='" + node + "' freqHz=" + freqHz
                    + " bwHz=" + bwHz + " sf=" + sf + " cr=" + cr
                    + " lat=" + lat + " lon=" + lon);
            MeshLocationFix fix = new MeshLocationFix(lat, lon, System.currentTimeMillis(), node);
            if (fix.isValid()) {
                latestSelfLocation = fix;
                notifyMeshSelfLocation(fix);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed parsing self-info: " + e.getMessage());
        }
    }

    private void applySelfInfoOtherParams(byte[] pkt) {
        if (pkt == null) {
            return;
        }
        // Some firmware builds include 3 bytes (advType/tx/maxTx) before pubkey, some do not.
        int[][] candidates = new int[][]{
                {41, 42, 43, 44}, // companion_radio upstream layout
                {38, 39, 40, 41}  // legacy/trimmed layout
        };
        for (int[] c : candidates) {
            int multiIdx = c[0];
            int advertIdx = c[1];
            int telemetryIdx = c[2];
            int manualIdx = c[3];
            if (pkt.length <= manualIdx) {
                continue;
            }
            int advertPolicy = pkt[advertIdx] & 0xFF;
            if (advertPolicy < ADVERT_LOC_NONE || advertPolicy > 2) {
                continue;
            }
            cachedMultiAcks = pkt[multiIdx] & 0xFF;
            cachedTelemetryModes = pkt[telemetryIdx] & 0xFF;
            cachedManualAddContacts = pkt[manualIdx] & 0xFF;
            boolean enabled = advertPolicy != ADVERT_LOC_NONE;
            if (sendPositionWithAdvertEnabled == null
                    || sendPositionWithAdvertEnabled.booleanValue() != enabled) {
                sendPositionWithAdvertEnabled = enabled;
                notifySendPositionWithAdvertChanged(enabled);
            } else {
                sendPositionWithAdvertEnabled = enabled;
            }
            return;
        }
    }

    private void applySelfInfoNodeSettings(byte[] pkt) {
        if (pkt == null) {
            return;
        }
        // Self-info layout differs across firmware branches; probe known offsets.
        int[][] candidates = new int[][]{
                {48, 52, 56, 57, 2, 3, 58}, // companion_radio upstream layout
                {45, 49, 53, 54, 1, 2, 55}  // legacy layout without advType
        };
        for (int[] c : candidates) {
            int freqIdx = c[0];
            int bwIdx = c[1];
            int sfIdx = c[2];
            int crIdx = c[3];
            int txIdx = c[4];
            int maxTxIdx = c[5];
            int nameIdx = c[6];
            if (pkt.length <= crIdx || pkt.length <= txIdx || pkt.length <= maxTxIdx
                    || pkt.length < nameIdx) {
                continue;
            }
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            long freqKHz = ((long) bb.getInt(freqIdx)) & 0xFFFFFFFFL;
            long bwHz = ((long) bb.getInt(bwIdx)) & 0xFFFFFFFFL;
            int sf = pkt[sfIdx] & 0xFF;
            int cr = pkt[crIdx] & 0xFF;
            int txPower = (int) pkt[txIdx];
            int maxTxPower = pkt[maxTxIdx] & 0xFF;
            if (freqKHz < 150000 || freqKHz > 2500000
                    || bwHz < 7000 || bwHz > 500000
                    || sf < 5 || sf > 12
                    || cr < 5 || cr > 8) {
                continue;
            }
            String name = "";
            if (pkt.length > nameIdx) {
                name = new String(pkt, nameIdx, pkt.length - nameIdx, StandardCharsets.UTF_8).trim();
            }
            MeshNodeSettings settings = new MeshNodeSettings(
                    name,
                    freqKHz / 1000.0,
                    bwHz / 1000.0,
                    sf,
                    cr,
                    txPower,
                    maxTxPower,
                    System.currentTimeMillis());
            latestNodeSettings = settings;
            notifyMeshNodeSettingsUpdated(settings);
            return;
        }
    }

    private void applySettingText(byte[] pkt) {
        if (pkt == null || pkt.length < 2) {
            return;
        }
        try {
            String text = new String(pkt, 1, pkt.length - 1, StandardCharsets.UTF_8).trim();
            if (text.startsWith("gps:")) {
                boolean enabled = text.endsWith("1")
                        || text.equalsIgnoreCase("gps:on")
                        || text.equalsIgnoreCase("gps:true");
                meshGpsEnabled = enabled;
                notifyMeshGpsStateChanged(enabled);
                return;
            }
            if (text.startsWith("adloc:") || text.startsWith("advert_loc:")) {
                boolean enabled = text.endsWith("1")
                        || text.endsWith("2")
                        || text.equalsIgnoreCase("adloc:on")
                        || text.equalsIgnoreCase("adloc:true");
                sendPositionWithAdvertEnabled = enabled;
                notifySendPositionWithAdvertChanged(enabled);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyBatteryInfo(byte[] pkt) {
        if (pkt == null || pkt.length < 3) {
            return;
        }
        int batteryMv = (pkt[1] & 0xFF) | ((pkt[2] & 0xFF) << 8);
        publishBatteryReading(batteryMv, "PACKET_BATTERY");
    }

    private void applyStatsBattery(byte[] pkt) {
        if (pkt == null || pkt.length < 4 || (pkt[1] & 0xFF) != STATS_TYPE_CORE) {
            return;
        }
        int batteryMv = (pkt[2] & 0xFF) | ((pkt[3] & 0xFF) << 8);
        publishBatteryReading(batteryMv, "STATS_CORE");
    }

    private void publishBatteryReading(int batteryMv, String source) {
        int batteryPercent = meshBatteryMvToPercent(batteryMv);
        if (batteryPercent < 0) {
            return;
        }
        latestBatteryMv = batteryMv;
        latestBatteryPercent = batteryPercent;
        Log.d(TAG, source + " mv=" + batteryMv + " pct=" + batteryPercent);
        notifyMeshBatteryUpdated(batteryMv, batteryPercent);
    }

    private void logDeviceInfo(byte[] pkt) {
        // Mostly build/manufacturer metadata; useful for confirming both sides are same firmware branch.
        try {
            if (pkt.length < 4) {
                return;
            }
            int fwCode = pkt[1] & 0xFF;
            int maxContactsHalf = pkt[2] & 0xFF;
            int maxChannels = pkt[3] & 0xFF;
            String manufacturer = "";
            String version = "";
            if (pkt.length >= 77) {
                manufacturer = decodeCString(pkt, 20, 40);
                version = decodeCString(pkt, 60, 20);
            }
            Log.i(TAG, "Device info fwCode=" + fwCode + " maxChannels=" + maxChannels
                    + " maxContacts=" + (maxContactsHalf * 2)
                    + " mfg='" + manufacturer + "' ver='" + version + "'");
        } catch (Exception e) {
            Log.w(TAG, "Failed parsing device-info: " + e.getMessage());
        }
    }

    private String decodeCString(byte[] src, int off, int maxLen) {
        if (src == null || off < 0 || maxLen <= 0 || src.length <= off) {
            return "";
        }
        int end = Math.min(src.length, off + maxLen);
        int n = off;
        while (n < end && src[n] != 0) n++;
        return new String(src, off, n - off, StandardCharsets.UTF_8).trim();
    }

    private String bytesToHex(byte[] src, int off, int len) {
        if (src == null || off < 0 || len <= 0 || src.length < off + len) {
            return "";
        }
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < off + len; i++) {
            int b = src[i] & 0xFF;
            if (b < 0x10) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    private MeshAdvert parseMeshAdvert(byte[] pkt) {
        // Required fields are within [1..143]; accept shorter variants from firmware forks.
        if (pkt == null || pkt.length < 144) {
            return null;
        }
        try {
            int type = pkt[33] & 0xFF;
            String pubKeyHex = bytesToHex(pkt, 1, 32);
            if (pubKeyHex.isEmpty()) {
                return null;
            }

            String rawName = new String(pkt, 100, 32, StandardCharsets.UTF_8);
            int nul = rawName.indexOf('\0');
            String name = (nul >= 0 ? rawName.substring(0, nul) : rawName).trim();
            if (name.isEmpty()) {
                name = "Mesh Repeater";
            }

            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            long tsSec = ((long) bb.getInt(132)) & 0xFFFFFFFFL;
            int latE6 = bb.getInt(136);
            int lonE6 = bb.getInt(140);
            double lat = latE6 / 1_000_000.0;
            double lon = lonE6 / 1_000_000.0;
            boolean hasPosition = !(latE6 == 0 && lonE6 == 0);
            return new MeshAdvert(type, pubKeyHex, name, tsSec, lat, lon, hasPosition);
        } catch (Exception e) {
            Log.w(TAG, "Mesh advert parse failed", e);
            return null;
        }
    }

    private RepeaterAdvert repeaterAdvertFromMesh(MeshAdvert advert) {
        return new RepeaterAdvert(
                advert.pubKeyHex,
                advert.name,
                advert.advertTimestampSec,
                advert.latitude,
                advert.longitude,
                advert.hasPosition);
    }

    private void requestFullContactForAdvertRefresh(byte[] pkt) {
        if (pkt == null || pkt.length < 33) {
            return;
        }
        try {
            String pubKeyHex = bytesToHex(pkt, 1, 32);
            if (pubKeyHex.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            Long last = contactQueryThrottleMsByPubKey.get(pubKeyHex);
            if (last != null && (now - last) < 1500L) {
                return;
            }
            contactQueryThrottleMsByPubKey.put(pubKeyHex, now);
            byte[] cmd = buildGetContactByKeyCommand(pkt);
            if (cmd != null) {
                enqueueCommand(cmd);
            }
            Log.d(TAG, "Advert refresh 0x80 → requesting full contact for pubkey=" + pubKeyHex);
        } catch (Exception e) {
            Log.w(TAG, "Failed to request full contact for advert refresh", e);
        }
    }

    private void maybeToastRepeaterDiscovery(RepeaterAdvert advert) {
        if (advert == null || context == null) {
            return;
        }
        String dedupKey = advert.pubKeyHex + ":" + advert.advertTimestampSec;
        Long previous = repeaterToastDedupByPubKeyTs.putIfAbsent(dedupKey, advert.advertTimestampSec);
        if (previous != null) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                String text = "New repeater discovered, #" + advert.name;
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    private void maybeToastNodeDiscovery(MeshAdvert advert) {
        if (advert == null || context == null) {
            return;
        }
        String dedupKey = advert.pubKeyHex + ":" + advert.advertTimestampSec;
        Long previous = nodeToastDedupByPubKeyTs.putIfAbsent(dedupKey, advert.advertTimestampSec);
        if (previous != null) {
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                String name = advert.name != null ? advert.name.trim() : "";
                if (name.isEmpty()) {
                    name = "Node";
                }
                Toast.makeText(context,
                        "New Node Discovered-#" + name,
                        Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        });
    }

    /** Sender pubkey prefix (6 bytes) from a contact-message frame: bytes 1..6 (v1) or 4..9 (v3). */
    private String extractContactSenderPubKeyPrefix(byte[] pkt, boolean v3) {
        int off = v3 ? 4 : 1;
        if (pkt == null || pkt.length < off + 6) {
            return null;
        }
        return bytesToHex(pkt, off, 6);
    }

    private void handleSendConfirmed(byte[] pkt) {
        long tripMs = 0L;
        try {
            if (pkt != null && pkt.length >= 9) {
                tripMs = ((long) ByteBuffer.wrap(pkt, 5, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt()) & 0xffffffffL;
            }
        } catch (Exception ignored) {
            tripMs = 0L;
        }
        PendingChannelText pending = null;
        synchronized (pendingChannelTextSends) {
            while (!pendingChannelTextSends.isEmpty()) {
                PendingChannelText first = pendingChannelTextSends.removeFirst();
                if ((System.currentTimeMillis() - first.queuedAtMs) <= 120_000L) {
                    pending = first;
                    break;
                }
            }
        }
        if (pending != null) {
            String status = tripMs > 0L
                    ? ("heard (repeat count pending, ack " + tripMs + "ms)")
                    : "heard (repeat count pending)";
            notifyMeshChannelMessage(new MeshChannelMessage(
                    pending.channelIndex,
                    pending.text,
                    System.currentTimeMillis(),
                    true,
                    status,
                    null,
                    null,
                    null));
            Log.d(TAG, "TX confirm ch=" + pending.channelIndex + " tripMs=" + tripMs);
        } else {
            Log.d(TAG, "TX confirm with no pending channel text");
        }
    }

    private void handleLogRxData(byte[] pkt) {
        if (pkt == null || pkt.length < 4) {
            return;
        }
        Integer repeats = extractRepeatsFromLogRawPacket(pkt, 3, pkt.length - 3);
        if (repeats == null) {
            return;
        }
        int snrQ = (int) pkt[1];
        PendingChannelText pending = null;
        synchronized (pendingChannelTextSends) {
            long now = System.currentTimeMillis();
            while (!pendingChannelTextSends.isEmpty()) {
                PendingChannelText first = pendingChannelTextSends.peekFirst();
                if (first == null) {
                    pendingChannelTextSends.removeFirst();
                    continue;
                }
                if ((now - first.queuedAtMs) > 20_000L) {
                    pendingChannelTextSends.removeFirst();
                    continue;
                }
                pending = pendingChannelTextSends.removeFirst();
                break;
            }
        }
        if (pending == null) {
            return;
        }
        String status = "heard " + Math.max(0, repeats) + " repeats";
        notifyMeshChannelMessage(new MeshChannelMessage(
                pending.channelIndex,
                pending.text,
                System.currentTimeMillis(),
                true,
                status,
                snrQ,
                repeats,
                null));
        Log.d(TAG, "TX log-rx confirm ch=" + pending.channelIndex + " status=" + status
                + " snrQ=" + snrQ);
    }

    private Integer extractRepeatsFromLogRawPacket(byte[] src, int offset, int len) {
        if (src == null || len <= 1 || offset < 0 || (offset + len) > src.length) {
            return null;
        }
        // Raw packet blob is mesh::Packet::writeTo(): [header][optional transport(4)][path_len][path...][payload...]
        // Try both common layouts (with/without transport codes).
        int[] pathLenOffsets = {1, 5};
        for (int pathOff : pathLenOffsets) {
            if (len <= pathOff) {
                continue;
            }
            int idx = offset + pathOff;
            int pathLen = src[idx] & 0xFF;
            int hashCount = pathLen & 0x3F;
            int hashSize = ((pathLen >> 6) & 0x03) + 1;
            int pathBytes = hashCount * hashSize;
            if (hashCount > 63) {
                continue;
            }
            if (pathBytes < 0 || pathBytes > 64) {
                continue;
            }
            if ((pathOff + 1 + pathBytes) > len) {
                continue;
            }
            return hashCount;
        }
        return null;
    }

    private static final class ChannelMessageMeta {
        final int channelIndex;
        final Integer snrQuarterDb;
        final Integer pathLen;
        final Integer senderTimestampSec;

        ChannelMessageMeta(int channelIndex, Integer snrQuarterDb, Integer pathLen,
                           Integer senderTimestampSec) {
            this.channelIndex = channelIndex;
            this.snrQuarterDb = snrQuarterDb;
            this.pathLen = pathLen;
            this.senderTimestampSec = senderTimestampSec;
        }
    }

    private ChannelMessageMeta extractChannelMessageMeta(byte[] pkt, boolean v3) {
        if (pkt == null) {
            return new ChannelMessageMeta(-1, null, null, null);
        }
        try {
            if (v3) {
                if (pkt.length < 11) {
                    return new ChannelMessageMeta(-1, null, null, null);
                }
                int snrQ = (int) pkt[1];
                int idx = pkt[4] & 0xFF;   // expected v3 layout
                int path = pkt[5] & 0xFF;
                int ts = ByteBuffer.wrap(pkt, 7, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                // Firmware variants can occasionally shift this layout.
                // Fallback to legacy-style offsets if parsed index is invalid.
                if (idx < 0 || idx > 7) {
                    int altIdx = pkt[1] & 0xFF;
                    int altPath = pkt.length > 2 ? (pkt[2] & 0xFF) : 0xFF;
                    Integer altTs = null;
                    if (pkt.length >= 8) {
                        altTs = ByteBuffer.wrap(pkt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                    }
                    if (altIdx >= 0 && altIdx <= 7) {
                        idx = altIdx;
                        path = altPath;
                        ts = altTs != null ? altTs : ts;
                    }
                }
                return new ChannelMessageMeta(
                        (idx >= 0 && idx <= 7) ? idx : -1,
                        snrQ,
                        path == 0xFF ? null : path,
                        ts);
            } else {
                if (pkt.length < 8) {
                    return new ChannelMessageMeta(-1, null, null, null);
                }
                int idx = pkt[1] & 0xFF;
                int path = pkt[2] & 0xFF;
                int ts = ByteBuffer.wrap(pkt, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                return new ChannelMessageMeta(
                        (idx >= 0 && idx <= 7) ? idx : -1,
                        null,
                        path == 0xFF ? null : path,
                        ts);
            }
        } catch (Exception ignored) {
            return new ChannelMessageMeta(-1, null, null, null);
        }
    }

    private String extractChannelStatusText(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.US);
        int heard = lower.indexOf("heard");
        int repeat = lower.indexOf("repeat");
        if (heard >= 0 && repeat > heard) {
            int end = Math.min(text.length(), repeat + "repeat".length() + 3);
            return text.substring(heard, end).trim();
        }
        return "";
    }

    private String channelSecretFingerprint(byte[] frame, int off, int len) {
        if (frame == null || off < 0 || len <= 0 || frame.length < off + len) {
            return "invalid";
        }
        int end = Math.min(off + len, frame.length);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = off; i < end; i++) {
            int b = frame[i] & 0xFF;
            if (b < 0x10) sb.append('0');
            sb.append(Integer.toHexString(b));
        }
        return sb.toString();
    }

    private String extractChannelText(byte[] pkt, boolean v3) {
        int off = v3 ? 11 : 8;
        if (pkt.length < off) return null;
        return new String(pkt, off, pkt.length - off, StandardCharsets.UTF_8);
    }

    private String extractContactText(byte[] pkt, boolean v3) {
        int txtTypeIndex = v3 ? 11 : 8;
        int off = v3 ? 16 : 13;
        if (pkt.length < off) return null;
        byte txtType = pkt[txtTypeIndex];
        if (txtType == 2) off += 4;
        if (pkt.length < off) return null;
        return new String(pkt, off, pkt.length - off, StandardCharsets.UTF_8);
    }

    private void handleMeshMessage(String msg) {
        handleMeshMessage(msg, 0);
    }

    private void handleMeshMessage(String msg, int pathLen) {
        if (msg == null || !msg.startsWith(ENV_PREFIX)) return;
        String[] parts = msg.split("\\|", 5);
        if (parts.length != 5) return;
        try {
            int msgId = Integer.parseInt(parts[1]);
            int seq = Integer.parseInt(parts[2]);
            int total = Integer.parseInt(parts[3]);
            byte[] chunk = Base64.decode(parts[4], Base64.DEFAULT);
            if (chunk == null || total < 1 || seq < 1 || seq > total) return;

            ChunkAccumulator acc = chunkBuffers.get(msgId);
            if (acc == null || acc.total != total) {
                acc = new ChunkAccumulator(total);
                chunkBuffers.put(msgId, acc);
            }
            acc.parts.put(seq, chunk);
            acc.lastUpdateMs = System.currentTimeMillis();
            Log.d(TAG, "RX chunk msgId=" + msgId + " seq=" + seq + "/" + total
                    + " chunkBytes=" + chunk.length + " have=" + acc.parts.size());
            if (acc.parts.size() < total) return;

            int len = 0;
            for (int i = 1; i <= total; i++) {
                byte[] p = acc.parts.get(i);
                if (p == null) return;
                len += p.length;
            }
            byte[] ax25 = new byte[len];
            int off = 0;
            for (int i = 1; i <= total; i++) {
                byte[] p = acc.parts.get(i);
                System.arraycopy(p, 0, ax25, off, p.length);
                off += p.length;
            }
            chunkBuffers.remove(msgId);
            Log.d(TAG, "Reassembled msgId=" + msgId + " bytes=" + ax25.length);

            for (RawDataListener listener : rawDataListeners) {
                try {
                    if (listener.onRawBytes(ax25)) {
                        Log.d(TAG, "Raw listener consumed msgId=" + msgId);
                        return;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "RawDataListener failed: " + e.getMessage());
                }
            }
            Log.d(TAG, "Routing reassembled AX.25 bytes=" + ax25.length);
            packetRouter.routeIncoming(ax25, pathLen);
        } catch (Exception ignored) {
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "onConnectionStateChange status=" + status + " newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g;
                reconnectAttempts = 0;
                connected.set(false);
                g.requestMtu(512);
                g.discoverServices();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handleConnectionLost();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.i(TAG, "onServicesDiscovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("BLE service discovery failed");
                handleConnectionLost();
                return;
            }
            BluetoothGattService svc = g.getService(UUID_UART_SERVICE);
            if (svc == null) {
                notifyError("MeshCore UART service not found");
                handleConnectionLost();
                return;
            }
            rxCharacteristic = svc.getCharacteristic(UUID_UART_RX);
            txCharacteristic = svc.getCharacteristic(UUID_UART_TX);
            if (rxCharacteristic == null || txCharacteristic == null) {
                notifyError("MeshCore RX/TX characteristic missing");
                handleConnectionLost();
                return;
            }
            g.setCharacteristicNotification(txCharacteristic, true);
            BluetoothGattDescriptor ccc = txCharacteristic.getDescriptor(UUID_CCC);
            if (ccc == null) {
                notifyError("MeshCore CCC descriptor missing");
                handleConnectionLost();
                return;
            }
            ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            g.writeDescriptor(ccc);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (!UUID_CCC.equals(descriptor.getUuid())) return;
            Log.i(TAG, "onDescriptorWrite CCC status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                notifyError("BLE notification enable failed");
                handleConnectionLost();
                return;
            }
            connecting.set(false);
            connected.set(true);
            markIoActivity();
            notifyConnected(lastDevice);
            enqueueCommand(buildAppStartCommand());
            enqueueCommand(buildDeviceQueryCommand());
            enqueueCommand(buildSetChannelCommand(
                    ATAK_CHANNEL_INDEX,
                    ATAK_CHANNEL_NAME,
                    ATAK_CHANNEL_SECRET));
            for (int i = 0; i < 8; i++) {
                enqueueCommand(buildGetChannelInfoCommand(i));
            }
            enqueueCommand(new byte[]{CMD_GET_GPS_STATE});
            enqueueCommand(buildGetNextMessageCommand());
            ioHandler.removeCallbacks(periodicMessagePoll);
            ioHandler.postDelayed(periodicMessagePoll, 2500L);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (characteristic == null || !UUID_UART_TX.equals(characteristic.getUuid())) return;
            handleCompanionPacket(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            byte[] sent = characteristic != null ? characteristic.getValue() : null;
            int type = (sent != null && sent.length > 0) ? (sent[0] & 0xFF) : -1;
            synchronized (writeQueue) {
                writeQueue.pollFirst();
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "BLE write failed: status=" + status + " cmdType=0x"
                        + Integer.toHexString(type));
            } else {
                Log.d(TAG, "BLE write ok cmdType=0x" + Integer.toHexString(type));
                markIoActivity();
            }
            drainWriteQueue();
        }
    };

    private static final class ChunkAccumulator {
        final int total;
        final Map<Integer, byte[]> parts = new ConcurrentHashMap<>();
        long lastUpdateMs;

        ChunkAccumulator(int total) {
            this.total = total;
            this.lastUpdateMs = System.currentTimeMillis();
        }
    }

    private void pruneStaleChunks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, ChunkAccumulator> e : chunkBuffers.entrySet()) {
            ChunkAccumulator acc = e.getValue();
            if (acc == null) continue;
            if (now - acc.lastUpdateMs > 120_000L) {
                chunkBuffers.remove(e.getKey());
            }
        }
    }
}
