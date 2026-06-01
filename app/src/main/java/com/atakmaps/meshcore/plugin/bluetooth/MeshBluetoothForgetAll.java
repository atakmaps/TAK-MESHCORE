package com.atakmaps.meshcore.plugin.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Clears plugin Bluetooth history and attempts to unpair MeshCore devices
 * from the phone's Bluetooth stack.
 */
public final class MeshBluetoothForgetAll {

    private static final String TAG = "MeshCore.ForgetAll";

    private MeshBluetoothForgetAll() {
    }

    public static final class Result {
        /** Saved registry entries removed. */
        public final int registryEntriesCleared;
        /** Distinct device addresses considered for unpair. */
        public final int bondsAttempted;
        /** Devices successfully unpaired via {@code removeBond}. */
        public final int bondsRemoved;
        /** Bonded devices we could not unpair programmatically. */
        public final int bondsFailed;

        Result(int registryEntriesCleared, int bondsAttempted,
               int bondsRemoved, int bondsFailed) {
            this.registryEntriesCleared = registryEntriesCleared;
            this.bondsAttempted = bondsAttempted;
            this.bondsRemoved = bondsRemoved;
            this.bondsFailed = bondsFailed;
        }

        public boolean needsAndroidSettingsReminder() {
            return bondsFailed > 0;
        }
    }

    /**
     * Clears all saved MeshCore Bluetooth records and tries to remove Android bonds
     * for every known MeshCore address plus any bonded mesh-like device.
     */
    @NonNull
    public static Result forgetAll(@NonNull Context context,
                                   BluetoothAdapter adapter) {
        return forgetAll(context, adapter, null);
    }

    @NonNull
    public static Result forgetAll(@NonNull Context context,
                                   BluetoothAdapter adapter,
                                   Iterable<String> extraAddresses) {
        Set<String> addresses = new HashSet<>();
        if (extraAddresses != null) {
            for (String address : extraAddresses) {
                if (address != null && !address.trim().isEmpty()) {
                    addresses.add(normalize(address));
                }
            }
        }
        List<BluetoothDeviceRegistry.BtDeviceRecord> saved =
                BluetoothDeviceRegistry.getAllSortedForDisplay(context);
        int registryCount = saved.size();
        for (BluetoothDeviceRegistry.BtDeviceRecord record : saved) {
            if (record != null && record.address != null) {
                addresses.add(normalize(record.address));
            }
        }

        if (adapter != null) {
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null) {
                    for (BluetoothDevice device : bonded) {
                        if (device == null) {
                            continue;
                        }
                        if (MeshBleDeviceMatcher.isMeshDevice(context, device)) {
                            String addr = device.getAddress();
                            if (addr != null) {
                                addresses.add(normalize(addr));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read bonded devices", e);
            }
        }

        BluetoothDeviceRegistry.clearAll(context);

        int attempted = 0;
        int removed = 0;
        int failed = 0;
        if (adapter != null) {
            for (String address : addresses) {
                if (address == null || address.isEmpty()) {
                    continue;
                }
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(address);
                    int bondState = BluetoothDevice.BOND_NONE;
                    try {
                        bondState = device.getBondState();
                    } catch (Exception ignored) {
                    }
                    if (bondState != BluetoothDevice.BOND_BONDED
                            && bondState != BluetoothDevice.BOND_BONDING) {
                        continue;
                    }
                    attempted++;
                    if (removeBond(device)) {
                        removed++;
                        Log.i(TAG, "Removed bond for " + address);
                    } else {
                        failed++;
                        Log.w(TAG, "Failed to remove bond for " + address);
                    }
                } catch (Exception e) {
                    attempted++;
                    failed++;
                    Log.w(TAG, "Error removing bond for " + address, e);
                }
            }
        }

        return new Result(registryCount, attempted, removed, failed);
    }

    private static boolean removeBond(BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        try {
            Method method = device.getClass().getMethod("removeBond");
            Object result = method.invoke(device);
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "removeBond reflection failed for " + device.getAddress(), e);
            return false;
        }
    }

    private static String normalize(String address) {
        return address.trim().toUpperCase(Locale.US);
    }
}
