package com.atakmaps.meshcore.plugin.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

public class BoundedGzipTest {

    private static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(in);
        }
        return bos.toByteArray();
    }

    @Test
    public void roundTripUnderLimit() throws Exception {
        byte[] xml = "<event version='2.0' uid='abc'/>".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(xml, BoundedGzip.gunzip(gzip(xml), 1024));
    }

    @Test
    public void exactlyAtLimitIsAllowed() throws Exception {
        byte[] data = new byte[4096];
        assertArrayEquals(data, BoundedGzip.gunzip(gzip(data), 4096));
    }

    @Test
    public void zipBombIsRejected() throws Exception {
        // 16 MB of zeros compresses to roughly 16 KB — small enough to arrive over RF.
        byte[] bomb = gzip(new byte[16 * 1024 * 1024]);
        assertTrue(bomb.length < 64 * 1024);
        try {
            BoundedGzip.gunzip(bomb, 256 * 1024);
            fail("expected LimitExceededException");
        } catch (BoundedGzip.LimitExceededException expected) {
            // ok
        }
    }

    @Test(expected = IOException.class)
    public void garbageIsRejected() throws Exception {
        BoundedGzip.gunzip(new byte[]{1, 2, 3, 4, 5}, 1024);
    }

    @Test(expected = IOException.class)
    public void nullIsRejected() throws Exception {
        BoundedGzip.gunzip(null, 1024);
    }
}
