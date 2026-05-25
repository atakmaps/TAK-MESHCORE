package com.uvpro.plugin.bluetooth;

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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.uvpro.plugin.protocol.PacketRouter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
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

    private static final String TAG = "UVPro.MeshBLE";

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
    private static final byte CMD_SEND_CHANNEL_MSG = 0x03;
    private static final byte CMD_GET_NEXT_MSG = 0x0A;
    private static final byte CMD_DEVICE_QUERY = 0x16;
    private static final byte CMD_DEVICE_QUERY_ARG = 0x03;
    private static final byte CMD_GET_CHANNEL = 0x1F;

    // Companion notifications
    private static final byte RESP_CHANNEL_MSG = 0x08;
    private static final byte RESP_CONTACT_MSG = 0x07;
    private static final byte RESP_SELF_INFO = 0x05;
    private static final byte RESP_DEVICE_INFO = 0x0D;
    private static final byte RESP_CHANNEL_MSG_V3 = 0x11;
    private static final byte RESP_CONTACT_MSG_V3 = 0x10;
    private static final byte RESP_CHANNEL_INFO = 0x12;
    private static final byte RESP_NO_MORE_MSGS = 0x0A;
    private static final byte PUSH_MESSAGES_WAITING = (byte) 0x83;

    private static final int MAX_MESH_MESSAGE_LEN = 130; // leave room below 133 chars
    private static final int MAX_RAW_AX25_CHUNK = 57; // 57 bytes -> 76 Base64 chars
    private static final String ENV_PREFIX = "UVAX1|";

    private final Context context;
    private final PacketRouter packetRouter;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private final AtomicBoolean radioSilenceEnabled = new AtomicBoolean(false);
    private final AtomicLong lastIoActivityMs = new AtomicLong(0L);
    private final AtomicInteger outboundMsgId = new AtomicInteger(1);
    private final AtomicInteger activeMeshChannel = new AtomicInteger(0);

    private final CopyOnWriteArrayList<ConnectionListener> listeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<RawDataListener> rawDataListeners =
            new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> beforeDisconnectHooks =
            new CopyOnWriteArrayList<>();

    private final Map<Integer, ChunkAccumulator> chunkBuffers = new ConcurrentHashMap<>();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writeInFlight = false;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothDevice lastDevice;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;

    private final HandlerThread ioThread = new HandlerThread("UVPro-MeshBLE-IO");
    private Handler ioHandler;
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

    public BtConnectionManager(Context context, PacketRouter packetRouter) {
        Context atakContext = com.atakmap.android.maps.MapView.getMapView() != null
                ? com.atakmap.android.maps.MapView.getMapView().getContext()
                : context;
        this.context = atakContext;
        this.packetRouter = packetRouter;
        this.btAdapter = BluetoothAdapter.getDefaultAdapter();
        ioThread.start();
        ioHandler = new Handler(ioThread.getLooper());
    }

    /**
     * Enumerates bonded BLE devices and starts an active BLE scan for MeshCore UART service.
     */
    public void startScan() {
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

        stopScanInternal();

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        if (pairedDevices != null) {
            for (BluetoothDevice device : pairedDevices) {
                String name = device.getName();
                if (name == null) name = device.getAddress();
                Log.i(TAG, "Bonded BLE device: " + name + " [" + device.getAddress() + "]");
                notifyDeviceFound(device);
            }
        }

        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            notifyScanComplete();
            return;
        }
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(UUID_UART_SERVICE))
                .build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device != null) {
                    notifyDeviceFound(device);
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.w(TAG, "BLE scan failed: " + errorCode);
                notifyError("BLE scan failed: " + errorCode);
            }
        };
        try {
            java.util.List<ScanFilter> filters = java.util.Collections.singletonList(filter);
            bleScanner.startScan(filters, settings, scanCallback);
            ioHandler.postDelayed(this::stopScanInternal, 8000L);
        } catch (Exception e) {
            Log.w(TAG, "BLE scan start failed", e);
        }
        notifyScanComplete();
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

    /**
     * Connect to a specific Bluetooth device.
     * Tries multiple socket strategies to handle various Android BT quirks.
     */
    public void connect(BluetoothDevice device) {
        if (device == null) return;
        stopScanInternal();
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

    /**
     * Connect to the last known device.
     */
    public void connectToLastDevice() {
        if (lastDevice != null) {
            connect(lastDevice);
        } else {
            startScan();
        }
    }

    /**
     * Disconnect from the radio.
     */
    public void disconnect() {
        shouldReconnect.set(false);
        connecting.set(false);
        connected.set(false);
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
        connecting.set(false);
        connected.set(false);
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
            Log.d(TAG, "chunk " + (i + 1) + "/" + total + " textLen=" + payload.length());
            enqueueCommand(buildSendChannelMessageCommand(getMeshChannelIndex(), payload));
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
        enqueueCommand(buildSendChannelMessageCommand(getMeshChannelIndex(), payload));
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
        notifyDisconnected("Connection lost");

        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (lastDevice == null) return;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS + "). Giving up.");
            notifyError("Reconnect failed after " + MAX_RECONNECT_ATTEMPTS + " attempts. Tap Scan to retry.");
            return;
        }

        reconnectAttempts++;
        int delaySec = 5 * reconnectAttempts; // Back off: 5s, 10s, 15s...
        Log.i(TAG, "Scheduling reconnect #" + reconnectAttempts + " in " + delaySec + " seconds...");
        ioHandler.postDelayed(() -> {
            if (shouldReconnect.get() && !connected.get() && !connecting.get()) {
                Log.i(TAG, "Attempting reconnect #" + reconnectAttempts + "...");
                connect(lastDevice);
            }
        }, delaySec * 1000L);
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

    private void markIoActivity() {
        lastIoActivityMs.set(System.currentTimeMillis());
    }

    private void stopScanInternal() {
        if (bleScanner != null && scanCallback != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
        }
        scanCallback = null;
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
        byte[] app = "atak-uvpro".getBytes(StandardCharsets.UTF_8);
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

    private byte[] buildGetChannelInfoCommand(int idx) {
        return new byte[]{CMD_GET_CHANNEL, (byte) (idx & 0xFF)};
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

    private int getMeshChannelIndex() {
        int discovered = activeMeshChannel.get();
        if (discovered >= 0 && discovered <= 7) {
            return discovered;
        }
        try {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            String val = prefs.getString("meshatak_mesh_channel", "0");
            int i = Integer.parseInt(val);
            if (i < 0) return 0;
            if (i > 7) return 7;
            return i;
        } catch (Exception ignored) {
            return 0;
        }
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
        if (t == RESP_NO_MORE_MSGS) {
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
        if (t == RESP_DEVICE_INFO) {
            logDeviceInfo(pkt);
            return;
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
            String routed = extractRoutableEnvelope(message);
            if (routed != null) {
                Log.d(TAG, "RX mesh env len=" + routed.length());
                handleMeshMessage(routed);
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
        Log.d(TAG, "Channel slot " + idx + " name='" + name + "' secretFp=" + secretFp);
        if (!name.isEmpty()) {
            int old = activeMeshChannel.getAndSet(idx);
            if (old != idx) {
                Log.i(TAG, "Auto-selected MeshCore channel " + idx + " (" + name + ")");
            }
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
            ByteBuffer bb = ByteBuffer.wrap(pkt).order(ByteOrder.LITTLE_ENDIAN);
            int latE6 = bb.getInt(36);
            int lonE6 = bb.getInt(40);
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
        } catch (Exception e) {
            Log.w(TAG, "Failed parsing self-info: " + e.getMessage());
        }
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
            packetRouter.routeIncoming(ax25);
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
            for (int i = 0; i < 8; i++) {
                enqueueCommand(buildGetChannelInfoCommand(i));
            }
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
