package com.atakmaps.meshcore.plugin.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.ParcelUuid;

import androidx.annotation.Nullable;

import com.atakmaps.meshcore.plugin.bluetooth.BluetoothDeviceRegistry.BtDeviceRecord;

import java.util.Locale;
import java.util.UUID;

/**
 * Identifies MeshCore companion radios during BLE discovery.
 *
 * <p>MeshCore firmware ({@code MeshCore-upstream}) advertises the Nordic UART service
 * ({@code 6E400001-B5A3-F393-E0A9-E50E24DCCA9E}) and names devices
 * {@code MeshCore-<nodeName>} ({@code BLE_NAME_PREFIX} in companion_radio/MyMesh.h).
 * The official companion protocol treats the service UUID as the primary filter; name
 * matching is optional.</p>
 */
public final class MeshBleDeviceMatcher {

    /** Nordic UART service used by MeshCore companion firmware. */
    public static final UUID UUID_UART_SERVICE =
            UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");

    /** Meshtastic-compatible UART service (some dual-stack / bridged nodes). */
    public static final UUID UUID_MESHTASTIC_SERVICE =
            UUID.fromString("6BA1B218-15A8-461F-9FA8-5DCAE273EAFD");

    /** Default BLE PIN when firmware is built with {@code BLE_PIN_CODE} (see MyMesh.cpp). */
    public static final int DEFAULT_PAIRING_PIN = 123456;

    /** Firmware device-name prefix ({@code BLE_NAME_PREFIX}). */
    public static final String NAME_PREFIX = "MeshCore-";

    private MeshBleDeviceMatcher() {
    }

    /**
     * Returns true when the scan record advertises a MeshCore / Meshtastic UART service.
     * This is the definitive signal used by the official MeshCore companion apps.
     */
    public static boolean advertisesMeshService(@Nullable ScanResult result) {
        if (result == null) {
            return false;
        }
        ScanRecord record = result.getScanRecord();
        if (record == null || record.getServiceUuids() == null) {
            return false;
        }
        for (ParcelUuid parcelUuid : record.getServiceUuids()) {
            if (parcelUuid == null || parcelUuid.getUuid() == null) {
                continue;
            }
            UUID uuid = parcelUuid.getUuid();
            if (UUID_UART_SERVICE.equals(uuid) || UUID_MESHTASTIC_SERVICE.equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Heuristic name match for nodes that advertise a name but omit the service UUID
     * in the parsed scan record (common on some chipsets / Android versions).
     */
    public static boolean matchesMeshName(@Nullable String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.regionMatches(true, 0, NAME_PREFIX, 0, NAME_PREFIX.length())) {
            return true;
        }
        String n = trimmed.toLowerCase(Locale.US);
        return n.contains("meshcore")
                || n.contains("meshtastic")
                || n.contains("wismesh")
                || n.contains("heltec")
                || n.contains("lilygo")
                || n.contains("t-echo")
                || n.contains("tdeck")
                || n.contains("t-deck")
                || n.contains("t-pager")
                || n.contains("t-display")
                || n.contains("tbeam")
                || n.contains("t-beam")
                || n.contains("t1000")
                || n.contains("t114")
                || n.contains("rak")
                || n.contains("seeed")
                || n.contains("sensecap")
                || n.contains("thinknode")
                || n.contains("station g2")
                || n.contains("nano g2")
                || n.contains("wio");
    }

    @Nullable
    public static String resolveName(@Nullable ScanResult result, @Nullable BluetoothDevice device) {
        ScanRecord record = result != null ? result.getScanRecord() : null;
        String name = record != null ? record.getDeviceName() : null;
        if (name == null || name.trim().isEmpty()) {
            try {
                name = device != null ? device.getName() : null;
            } catch (Exception ignored) {
                name = null;
            }
        }
        return name;
    }

    /**
     * Returns true if {@code result} looks like a MeshCore companion radio.
     *
     * @param trustHardwareFilter when true, accept any scan result (used for UUID-filtered
     *                            hardware scans where Android already matched the service UUID
     *                            but may not populate {@link ScanRecord#getServiceUuids()}).
     */
    public static boolean isMeshDevice(@Nullable Context context,
                                       @Nullable ScanResult result,
                                       @Nullable BluetoothDevice device,
                                       boolean trustHardwareFilter) {
        if (device == null) {
            return false;
        }
        if (context != null && isKnownMeshAddress(context, device.getAddress())) {
            return true;
        }
        if (trustHardwareFilter || advertisesMeshService(result)) {
            return true;
        }
        return matchesMeshName(resolveName(result, device));
    }

    public static boolean isMeshDevice(@Nullable Context context,
                                       @Nullable ScanResult result,
                                       @Nullable BluetoothDevice device) {
        return isMeshDevice(context, result, device, false);
    }

    public static boolean isMeshDevice(@Nullable Context context,
                                       @Nullable BluetoothDevice device) {
        return isMeshDevice(context, null, device, false);
    }

    public static boolean isKnownMeshAddress(@Nullable Context context, @Nullable String address) {
        if (context == null || address == null || address.trim().isEmpty()) {
            return false;
        }
        return BluetoothDeviceRegistry.find(context, address) != null;
    }

    /** Registry entries are MeshCore-only; any saved record is a known mesh radio. */
    public static boolean isKnownMeshRecord(@Nullable BtDeviceRecord record) {
        return record != null;
    }

    public static String pairingHintMessage(@Nullable BluetoothDevice device) {
        String label = "MeshCore";
        if (device != null) {
            try {
                String name = device.getName();
                if (name != null && !name.trim().isEmpty()) {
                    label = name.trim();
                } else if (device.getAddress() != null) {
                    label = device.getAddress();
                }
            } catch (Exception ignored) {
            }
        }
        return "Pairing " + label + ". Accept the Bluetooth prompt; default PIN is "
                + DEFAULT_PAIRING_PIN + ".";
    }
}
