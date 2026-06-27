package com.atakmaps.meshcore.plugin.location;

import android.graphics.Color;
import android.os.SystemClock;

import com.atakmap.android.location.framework.Location;
import com.atakmap.android.location.framework.LocationDerivation;
import com.atakmap.android.location.framework.LocationProvider;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.coords.GeoPointMetaData;

import gov.tak.api.cot.CoordinatedTime;

/**
 * Registers MeshCore node GPS as a first-class ATAK location provider (5.6+ GPS
 * source toggles ignore legacy map-bundle mock injection when Android GPS is off).
 */
public final class PluginMeshLocationProvider extends LocationProvider {

    public static final String PROVIDER_UID = "meshcore-plugin-gps";

    /** Slightly longer than {@link MeshGpsBridge#AUGMENT_INTERVAL_MS}. */
    private static final int GPS_VALIDITY_MS = 130_000;

    private volatile boolean enabled = true;
    private volatile String sourceLabel = MeshGpsBridge.MOCK_SOURCE_LABEL;

    @Override
    public String getUniqueIdentifier() {
        return PROVIDER_UID;
    }

    @Override
    public String getTitle() {
        return "MeshCore Radio GPS";
    }

    @Override
    public String getDescription() {
        return "GPS fixes from a MeshCore node over Bluetooth";
    }

    @Override
    public String getSource() {
        return sourceLabel;
    }

    @Override
    public String getSourceCategory() {
        return null;
    }

    @Override
    public int getSourceColor() {
        return Color.parseColor("#FF00BCD4");
    }

    @Override
    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean getEnabled() {
        return enabled;
    }

    @Override
    public void dispose() {
        enabled = false;
    }

    public void publishFix(MeshLocationFix fix, String sourceLabel) {
        if (fix == null || !fix.isValid()) {
            return;
        }
        if (sourceLabel != null && !sourceLabel.trim().isEmpty()) {
            this.sourceLabel = sourceLabel.trim();
        }
        GeoPoint gp = new GeoPoint(fix.latitude, fix.longitude);
        fireLocationChanged(new MeshFixLocation(gp));
    }

    private final class MeshFixLocation implements Location {
        private final GeoPoint point;

        MeshFixLocation(GeoPoint point) {
            this.point = point;
        }

        @Override
        public long getLocationDerivedTime() {
            return CoordinatedTime.currentTimeMillis();
        }

        @Override
        public GeoPoint getPoint() {
            return point;
        }

        @Override
        public double getBearing() {
            return Double.NaN;
        }

        @Override
        public double getSpeed() {
            return Double.NaN;
        }

        @Override
        public double getBearingAccuracy() {
            return Double.NaN;
        }

        @Override
        public double getSpeedAccuracy() {
            return Double.NaN;
        }

        @Override
        public int getReliabilityScore() {
            return 60;
        }

        @Override
        public String getReliabilityReason() {
            return "MeshCore GPS";
        }

        @Override
        public LocationDerivation getDerivation() {
            return new LocationDerivation() {
                @Override
                public String getHorizontalSource() {
                    return sourceLabel;
                }

                @Override
                public String getVerticalSource() {
                    return GeoPointMetaData.GPS;
                }
            };
        }

        @Override
        public boolean isValid() {
            return point != null && point.isValid()
                    && SystemClock.elapsedRealtime() - getLastUpdated() < GPS_VALIDITY_MS;
        }
    }
}
