package com.atakmaps.meshcore.plugin.ax25;

/**
 * MeshCore-specific iconset constants and icon path helpers.
 */
public final class MeshcoreIconset {

    public static final String ICONSET_UID = "8a8cd53f-3e18-4f0a-9e45-7287f0564d22";

    private MeshcoreIconset() {
    }

    public static String iconsetPathForSymbolCode(char symbolCode) {
        if (symbolCode == '>') {
            return ICONSET_UID + "/meshcore.png";
        }
        char letter = Character.toUpperCase(symbolCode);
        if (letter >= 'A' && letter <= 'Z') {
            return ICONSET_UID + "/letters/mc-" + letter + ".png";
        }
        return null;
    }

    public static String iconsetPathForLetter(char letter) {
        char normalized = Character.toUpperCase(letter);
        if (normalized < 'A' || normalized > 'Z') {
            return null;
        }
        return ICONSET_UID + "/letters/mc-" + normalized + ".png";
    }
}
