package com.atakmaps.meshcore.plugin.protocol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

/**
 * Schedules OPENRL RF work with short guards. Ping replies use full reply slots;
 * operator group/contact-list CoT uses a brief stagger only.
 */
public final class RfSlottedCoTScheduler {

    private static final String TAG = "MeshCore.SlottedTx";
    private static final int GROUP_TX_MAX_GUARD_ATTEMPTS = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;

    /**
     * Operator-initiated group/contact-list CoT: brief hash stagger (≤1.5 s), then transmit
     * as soon as the OPENRL guard allows (not the 20-slot ping-reply delay).
     */
    public void scheduleGroupContactCot(Context context, Runnable task) {
        if (context == null || task == null) {
            return;
        }
        Context appCtx = context.getApplicationContext();
        cancel();
        long staggerMs = NetSlotConfig.computeGroupSyncStaggerMs(appCtx,
                SettingsFragment.getCallsign(appCtx));
        Log.d(TAG, "Group CoT stagger " + staggerMs + "ms then TX when channel clear");
        pending = () -> runGroupContactCotWithChannelGuard(appCtx, task, 0);
        handler.postDelayed(pending, staggerMs);
    }

    private void runGroupContactCotWithChannelGuard(Context context, Runnable task,
                                                    int guardAttempt) {
        if (RfTxArbitrator.get().shouldDeferRfChatAck()) {
            if (guardAttempt < GROUP_TX_MAX_GUARD_ATTEMPTS) {
                handler.postDelayed(
                        () -> runGroupContactCotWithChannelGuard(context, task, guardAttempt + 1),
                        400L);
                return;
            }
            Log.w(TAG, "Group CoT TX: channel busy too long — sending anyway");
        }
        pending = null;
        RfTxArbitrator.get().setSlottedTxPending(false);
        try {
            task.run();
        } catch (Exception e) {
            Log.e(TAG, "Group CoT task failed", e);
        }
    }

    public void cancel() {
        if (pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
        }
        RfTxArbitrator.get().setSlottedTxPending(false);
    }
}
