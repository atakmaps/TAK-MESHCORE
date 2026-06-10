package com.atakmaps.meshcore.plugin.protocol;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;

/**
 * Transmits directed position-request pings (TYPE_PING with target callsign) over MeshCore RF.
 */
public final class PositionRequester {

    private static final String TAG = "MeshCore.PositionReq";

    private static volatile BtConnectionManager transport;
    private static volatile EncryptionManager encryptionManager;

    private PositionRequester() {
    }

    public static void install(BtConnectionManager bt, EncryptionManager encryption) {
        transport = bt;
        encryptionManager = encryption;
    }

    public static void clear() {
        transport = null;
        encryptionManager = null;
    }

    public static boolean requestPosition(Context context, String targetCallsign) {
        return requestPosition(context, null, targetCallsign);
    }

    public static boolean requestPosition(Context context, String contactUid,
                                          String targetCallsign) {
        if (targetCallsign == null || targetCallsign.trim().isEmpty()) {
            return false;
        }
        BtConnectionManager tx = transport;
        if (tx == null || !tx.isConnected()) {
            Log.w(TAG, "Request position: not connected");
            return false;
        }
        Context ctx = resolveContext(context);
        if (ctx == null) {
            return false;
        }
        String sender = SettingsFragment.getCallsign(ctx);
        String atakTarget = targetCallsign.trim();
        String targetRadio = CallsignUtil.toRadioCallsign(atakTarget);
        if (targetRadio.isEmpty()) {
            return false;
        }
        Log.i(TAG, "Directed ping uid=" + contactUid
                + " atak=" + atakTarget + " wire=" + targetRadio);
        try {
            MeshCorePacket packet = MeshCorePacket.createDirectedPingPacket(sender, atakTarget);
            byte[] payload = packet.getPayload();
            if (payload == null || payload.length != 12) {
                Log.e(TAG, "Directed ping encode failed: payloadLen="
                        + (payload == null ? 0 : payload.length));
                return false;
            }
            byte[] packetBytes = packet.encode();
            EncryptionManager em = encryptionManager;
            if (em != null && em.isEnabled()) {
                packetBytes = em.encrypt(packetBytes);
                if (packetBytes == null) {
                    Log.e(TAG, "Encrypt failed for directed ping");
                    return false;
                }
            }
            Ax25Frame frame = Ax25Frame.createMeshCoreFrame(sender, 0, packetBytes);
            boolean ok = tx.sendKissFrame(frame.encode());
            if (ok) {
                PingReplyNotifier.noteDirectedPingSent(ctx, atakTarget, "MeshCore");
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "requestPosition failed", e);
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
