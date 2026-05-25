package com.atakmaps.meshcore.plugin.aprs;

import java.util.Locale;

/**
 * Builds APRS position report info fields for outbound KISS transmission.
 */
public final class AprsBeaconBuilder {

    private static final int MAX_COMMENT_LEN = 43;

    private AprsBeaconBuilder() {
    }

    /**
     * Uncompressed position with leading {@code !} (no messaging flag).
     * Format: !DDMM.mmN/DDDMM.mmW + symbol table/code + optional comment.
     */
    public static String buildUncompressedPositionBeacon(double lat, double lon,
                                                         char symbolTable, char symbolCode,
                                                         String comment) {
        String latStr = formatLatitude(lat);
        String lonStr = formatLongitude(lon);
        if (latStr == null || lonStr == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        sb.append('!');
        sb.append(latStr);
        sb.append(symbolTable);
        sb.append(lonStr);
        sb.append(symbolCode);
        String c = sanitizeComment(comment);
        if (!c.isEmpty()) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String sanitizeComment(String comment) {
        if (comment == null) {
            return "";
        }
        String c = comment.replace('\r', ' ').replace('\n', ' ').trim();
        if (c.length() > MAX_COMMENT_LEN) {
            c = c.substring(0, MAX_COMMENT_LEN);
        }
        return c;
    }

    private static String formatLatitude(double lat) {
        if (Double.isNaN(lat) || lat < -90.0 || lat > 90.0) {
            return null;
        }
        char ns = lat >= 0 ? 'N' : 'S';
        double abs = Math.abs(lat);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%02d%05.2f%c", deg, min, ns);
    }

    private static String formatLongitude(double lon) {
        if (Double.isNaN(lon) || lon < -180.0 || lon > 180.0) {
            return null;
        }
        char ew = lon >= 0 ? 'E' : 'W';
        double abs = Math.abs(lon);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        return String.format(Locale.US, "%03d%05.2f%c", deg, min, ew);
    }
}
