package com.atakmaps.meshcore.plugin.bluetooth;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Rolling cap rules for mesh node device contact tables. */
public final class MeshDeviceContactPolicy {

    public static final int ROLLING_MAX = 75;

    private MeshDeviceContactPolicy() {
    }

    @NonNull
    public static List<BtConnectionManager.MeshDeviceContact> contactsToEvictFromDevice(
            @NonNull List<BtConnectionManager.MeshDeviceContact> contacts) {
        if (contacts.size() <= ROLLING_MAX) {
            return Collections.emptyList();
        }
        List<BtConnectionManager.MeshDeviceContact> nonFavorites = new ArrayList<>();
        for (BtConnectionManager.MeshDeviceContact contact : contacts) {
            if (contact != null && !contact.isFavorite()) {
                nonFavorites.add(contact);
            }
        }
        nonFavorites.sort(Comparator.comparingInt(c -> c.lastMod));
        int excess = contacts.size() - ROLLING_MAX;
        int removeCount = Math.min(excess, nonFavorites.size());
        if (removeCount <= 0) {
            return Collections.emptyList();
        }
        return new ArrayList<>(nonFavorites.subList(0, removeCount));
    }

    @NonNull
    public static List<BtConnectionManager.MeshDeviceContact> filterRemoved(
            @NonNull List<BtConnectionManager.MeshDeviceContact> contacts,
            @NonNull List<BtConnectionManager.MeshDeviceContact> removed) {
        if (removed.isEmpty()) {
            return contacts;
        }
        List<String> removedKeys = new ArrayList<>(removed.size());
        for (BtConnectionManager.MeshDeviceContact contact : removed) {
            if (contact != null && contact.pubKeyHex != null) {
                removedKeys.add(contact.pubKeyHex.trim().toUpperCase());
            }
        }
        List<BtConnectionManager.MeshDeviceContact> kept = new ArrayList<>();
        for (BtConnectionManager.MeshDeviceContact contact : contacts) {
            if (contact == null || contact.pubKeyHex == null) {
                continue;
            }
            String key = contact.pubKeyHex.trim().toUpperCase();
            if (!removedKeys.contains(key)) {
                kept.add(contact);
            }
        }
        return kept;
    }
}
