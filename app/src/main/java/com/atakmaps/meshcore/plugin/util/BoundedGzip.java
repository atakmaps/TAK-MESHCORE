package com.atakmaps.meshcore.plugin.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * GZIP decompression with a hard output cap, for data received over RF.
 *
 * <p>A few hundred bytes of crafted GZIP can inflate to hundreds of megabytes, so
 * anything decompressed from the air must be bounded.</p>
 */
public final class BoundedGzip {

    private BoundedGzip() {
    }

    /** Thrown when the decompressed output would exceed the caller's limit. */
    public static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        public LimitExceededException(int limit) {
            super("decompressed size exceeds " + limit + " bytes");
        }
    }

    /**
     * Decompress {@code compressed}, failing as soon as output passes {@code maxBytes}.
     *
     * @throws LimitExceededException if the output would be larger than {@code maxBytes}
     * @throws IOException            if the data is not valid GZIP
     */
    public static byte[] gunzip(byte[] compressed, int maxBytes) throws IOException {
        if (compressed == null) {
            throw new IOException("null input");
        }
        byte[] buf = new byte[512];
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int total = 0;
            int n;
            while ((n = gzip.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new LimitExceededException(maxBytes);
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
