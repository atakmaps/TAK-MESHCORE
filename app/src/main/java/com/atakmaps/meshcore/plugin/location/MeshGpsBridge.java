package com.atakmaps.meshcore.plugin.location;

import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.location.framework.Location;
import com.atakmap.android.location.framework.LocationManager;
import com.atakmap.android.location.framework.LocationProvider;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.MetaDataHolder2;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

/**
 * Applies MeshCore node GPS into ATAK as a registered location provider (5.6+) and legacy mock metadata.
 */
public final class MeshGpsBridge {

    private static final String TAG = "MeshCore.GpsBridge";

    public static final long AUGMENT_INTERVAL_MS = 120_000L;
    public static final String MOCK_SOURCE_LABEL = "MeshCore GPS";
    private static final String INTERNAL_GPS_PROVIDER_UID = "internal-gps-chip";

    private static volatile PluginMeshLocationProvider meshLocationProvider;
    private static volatile boolean meshLocationProviderRegistered;

    private MeshGpsBridge() {
    }

    public static synchronized void installLocationProvider() {
        if (meshLocationProviderRegistered) {
            return;
        }
        meshLocationProvider = new PluginMeshLocationProvider();
        LocationManager.getInstance().registerProvider(
                meshLocationProvider, LocationManager.HIGHEST_PRIORITY);
        meshLocationProviderRegistered = true;
        Log.i(TAG, "Registered MeshCore GPS location provider");
    }

    public static synchronized void uninstallLocationProvider() {
        if (!meshLocationProviderRegistered) {
            return;
        }
        LocationManager.getInstance().unregisterProvider(PluginMeshLocationProvider.PROVIDER_UID);
        if (meshLocationProvider != null) {
            meshLocationProvider.dispose();
            meshLocationProvider = null;
        }
        meshLocationProviderRegistered = false;
        Log.i(TAG, "Unregistered MeshCore GPS location provider");
    }

    public static boolean isPhoneGpsAvailable(MapView mapView) {
        try {
            LocationProvider internal = LocationManager.getInstance()
                    .getLocationProvider(INTERNAL_GPS_PROVIDER_UID);
            if (internal == null || !internal.getEnabled()) {
                return false;
            }
            Location loc = internal.getLastReportedLocation();
            if (loc == null || !loc.isValid()) {
                return false;
            }
            GeoPoint point = loc.getPoint();
            return point != null && point.isValid();
        } catch (Exception e) {
            Log.w(TAG, "Could not evaluate phone GPS state", e);
            return false;
        }
    }

    public static boolean injectIntoAtak(MapView mapView,
            com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager.MeshLocationFix btFix) {
        return injectIntoAtak(mapView, MeshLocationFix.fromBtFix(btFix));
    }

    public static boolean injectIntoAtak(MapView mapView, MeshLocationFix fix) {
        if (mapView == null || fix == null || !fix.isValid()) {
            return false;
        }
        MetaDataHolder2 data = mapView.getMapData();
        if (data == null) {
            return false;
        }
        GeoPoint gp = new GeoPoint(fix.latitude, fix.longitude);
        data.setMetaString("locationSourcePrefix", "mock");
        data.setMetaBoolean("mockLocationAvailable", true);
        data.setMetaString("mockLocationSource", MOCK_SOURCE_LABEL);
        data.setMetaString("mockLocationSourceColor", "#FF00BCD4");
        data.setMetaBoolean("mockLocationCallsignValid", true);
        data.setMetaString("mockLocation", gp.toString());
        data.setMetaLong("mockLocationTime", SystemClock.elapsedRealtime());
        data.setMetaLong("mockGPSTime", new CoordinatedTime().getMilliseconds());
        Intent gpsReceived = new Intent("com.atakmap.android.map.WR_GPS_RECEIVED");
        AtakBroadcast.getInstance().sendBroadcast(gpsReceived);
        PluginMeshLocationProvider provider = meshLocationProvider;
        if (provider != null) {
            provider.publishFix(fix, MOCK_SOURCE_LABEL);
        }
        Log.i(TAG, "Injected GPS into ATAK (" + MOCK_SOURCE_LABEL + "): " + gp);
        return true;
    }
}
