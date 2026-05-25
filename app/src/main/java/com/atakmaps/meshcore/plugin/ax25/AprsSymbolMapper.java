package com.atakmaps.meshcore.plugin.ax25;

import java.util.Locale;

/**
 * Maps APRS symbol table/code bytes to ATAK imported iconset paths.
 */
public final class AprsSymbolMapper {

    public static final String ICONSET_UID = "b3e4a5c6-d7e8-4f90-a1b2-c3d4e5f6a7b8";

    private static final String OVERLAY_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private AprsSymbolMapper() {
    }

    public static String iconsetPath(char symbolTable, char symbolCode) {
        if (symbolCode < '!' || symbolCode > '~') {
            return null;
        }
        String codeHex = String.format(Locale.US, "%02x", (int) symbolCode);

        if (symbolTable == '/') {
            return ICONSET_UID + "/Primary/p-" + codeHex + ".png";
        }
        if (symbolTable == '\\') {
            return ICONSET_UID + "/Alternate/a-" + codeHex + ".png";
        }
        if (OVERLAY_CHARS.indexOf(Character.toUpperCase(symbolTable)) >= 0) {
            // Phase 1: overlay glyph packets use alternate base symbol only.
            return ICONSET_UID + "/Alternate/a-" + codeHex + ".png";
        }
        return null;
    }
}
