package com.atakmaps.meshcore.plugin.location;

/**
 * GPS fix from a connected MeshCore node.
 */
public final class MeshLocationFix {

    public final double latitude;
    public final double longitude;
    public final long receivedAtMs;
    public final String nodeName;

    public MeshLocationFix(double latitude, double longitude, long receivedAtMs, String nodeName) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.receivedAtMs = receivedAtMs;
        this.nodeName = nodeName;
    }

    public static MeshLocationFix fromBtFix(
            com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager.MeshLocationFix fix) {
        if (fix == null) {
            return null;
        }
        return new MeshLocationFix(fix.latitude, fix.longitude, fix.receivedAtMs, fix.nodeName);
    }

    public boolean isValid() {
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return false;
        }
        if (latitude < -90.0 || latitude > 90.0 || longitude < -180.0 || longitude > 180.0) {
            return false;
        }
        return !(Math.abs(latitude) < 0.000001 && Math.abs(longitude) < 0.000001);
    }
}
