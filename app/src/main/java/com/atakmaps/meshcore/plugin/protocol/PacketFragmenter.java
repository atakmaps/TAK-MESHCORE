package com.atakmaps.meshcore.plugin.protocol;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits large payloads into radio-sized fragments and reassembles them.
 *
 * AX.25 info field is limited to ~256 bytes. Large CoT XML (even gzipped)
 * may exceed this, requiring fragmentation.
 *
 * Fragment header (4 bytes):
 *   [FragmentID: 2 bytes] [SeqNum: 1 byte] [TotalFragments: 1 byte]
 *
 * When TotalFragments == 0, the packet is unfragmented (no header overhead).
 */
public class PacketFragmenter {

    private static final String TAG = "MeshCore.Fragment";

    /**
     * Maximum payload size per AX.25 info field.
     * AX.25 max is 256, minus our MeshCore TLV header (3 bytes) and
     * fragment header (4 bytes), we keep payload at 240 bytes max.
     */
    public static final int MAX_FRAGMENT_PAYLOAD = 240;

    /** Fragment header size */
    public static final int FRAGMENT_HEADER_SIZE = 4;

    /**
     * Counter for generating fragment IDs. Starts at a random value so a freshly restarted
     * station does not reuse the IDs it (or another station) sent moments ago.
     */
    private static int nextFragmentId = new java.security.SecureRandom().nextInt(0x10000);

    /**
     * Fragment a payload if it exceeds the maximum frame size.
     *
     * @param type    MeshCore packet type
     * @param payload The full payload to fragment
     * @return List of MeshCorePacket instances ready for AX.25 framing.
     *         If payload fits in one frame, returns a single unfragmented packet.
     */
    public static List<MeshCorePacket> fragment(int type, byte[] payload) {
        List<MeshCorePacket> results = new ArrayList<>();

        if (payload.length <= MAX_FRAGMENT_PAYLOAD) {
            // No fragmentation needed
            results.add(new MeshCorePacket((byte) type, payload));
            return results;
        }

        // Must fragment — use TYPE_COT_FRAGMENT for CoT fragments
        int fragType = (type == MeshCorePacket.TYPE_COT)
                ? MeshCorePacket.TYPE_COT_FRAGMENT : type;

        int fragmentId = getNextFragmentId();
        int maxPayloadPerFrag = MAX_FRAGMENT_PAYLOAD - FRAGMENT_HEADER_SIZE;
        int totalFragments = (payload.length + maxPayloadPerFrag - 1)
                / maxPayloadPerFrag;

        if (totalFragments > 255) {
            Log.e(TAG, "Payload too large to fragment: " + payload.length
                    + " bytes would require " + totalFragments + " fragments");
            return results; // Return empty — can't send
        }

        Log.d(TAG, "Fragmenting " + payload.length + " bytes into "
                + totalFragments + " fragments (id=" + fragmentId + ")");

        for (int i = 0; i < totalFragments; i++) {
            int offset = i * maxPayloadPerFrag;
            int len = Math.min(maxPayloadPerFrag, payload.length - offset);

            ByteBuffer buf = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + len);
            buf.putShort((short) fragmentId);
            buf.put((byte) i);
            buf.put((byte) totalFragments);
            buf.put(payload, offset, len);

            results.add(new MeshCorePacket((byte) fragType, buf.array()));
        }

        return results;
    }

    private static synchronized int getNextFragmentId() {
        int id = nextFragmentId;
        nextFragmentId = (nextFragmentId + 1) & 0xFFFF;
        return id;
    }

    /**
     * Reassembler: collects fragments and emits complete payloads.
     *
     * Thread-safe. Fragments are identified by (sender, fragmentId) so two stations that
     * happen to pick the same 16-bit ID cannot corrupt each other's payloads.
     * Incomplete fragment sets are expired after a timeout, and the number of
     * in-flight sets is capped so junk traffic cannot grow memory without bound.
     */
    public static class Reassembler {

        private static final long FRAGMENT_TIMEOUT_MS = 30_000; // 30 seconds

        /** Max incomplete fragment sets held at once; oldest is evicted beyond this. */
        static final int MAX_PENDING_SETS = 64;

        private final Map<String, FragmentSet> pending = new HashMap<>();

        /**
         * Add a fragment. If this completes a set, returns the full payload.
         *
         * @param sender          Stable identifier of the transmitting station
         *                        (e.g. callsign-SSID); {@code null} is treated as unknown
         * @param fragmentPayload The fragment data including the 4-byte header
         * @return Complete reassembled payload, or null if still waiting
         */
        public synchronized byte[] addFragment(String sender, byte[] fragmentPayload) {
            if (fragmentPayload == null || fragmentPayload.length < FRAGMENT_HEADER_SIZE) {
                Log.w(TAG, "Fragment too short: "
                        + (fragmentPayload == null ? "null" : fragmentPayload.length));
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(fragmentPayload);
            int fragmentId = buf.getShort() & 0xFFFF;
            int seqNum = buf.get() & 0xFF;
            int totalFragments = buf.get() & 0xFF;

            if (totalFragments == 0 || seqNum >= totalFragments) {
                Log.w(TAG, "Invalid fragment: seq=" + seqNum
                        + " total=" + totalFragments);
                return null;
            }

            // Expire old fragment sets
            expireOld();

            String key = (sender != null ? sender : "?") + "#" + fragmentId;
            FragmentSet set = pending.get(key);
            if (set != null && set.totalFragments != totalFragments) {
                // Same sender reused the ID for a different payload; start over.
                Log.d(TAG, "Fragment set " + key + " restarted (total "
                        + set.totalFragments + " -> " + totalFragments + ")");
                pending.remove(key);
                set = null;
            }
            if (set == null) {
                evictOldestIfFull();
                set = new FragmentSet(fragmentId, totalFragments);
                pending.put(key, set);
            }

            // Copy data (without header)
            byte[] data = new byte[fragmentPayload.length - FRAGMENT_HEADER_SIZE];
            System.arraycopy(fragmentPayload, FRAGMENT_HEADER_SIZE,
                    data, 0, data.length);

            set.addFragment(seqNum, data);

            if (set.isComplete()) {
                pending.remove(key);
                byte[] result = set.reassemble();
                Log.d(TAG, "Reassembled fragment set " + fragmentId
                        + ": " + result.length + " bytes");
                return result;
            }

            Log.d(TAG, "Fragment " + seqNum + "/" + totalFragments
                    + " of set " + fragmentId + " received ("
                    + set.getReceivedCount() + "/" + totalFragments + ")");
            return null;
        }

        private void evictOldestIfFull() {
            if (pending.size() < MAX_PENDING_SETS) {
                return;
            }
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, FragmentSet> e : pending.entrySet()) {
                if (e.getValue().createdAt < oldest) {
                    oldest = e.getValue().createdAt;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) {
                pending.remove(oldestKey);
            }
        }

        synchronized int pendingCount() {
            return pending.size();
        }

        private void expireOld() {
            long now = System.currentTimeMillis();
            pending.entrySet().removeIf(entry ->
                    now - entry.getValue().createdAt > FRAGMENT_TIMEOUT_MS);
        }

        /**
         * Internal: Holds fragments for a single payload.
         */
        private static class FragmentSet {
            final int fragmentId;
            final int totalFragments;
            final byte[][] fragments;
            int receivedCount;
            final long createdAt;

            FragmentSet(int id, int total) {
                this.fragmentId = id;
                this.totalFragments = total;
                this.fragments = new byte[total][];
                this.receivedCount = 0;
                this.createdAt = System.currentTimeMillis();
            }

            void addFragment(int seq, byte[] data) {
                if (seq >= 0 && seq < totalFragments
                        && fragments[seq] == null) {
                    fragments[seq] = data;
                    receivedCount++;
                }
            }

            boolean isComplete() {
                return receivedCount == totalFragments;
            }

            int getReceivedCount() {
                return receivedCount;
            }

            byte[] reassemble() {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (byte[] fragment : fragments) {
                    if (fragment != null) {
                        out.write(fragment, 0, fragment.length);
                    }
                }
                return out.toByteArray();
            }
        }
    }
}
