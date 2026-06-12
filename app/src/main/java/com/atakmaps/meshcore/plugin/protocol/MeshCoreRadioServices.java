package com.atakmaps.meshcore.plugin.protocol;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

/**
 * Access to live radio TX for settings and administration actions.
 */
public final class MeshCoreRadioServices {

    private static final String TAG = "MeshCore.RadioSvc";

    private static volatile BtConnectionManager btManager;
    private static volatile EncryptionManager encryptionManager;

    private MeshCoreRadioServices() {
    }

    public static void install(BtConnectionManager bt, EncryptionManager encryption) {
        btManager = bt;
        encryptionManager = encryption;
    }

    /** Apply encryption toggle + shared secret from ATAK SharedPreferences. */
    public static void syncEncryptionFromSettings(Context context) {
        EncryptionManager em = encryptionManager;
        if (em == null) {
            return;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return;
        }
        if (SettingsFragment.isEncryptionEnabled(ctx)) {
            em.setSharedSecret(SettingsFragment.getEncryptionPassphrase(ctx));
        } else {
            em.setSharedSecret(null);
        }
    }

    public static void clear() {
        btManager = null;
        encryptionManager = null;
    }

    public static boolean isConnected() {
        BtConnectionManager bt = btManager;
        return bt != null && bt.isConnected();
    }

    /**
     * Broadcast current slot settings to the net (TYPE_NET_SLOT_CONFIG).
     */
    public static boolean distributeNetSlotConfig(Context context) {
        BtConnectionManager bt = btManager;
        if (bt == null || !bt.isConnected()) {
            Log.w(TAG, "Distribute net slots: not connected");
            return false;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return false;
        }
        int slotCount = NetSlotConfig.getSlotCount(ctx);
        float slotTimeSec = NetSlotConfig.getSlotTimeSec(ctx);
        String issuer = SettingsFragment.getCallsign(ctx);
        int sequence = (int) (System.currentTimeMillis() / 1000L);
        byte[] payload = NetSlotConfig.encodePayload(slotCount, slotTimeSec, sequence, issuer);
        MeshCorePacket packet = new MeshCorePacket(MeshCorePacket.TYPE_NET_SLOT_CONFIG, payload);
        return transmitPacket(packet);
    }

    private static boolean transmitPacket(MeshCorePacket packet) {
        try {
            BtConnectionManager bt = btManager;
            if (bt == null) {
                return false;
            }
            byte[] packetBytes = packet.encode();
            EncryptionManager em = encryptionManager;
            if (em != null && em.isEnabled()) {
                packetBytes = em.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encrypt failed for net slot config");
                    return false;
                }
            }
            String callsign = SettingsFragment.getCallsign(
                    MapView.getMapView() != null
                            ? MapView.getMapView().getContext()
                            : null);
            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(callsign, 0, packetBytes);
            boolean ok = bt.sendKissFrame(frame.encode());
            if (ok) {
                Log.i(TAG, "Net slot config transmitted");
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "transmitPacket failed", e);
            return false;
        }
    }

    private static Context resolveContext(Context context) {
        if (context != null) {
            return context;
        }
        MapView mv = MapView.getMapView();
        return mv != null ? mv.getContext() : null;
    }
}
