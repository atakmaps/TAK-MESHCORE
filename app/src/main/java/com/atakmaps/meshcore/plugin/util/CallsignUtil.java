package com.atakmaps.meshcore.plugin.util;

public class CallsignUtil {

    public static String toRadioCallsign(String atakCallsign) {
        if (atakCallsign == null) return "UNKNOWN";

        String cs = atakCallsign.toUpperCase()
                .replaceAll("[^A-Z0-9]", "");

        if (cs.length() > 1) {
            String first = cs.substring(0,1);
            String rest = cs.substring(1)
                    .replaceAll("[AEIOU]", "");
            cs = first + rest;
        }

        if (cs.length() > 6) {
            cs = cs.substring(0, 6);
        }

        return cs;
    }

    /** True when both names resolve to the same 6-character wire callsign. */
    public static boolean isSameRadioStation(String callsignA, String callsignB) {
        if (callsignA == null || callsignB == null) {
            return false;
        }
        String wireA = toRadioCallsign(callsignA.trim());
        String wireB = toRadioCallsign(callsignB.trim());
        return !wireA.isEmpty() && wireA.equalsIgnoreCase(wireB);
    }
}
