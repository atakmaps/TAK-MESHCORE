package com.atakmaps.meshcore.plugin.kiss;

import android.util.Log;

import com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Vendor KISS extension used by BTECH / APRSdroid-style TNC links to lock the radio
 * to a single RF frequency (digital-only / no multi-channel scan).
 *
 * <p>Lock: {@code FEND 0x06 0xEA &lt;4-byte Hz BE&gt; FEND}
 * <p>Unlock: {@code FEND 0x06 0xEB FEND}
 */
public final class KissRadioFrequencyControl {

    private static final String TAG = "MeshCore.KissFreq";

  /** Control path (not KISS data port {@code 0x00}). */
    private static final byte CONTROL_COMMAND = 0x06;
    private static final byte SUBCMD_FREQ = (byte) 0xEA;
    private static final byte SUBCMD_RETURN = (byte) 0xEB;

    private static volatile boolean frequencyLocked;

    private KissRadioFrequencyControl() {}

    public static boolean isFrequencyLocked() {
        return frequencyLocked;
    }

    /**
     * Lock RF to {@code freqMHz} over the open Bluetooth SPP stream.
     */
    public static boolean lockFrequency(BtConnectionManager bt, float freqMHz) {
        if (bt == null || !bt.isConnected()) {
            Log.w(TAG, "lockFrequency: not connected");
            return false;
        }
        if (freqMHz <= 0f) {
            Log.w(TAG, "lockFrequency: invalid MHz " + freqMHz);
            return false;
        }
        byte[] frame = buildLockFrame(freqMHz);
        boolean ok = bt.sendRawBytes(frame);
        if (ok) {
            frequencyLocked = true;
            Log.i(TAG, String.format(Locale.US, "KISS frequency lock sent: %.5f MHz", freqMHz));
        }
        return ok;
    }

    /**
     * Release single-frequency lock ({@code 0xEB} return command).
     */
    public static boolean unlockFrequency(BtConnectionManager bt) {
        if (!frequencyLocked) {
            return true;
        }
        if (bt == null || !bt.isConnected()) {
            frequencyLocked = false;
            return false;
        }
        byte[] frame = buildUnlockFrame();
        boolean ok = bt.sendRawBytes(frame);
        frequencyLocked = false;
        if (ok) {
            Log.i(TAG, "KISS frequency unlock sent");
        } else {
            Log.w(TAG, "KISS frequency unlock send failed");
        }
        return ok;
    }

    public static byte[] buildLockFrame(float freqMHz) {
        long freqHz = (long) (freqMHz * 1_000_000f);
        byte[] hz = new byte[]{
                (byte) ((freqHz >> 24) & 0xFF),
                (byte) ((freqHz >> 16) & 0xFF),
                (byte) ((freqHz >> 8) & 0xFF),
                (byte) (freqHz & 0xFF)
        };
        List<Byte> body = new ArrayList<>(8);
        body.add(CONTROL_COMMAND);
        body.add(SUBCMD_FREQ);
        for (byte b : hz) {
            appendEscaped(body, b);
        }
        return wrapFend(body);
    }

    public static byte[] buildUnlockFrame() {
        List<Byte> body = new ArrayList<>(4);
        body.add(CONTROL_COMMAND);
        body.add(SUBCMD_RETURN);
        return wrapFend(body);
    }

    private static void appendEscaped(List<Byte> out, byte value) {
        if (value == KissConstants.FEND) {
            out.add(KissConstants.FESC);
            out.add(KissConstants.TFEND);
        } else if (value == KissConstants.FESC) {
            out.add(KissConstants.FESC);
            out.add(KissConstants.TFESC);
        } else {
            out.add(value);
        }
    }

    private static byte[] wrapFend(List<Byte> inner) {
        byte[] frame = new byte[inner.size() + 2];
        frame[0] = KissConstants.FEND;
        for (int i = 0; i < inner.size(); i++) {
            frame[i + 1] = inner.get(i);
        }
        frame[frame.length - 1] = KissConstants.FEND;
        return frame;
    }
}
