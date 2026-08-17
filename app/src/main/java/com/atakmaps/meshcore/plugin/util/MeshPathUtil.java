package com.atakmaps.meshcore.plugin.util;

/**
 * MeshCore {@code path_len} encoding helpers (mirrors {@code mesh::Packet} in firmware).
 */
public final class MeshPathUtil {

    private MeshPathUtil() {
    }

    /** {@code OUT_PATH_UNKNOWN} — companion should flood. */
    public static boolean isFloodPathLen(int pathLen) {
        return (pathLen & 0xFF) == 0xFF;
    }

    /** True when {@code path_len} is valid for {@code sendDirect} (not flood/unknown). */
    public static boolean isUsableDirectPath(int pathLen) {
        int encoded = pathLen & 0xFF;
        if (encoded == 0xFF || encoded == 0) {
            return false;
        }
        int hashCount = encoded & 63;
        int hashSize = ((encoded >> 6) & 0x03) + 1;
        if (hashSize == 4) {
            return false;
        }
        return hashCount * hashSize <= 64;
    }

    /** Wire byte length written after {@code path_len} in {@code CMD_SEND_CHANNEL_DATA}. */
    public static int pathWireByteLen(int pathLen) {
        if (!isUsableDirectPath(pathLen)) {
            return 0;
        }
        int encoded = pathLen & 0xFF;
        int hashCount = encoded & 63;
        int hashSize = ((encoded >> 6) & 0x03) + 1;
        return hashCount * hashSize;
    }

    /**
     * Extract the path bytes to append after {@code path_len} for companion TX.
     *
     * @param pathLen encoded {@code contact.out_path_len}
     * @param pathBuf 64-byte contact path buffer from firmware (may be null)
     */
    public static byte[] pathWireBytes(int pathLen, byte[] pathBuf) {
        int wireLen = pathWireByteLen(pathLen);
        if (wireLen <= 0) {
            return null;
        }
        if (pathBuf == null || pathBuf.length < wireLen) {
            return null;
        }
        byte[] out = new byte[wireLen];
        System.arraycopy(pathBuf, 0, out, 0, wireLen);
        return out;
    }

    /** Repeater hop count for logging (hash count in path). */
    public static int pathHopCount(int pathLen) {
        if (!isUsableDirectPath(pathLen)) {
            return -1;
        }
        return pathLen & 63;
    }

    /**
     * RX path label from firmware channel metadata ({@code path_len} byte on receive).
     * {@code pathLen <= 0} means no repeater-hop metadata (direct RF or unknown).
     */
    public static String formatRxRepeaterPath(int pathLen) {
        if (pathLen <= 0) {
            return "direct-or-unknown";
        }
        return pathLen + (pathLen == 1 ? " repeater-hop" : " repeater-hops");
    }

    /** TX route label from encoded {@code path_len} on {@code CMD_SEND_CHANNEL_DATA}. */
    public static String formatTxRoute(int pathLenEncoded) {
        if (isFloodPathLen(pathLenEncoded)) {
            return "flood";
        }
        if (isUsableDirectPath(pathLenEncoded)) {
            int hops = pathHopCount(pathLenEncoded);
            return hops == 1
                    ? "direct-via-repeater hops=1"
                    : "direct-via-repeater hops=" + hops;
        }
        return "unknown";
    }

    /**
     * Normalize RX {@code path_len} byte to a repeater hop/repeat count for logging.
     * Returns {@code null} when firmware marks flood/unknown ({@code 0xFF}).
     */
    public static Integer rxPathLenToHopCount(int pathLenRaw) {
        int encoded = pathLenRaw & 0xFF;
        if (encoded == 0xFF) {
            return null;
        }
        if (isUsableDirectPath(encoded)) {
            return pathHopCount(encoded);
        }
        int repeats = encoded & 0x3F;
        return repeats > 0 ? repeats : null;
    }
}
