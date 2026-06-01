package com.atakmaps.meshcore.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serial BLE availability probes.
 * <ul>
 *   <li>Full probe: handshake through RESP_SELF_INFO (bonded / busy detection).</li>
 *   <li>Light probe: connect + UART service discovery only (unbonded picker checks).</li>
 * </ul>
 */
final class BleMeshAvailabilityProber {

    private static final String TAG = "MeshCore.BLEProbe";
    private static final long PROBE_ATTEMPT_TIMEOUT_MS = 8000L;
    private static final long INTER_PROBE_DELAY_MS = 500L;
    private static final long RETRY_DELAY_MS = 600L;
    private static final int MAX_ATTEMPTS = 2;

    private static final byte CMD_APP_START = 0x01;
    /** Companion response to CMD_APP_START ({@code RESP_SELF_INFO}). */
    private static final byte RESP_SELF_INFO = 0x05;
    private static final String COMPANION_APP_ID = "meshcore-flutter";

    static final int AVAILABLE = 1;
    static final int BUSY = 2;

    interface Callback {
        void onResult(int availability);
    }

    private final Map<String, ProbeSession> activeByAddress = new ConcurrentHashMap<>();
    private final ArrayDeque<ProbeRequest> queue = new ArrayDeque<>();
    private volatile ProbeSession runningSession;
    private volatile Handler ioHandler;
    private volatile Context appContext;

    void probe(Context context, Handler ioHandler, BluetoothDevice device, Callback callback) {
        enqueueProbe(context, ioHandler, device, callback, false);
    }

    /** Connect + UART service discovery only; avoids pairing prompts on unbonded nodes. */
    void probeLight(Context context, Handler ioHandler, BluetoothDevice device, Callback callback) {
        enqueueProbe(context, ioHandler, device, callback, true);
    }

    private void enqueueProbe(Context context, Handler ioHandler, BluetoothDevice device,
                              Callback callback, boolean lightOnly) {
        if (device == null || callback == null || ioHandler == null) {
            if (callback != null) {
                callback.onResult(BUSY);
            }
            return;
        }
        String address = normalize(device.getAddress());
        if (address == null) {
            callback.onResult(BUSY);
            return;
        }
        cancel(address);
        this.ioHandler = ioHandler;
        this.appContext = context.getApplicationContext();
        synchronized (queue) {
            queue.offer(new ProbeRequest(device, callback, lightOnly));
        }
        scheduleNext(ioHandler);
    }

    void cancelAll() {
        synchronized (queue) {
            queue.clear();
        }
        ProbeSession running = runningSession;
        if (running != null) {
            running.finish(BUSY, true, "cancelAll");
        }
        for (String address : activeByAddress.keySet()) {
            cancel(address);
        }
    }

    void cancel(String address) {
        String key = normalize(address);
        if (key == null) {
            return;
        }
        synchronized (queue) {
            queue.removeIf(req -> key.equals(normalize(req.device.getAddress())));
        }
        ProbeSession session = activeByAddress.remove(key);
        if (session != null) {
            session.finish(BUSY, true, "cancel");
        }
        ProbeSession running = runningSession;
        if (running != null && key.equals(running.address)) {
            running.finish(BUSY, true, "cancel-running");
        }
    }

    private void scheduleNext(Handler handler) {
        if (handler == null) {
            return;
        }
        handler.post(() -> {
            if (runningSession != null && !runningSession.isDone()) {
                return;
            }
            ProbeRequest next;
            synchronized (queue) {
                next = queue.poll();
            }
            if (next == null) {
                return;
            }
            startAttempt(next, 1);
        });
    }

    private void startAttempt(ProbeRequest request, int attempt) {
        Handler handler = ioHandler;
        Context context = appContext;
        if (handler == null || context == null) {
            request.callback.onResult(BUSY);
            scheduleNext(handler);
            return;
        }
        String address = normalize(request.device.getAddress());
        ProbeSession session = new ProbeSession(request.device, address, request.callback,
                attempt, request.lightOnly);
        activeByAddress.put(address, session);
        runningSession = session;
        Log.d(TAG, "Probe start " + address + " attempt=" + attempt + "/" + MAX_ATTEMPTS
                + (request.lightOnly ? " light" : " full"));
        startProbeGatt(context, handler, request.device, session);
        handler.postDelayed(() -> {
            if (!session.isDone()) {
                Log.d(TAG, "Probe timeout " + address + " attempt=" + attempt);
                session.finish(BUSY, false, "timeout");
            }
        }, PROBE_ATTEMPT_TIMEOUT_MS);
    }

    private void startProbeGatt(Context context, Handler handler,
                                BluetoothDevice device, ProbeSession session) {
        try {
            BluetoothGattCallback callback = new BluetoothGattCallback() {
                @Override
                public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                    if (session.isDone()) {
                        return;
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            session.finish(BUSY, false, "connect-status-" + status);
                            return;
                        }
                        session.gatt = g;
                        if (!g.discoverServices()) {
                            session.finish(BUSY, false, "discoverServices-failed");
                        }
                        return;
                    }
                    if (newState == BluetoothProfile.STATE_DISCONNECTED && !session.handshakeComplete) {
                        session.finish(BUSY, false, "disconnect-status-" + status);
                    }
                }

                @Override
                public void onServicesDiscovered(BluetoothGatt g, int status) {
                    if (session.isDone()) {
                        return;
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        session.finish(BUSY, false, "services-status-" + status);
                        return;
                    }
                    BluetoothGattService svc = g.getService(MeshBleDeviceMatcher.UUID_UART_SERVICE);
                    if (svc == null) {
                        session.finish(BUSY, false, "uart-missing");
                        return;
                    }
                    session.rxCharacteristic = svc.getCharacteristic(MeshBleDeviceMatcher.UUID_UART_RX);
                    session.txCharacteristic = svc.getCharacteristic(MeshBleDeviceMatcher.UUID_UART_TX);
                    if (session.rxCharacteristic == null || session.txCharacteristic == null) {
                        session.finish(BUSY, false, "rxtx-missing");
                        return;
                    }
                    if (session.lightOnly) {
                        session.finish(AVAILABLE, false, "uart-found-light");
                        return;
                    }
                    if (!g.setCharacteristicNotification(session.txCharacteristic, true)) {
                        session.finish(BUSY, false, "notify-enable-failed");
                        return;
                    }
                    BluetoothGattDescriptor ccc = session.txCharacteristic.getDescriptor(
                            MeshBleDeviceMatcher.UUID_CCC);
                    if (ccc == null) {
                        session.finish(BUSY, false, "ccc-missing");
                        return;
                    }
                    ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!g.writeDescriptor(ccc)) {
                        session.finish(BUSY, false, "ccc-write-failed");
                    }
                }

                @Override
                public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor,
                                              int status) {
                    if (session.isDone()) {
                        return;
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        session.finish(BUSY, false, "ccc-status-" + status);
                        return;
                    }
                    byte[] appStart = buildAppStartCommand();
                    session.rxCharacteristic.setValue(appStart);
                    session.rxCharacteristic.setWriteType(
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    if (!g.writeCharacteristic(session.rxCharacteristic)) {
                        session.finish(BUSY, false, "appstart-write-failed");
                    }
                }

                @Override
                public void onCharacteristicWrite(BluetoothGatt g,
                                                  BluetoothGattCharacteristic characteristic,
                                                  int status) {
                    if (session.isDone()) {
                        return;
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        session.finish(BUSY, false, "write-status-" + status);
                    }
                }

                @Override
                public void onCharacteristicChanged(BluetoothGatt g,
                                                    BluetoothGattCharacteristic characteristic) {
                    if (session.isDone() || characteristic == null) {
                        return;
                    }
                    byte[] data = characteristic.getValue();
                    if (data != null && data.length > 0 && (data[0] & 0xFF) == (RESP_SELF_INFO & 0xFF)) {
                        session.handshakeComplete = true;
                        session.finish(AVAILABLE, false, "self-info");
                    }
                }
            };

            BluetoothGatt gatt;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(context, false, callback);
            }
            session.gatt = gatt;
            if (gatt == null) {
                session.finish(BUSY, false, "connectGatt-null");
            }
        } catch (Exception e) {
            Log.w(TAG, "Probe start failed for " + session.address, e);
            session.finish(BUSY, false, "exception");
        }
    }

    private static byte[] buildAppStartCommand() {
        byte[] app = COMPANION_APP_ID.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[8 + app.length];
        out[0] = CMD_APP_START;
        System.arraycopy(app, 0, out, 8, app.length);
        return out;
    }

    private static String normalize(String address) {
        if (address == null) {
            return null;
        }
        String trimmed = address.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.US);
    }

    private void onSessionFinished(ProbeSession session, int availability, boolean silent,
                                   String reason) {
        Handler handler = ioHandler;
        Log.d(TAG, "Probe done " + session.address + " avail=" + availability
                + " attempt=" + session.attempt + " reason=" + reason);
        if (silent) {
            scheduleNext(handler);
            return;
        }
        if (availability == AVAILABLE) {
            session.callback.onResult(AVAILABLE);
            if (handler != null) {
                handler.postDelayed(() -> scheduleNext(handler), INTER_PROBE_DELAY_MS);
            }
            return;
        }
        if (session.attempt < MAX_ATTEMPTS && handler != null) {
            handler.postDelayed(() -> startAttempt(
                    new ProbeRequest(session.device, session.callback, session.lightOnly),
                    session.attempt + 1), RETRY_DELAY_MS);
            return;
        }
        session.callback.onResult(BUSY);
        if (handler != null) {
            handler.postDelayed(() -> scheduleNext(handler), INTER_PROBE_DELAY_MS);
        }
    }

    private static final class ProbeRequest {
        final BluetoothDevice device;
        final Callback callback;
        final boolean lightOnly;

        ProbeRequest(BluetoothDevice device, Callback callback, boolean lightOnly) {
            this.device = device;
            this.callback = callback;
            this.lightOnly = lightOnly;
        }
    }

    private final class ProbeSession {
        final BluetoothDevice device;
        final String address;
        final Callback callback;
        final int attempt;
        final boolean lightOnly;
        volatile BluetoothGatt gatt;
        volatile BluetoothGattCharacteristic rxCharacteristic;
        volatile BluetoothGattCharacteristic txCharacteristic;
        volatile boolean handshakeComplete;
        volatile boolean done;

        ProbeSession(BluetoothDevice device, String address, Callback callback, int attempt,
                     boolean lightOnly) {
            this.device = device;
            this.address = address;
            this.callback = callback;
            this.attempt = attempt;
            this.lightOnly = lightOnly;
        }

        boolean isDone() {
            return done;
        }

        void finish(int availability, boolean silentClose, String reason) {
            if (done) {
                return;
            }
            done = true;
            activeByAddress.remove(address);
            if (runningSession == this) {
                runningSession = null;
            }
            BluetoothGatt g = gatt;
            gatt = null;
            if (g != null) {
                try {
                    g.disconnect();
                } catch (Exception ignored) {
                }
                try {
                    g.close();
                } catch (Exception ignored) {
                }
            }
            onSessionFinished(this, availability, silentClose, reason);
        }
    }
}
