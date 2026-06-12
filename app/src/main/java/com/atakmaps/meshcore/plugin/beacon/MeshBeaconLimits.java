package com.atakmaps.meshcore.plugin.beacon;

import android.content.Context;

import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

/**
 * Runtime mesh beacon rate floors when mesh limiting is not disabled by admin.
 * Stored user prefs are unchanged; caps apply only at TX decision time.
 */
public final class MeshBeaconLimits {

    public static final int MIN_INTERVAL_SEC = 1800;
    public static final int MIN_SLOW_RATE_SEC = 1800;
    public static final int MIN_FAST_RATE_SEC = 300;
    public static final int MIN_TURN_TIME_SEC = 300;

    private MeshBeaconLimits() {}

    /** Limits apply when admin has not checked Disable Mesh Beacon Limiting. */
    public static boolean isActive(Context context) {
        if (context == null) {
            return false;
        }
        return !SettingsFragment.isDisableMeshBeaconLimiting(context);
    }

    public static int capIntervalSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_INTERVAL_SEC);
    }

    public static int capSlowRateSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_SLOW_RATE_SEC);
    }

    public static int capFastRateSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_FAST_RATE_SEC);
    }

    public static int capMinTurnTimeSec(Context context, int userSec) {
        if (!isActive(context)) {
            return userSec;
        }
        return Math.max(userSec, MIN_TURN_TIME_SEC);
    }
}
