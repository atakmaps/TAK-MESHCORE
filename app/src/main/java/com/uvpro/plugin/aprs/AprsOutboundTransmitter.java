package com.uvpro.plugin.aprs;

import android.content.Context;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.PointMapItem;
import com.uvpro.plugin.ax25.Ax25Frame;
import com.uvpro.plugin.bluetooth.BtConnectionManager;
import com.uvpro.plugin.protocol.RfTxArbitrator;
import com.uvpro.plugin.ui.SettingsFragment;

/**
 * Transmits plugin-generated APRS position frames over KISS (dest APRS).
 */
public final class AprsOutboundTransmitter {

    private static final String TAG = "UVPro.APRS.TX";

    private AprsOutboundTransmitter() {
    }

    /**
     * @return true if a frame was queued on KISS
     */
    public static boolean sendPositionBeacon(Context context, BtConnectionManager bt) {
        return sendPositionBeacon(context, bt, true);
    }

    /**
     * @param enforceGuardWindow when false, skip passive OPENRL guard timing
     *                           (manual button), but still respect active OPENRL TX/ping reply.
     */
    public static boolean sendPositionBeacon(Context context, BtConnectionManager bt,
                                             boolean enforceGuardWindow) {
        if (context == null || bt == null || !bt.isConnected()) {
            return false;
        }
        if (bt.isRadioSilenceEnabled()) {
            Log.d(TAG, "APRS beacon blocked (radio silence)");
            return false;
        }
        boolean defer = enforceGuardWindow
                ? RfTxArbitrator.get().shouldDeferAprsBeacon()
                : RfTxArbitrator.get().shouldDeferManualAprsBeacon();
        if (defer) {
            Log.d(TAG, enforceGuardWindow
                    ? "APRS beacon deferred (OPENRL priority)"
                    : "APRS beacon deferred (OPENRL TX active)");
            return false;
        }
        String baseCall = SettingsFragment.getAprsCallsign(context);
        if (!SettingsFragment.isValidAprsCallsign(baseCall)) {
            Log.w(TAG, "APRS beacon blocked (invalid callsign)");
            return false;
        }
        if (!SettingsFragment.isAprsIconSelected(context)) {
            Log.w(TAG, "APRS icon not selected; using default symbol / >");
        }
        int ssid = SettingsFragment.getAprsSsid(context);
        char symTab = SettingsFragment.getAprsSymbolTable(context);
        char symCode = SettingsFragment.getAprsSymbolCode(context);
        String message = SettingsFragment.getAprsMessage(context);

        MapView mv = MapView.getMapView();
        if (mv == null) {
            return false;
        }
        PointMapItem self = mv.getSelfMarker();
        if (self == null) {
            return false;
        }
        com.atakmap.coremap.maps.coords.GeoPoint gp = self.getPoint();
        String payload = AprsBeaconBuilder.buildUncompressedPositionBeacon(
                gp.getLatitude(), gp.getLongitude(), symTab, symCode, message);
        if (payload == null) {
            Log.w(TAG, "APRS beacon build failed");
            return false;
        }
        try {
            Ax25Frame frame = Ax25Frame.createAprsFrame(baseCall, ssid, payload);
            boolean ok = bt.sendKissFrame(frame.encode());
            if (ok) {
                Log.i(TAG, "APRS beacon TX " + baseCall + "-" + ssid + " len=" + payload.length());
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "APRS beacon TX failed", e);
            return false;
        }
    }
}
