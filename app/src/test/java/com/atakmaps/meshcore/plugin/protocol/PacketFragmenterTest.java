package com.atakmaps.meshcore.plugin.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;

public class PacketFragmenterTest {

    private static byte[] payload(int len, long seed) {
        byte[] b = new byte[len];
        new Random(seed).nextBytes(b);
        return b;
    }

    /** Fragment header + data, as a remote station with the given fragment ID would send. */
    private static byte[] frag(int id, int seq, int total, byte[] data) {
        return ByteBuffer.allocate(4 + data.length)
                .putShort((short) id).put((byte) seq).put((byte) total).put(data).array();
    }

    @Test
    public void smallPayloadIsNotFragmented() {
        byte[] p = payload(100, 1);
        List<MeshCorePacket> pkts = PacketFragmenter.fragment(MeshCorePacket.TYPE_COT, p);
        assertEquals(1, pkts.size());
        assertEquals(MeshCorePacket.TYPE_COT, pkts.get(0).getType());
        assertArrayEquals(p, pkts.get(0).getPayload());
    }

    @Test
    public void fragmentAndReassembleInOrderAndShuffled() {
        byte[] p = payload(1000, 2);
        List<MeshCorePacket> pkts = PacketFragmenter.fragment(MeshCorePacket.TYPE_COT, p);
        assertTrue(pkts.size() > 1);
        for (MeshCorePacket pk : pkts) {
            assertEquals(MeshCorePacket.TYPE_COT_FRAGMENT, pk.getType());
            assertTrue(pk.getPayload().length <= PacketFragmenter.MAX_FRAGMENT_PAYLOAD);
        }

        PacketFragmenter.Reassembler r = new PacketFragmenter.Reassembler();
        byte[] out = null;
        for (int i = pkts.size() - 1; i >= 0; i--) {
            out = r.addFragment("ALPHA-0", pkts.get(i).getPayload());
            if (i > 0) {
                assertNull(out);
            }
        }
        assertArrayEquals(p, out);
        assertEquals(0, r.pendingCount());
    }

    @Test
    public void sameFragmentIdFromTwoSendersDoesNotCollide() {
        byte[] a0 = payload(50, 10), a1 = payload(50, 11);
        byte[] b0 = payload(50, 20), b1 = payload(50, 21);
        PacketFragmenter.Reassembler r = new PacketFragmenter.Reassembler();

        // Both stations restarted and picked fragment ID 0; frames interleave on air.
        assertNull(r.addFragment("ALPHA-0", frag(0, 0, 2, a0)));
        assertNull(r.addFragment("BRAVO-0", frag(0, 0, 2, b0)));
        byte[] fromB = r.addFragment("BRAVO-0", frag(0, 1, 2, b1));
        byte[] fromA = r.addFragment("ALPHA-0", frag(0, 1, 2, a1));

        assertArrayEquals(concat(a0, a1), fromA);
        assertArrayEquals(concat(b0, b1), fromB);
    }

    @Test
    public void changedTotalForSameIdRestartsTheSet() {
        PacketFragmenter.Reassembler r = new PacketFragmenter.Reassembler();
        assertNull(r.addFragment("A-0", frag(7, 0, 3, payload(10, 1))));
        byte[] x0 = payload(10, 2), x1 = payload(10, 3);
        assertNull(r.addFragment("A-0", frag(7, 0, 2, x0)));
        assertArrayEquals(concat(x0, x1), r.addFragment("A-0", frag(7, 1, 2, x1)));
    }

    @Test
    public void duplicateFragmentsAreIgnored() {
        PacketFragmenter.Reassembler r = new PacketFragmenter.Reassembler();
        byte[] d0 = payload(10, 1), d1 = payload(10, 2);
        assertNull(r.addFragment("A-0", frag(1, 0, 2, d0)));
        assertNull(r.addFragment("A-0", frag(1, 0, 2, d0)));
        assertArrayEquals(concat(d0, d1), r.addFragment("A-0", frag(1, 1, 2, d1)));
    }

    @Test
    public void invalidFragmentsAreRejected() {
        PacketFragmenter.Reassembler r = new PacketFragmenter.Reassembler();
        assertNull(r.addFragment("A-0", new byte[2]));
        assertNull(r.addFragment("A-0", null));
        assertNull(r.addFragment("A-0", frag(1, 0, 0, new byte[4])));
        assertNull(r.addFragment("A-0", frag(1, 5, 3, new byte[4])));
        assertEquals(0, r.pendingCount());
    }

    @Test
    public void pendingSetsAreCapped() {
        PacketFragmenter.Reassembler r = new PacketFragmenter.Reassembler();
        for (int i = 0; i < PacketFragmenter.Reassembler.MAX_PENDING_SETS * 3; i++) {
            r.addFragment("JUNK-" + i, frag(i, 0, 2, new byte[8]));
        }
        assertEquals(PacketFragmenter.Reassembler.MAX_PENDING_SETS, r.pendingCount());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        return ByteBuffer.allocate(a.length + b.length).put(a).put(b).array();
    }
}
