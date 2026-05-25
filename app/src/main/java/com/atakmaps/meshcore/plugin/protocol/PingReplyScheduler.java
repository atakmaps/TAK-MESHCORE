package com.atakmaps.meshcore.plugin.protocol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.protocol.RfTxArbitrator;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

/**
 * Schedules ping replies using deterministic callsign slots ({@link NetSlotConfig}).
 */
public final class PingReplyScheduler {

    private static final String TAG = "MeshCore.PingReply";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final CotBridge cotBridge;
    private Runnable pendingReply;

    public PingReplyScheduler(CotBridge cotBridge) {
        this.cotBridge = cotBridge;
    }

    /**
     * Queue a position reply after the configured slot delay for this device.
     */
    public void scheduleReply(Context context) {
        if (context == null || cotBridge == null) {
            return;
        }
        if (!SettingsFragment.isPingReplyEnabled(context)) {
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        String callsign = SettingsFragment.getCallsign(context);
        long delayMs = NetSlotConfig.computeReplyDelayMs(context, callsign);
        int slot = NetSlotConfig.computeSlotIndex(callsign, NetSlotConfig.getSlotCount(context));
        cancelPending();
        RfTxArbitrator.get().setPingReplyPending(true);
        final Context appCtx = context.getApplicationContext();
        pendingReply = () -> transmitReply(appCtx);
        handler.postDelayed(pendingReply, delayMs);
        Log.d(TAG, "Ping reply scheduled in " + delayMs + "ms (slot " + slot + ")");
    }

    public void cancelPending() {
        if (pendingReply != null) {
            handler.removeCallbacks(pendingReply);
            pendingReply = null;
        }
        RfTxArbitrator.get().setPingReplyPending(false);
    }

    private void transmitReply(Context context) {
        pendingReply = null;
        RfTxArbitrator.get().setPingReplyPending(false);
        if (!SettingsFragment.isPingReplyEnabled(context)) {
            return;
        }
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            PointMapItem self = mv.getSelfMarker();
            if (self == null) {
                return;
            }
            com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
            double speedMs = 0.0;
            double course = 0.0;
            try {
                speedMs = Double.parseDouble(self.getMetaString("Speed", "0"));
            } catch (Exception ignored) {
            }
            try {
                course = Double.parseDouble(self.getMetaString("course", "0"));
            } catch (Exception ignored) {
            }
            cotBridge.sendPositionOverRadio(
                    gp.getLatitude(), gp.getLongitude(),
                    gp.getAltitude(), (float) speedMs, (float) course, -1);
            Log.d(TAG, "Ping reply sent (slotted)");
        } catch (Exception e) {
            Log.w(TAG, "Ping reply transmit failed: " + e.getMessage());
        }
    }
}
