package com.atakmaps.meshcore.plugin.chat;

import android.util.Log;

import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.contact.PluginConnector;
import com.atakmap.android.maps.MapView;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Contact de-duplication and canonical UID selection (ported from UV-PRO / Darksteal).
 */
public final class ContactMergeUtil {

    private static final String TAG = "MeshCore.ContactMerge";
    private static final String ANDROID_UID_PREFIX = "ANDROID-";
    private static final String MESH_NODE_UID_PREFIX = "MESHCORE-NODE-";
    private static final String MESH_RPTR_UID_PREFIX = "MESHCORE-RPTR-";
    private static final Pattern OPAQUE_WIFI_DEVICE_UID =
            Pattern.compile("^[0-9A-F]{16}$");

    private static CotBridge mergeRoutingBridge;

    private ContactMergeUtil() {
    }

    public static void setMergeRoutingBridge(CotBridge bridge) {
        mergeRoutingBridge = bridge;
    }

    public static CotBridge getMergeRoutingBridge() {
        return mergeRoutingBridge;
    }

    public static String resolveCanonicalPeerUid(String callsignRaw, String... candidateUids) {
        String callsign = callsignRaw != null ? callsignRaw.trim().toUpperCase(Locale.US) : "";
        try {
            Contacts contacts = Contacts.getInstance();
            if (!callsign.isEmpty()) {
                Contact byCallsign = findContactByCallsignVariants(contacts, callsign);
                if (byCallsign != null && !byCallsign.getUID().isEmpty()) {
                    return byCallsign.getUID();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "resolveCanonicalPeerUid callsign lookup failed", e);
        }
        if (candidateUids != null) {
            for (String raw : candidateUids) {
                if (raw == null || raw.trim().isEmpty()) {
                    continue;
                }
                String uid = raw.trim();
                if (isMeshNodeUid(uid)) {
                    return uid.toUpperCase(Locale.US);
                }
                try {
                    Contact c = Contacts.getInstance().getContactByUuid(uid);
                    if (c != null) {
                        return c.getUID();
                    }
                } catch (Exception ignored) {
                }
                if (uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
                    String bare = uid.substring(ANDROID_UID_PREFIX.length());
                    try {
                        Contact byCall = findContactByCallsignVariants(
                                Contacts.getInstance(), bare);
                        if (byCall != null && !byCall.getUID().isEmpty()) {
                            return byCall.getUID();
                        }
                    } catch (Exception ignored) {
                    }
                    if (callsign.isEmpty()) {
                        return uid.toUpperCase(Locale.US);
                    }
                }
            }
        }
        if (!callsign.isEmpty()) {
            return ChatBridge.syntheticAndroidUid(callsign);
        }
        return "";
    }

    public static void collapseDuplicateContactsForCallsign(String rawCallsign,
                                                            String keepUidHint) {
        if (rawCallsign == null || rawCallsign.trim().isEmpty()) {
            return;
        }
        if (isMeshNodeUid(rawCallsign)) {
            return;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.isEmpty()) {
                return;
            }
            LinkedHashSet<String> variants = buildCallsignVariants(rawCallsign);
            String queryRadioKey = radioCallsignKey(rawCallsign);
            java.util.ArrayList<IndividualContact> candidates = new java.util.ArrayList<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                if (isMeshNodeUid(ic.getUID())) {
                    continue;
                }
                String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
                String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
                if (contactMatchesCallsignVariants(name, uid, variants, queryRadioKey)) {
                    candidates.add(ic);
                }
            }
            if (candidates.size() <= 1) {
                if (candidates.size() == 1) {
                    finishContactMerge(candidates.get(0), rawCallsign);
                }
                return;
            }

            IndividualContact keep = null;
            int bestScore = Integer.MIN_VALUE;
            for (IndividualContact ic : candidates) {
                int score = scorePreferredNativeContact(ic);
                String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
                if (keepUidHint != null && !keepUidHint.trim().isEmpty()
                        && uid.equalsIgnoreCase(keepUidHint.trim())) {
                    score += 200;
                }
                if (score > bestScore) {
                    bestScore = score;
                    keep = ic;
                }
            }
            if (keep == null) {
                return;
            }
            String keepUid = keep.getUID();
            for (IndividualContact ic : candidates) {
                if (ic.getUID().equalsIgnoreCase(keepUid)) {
                    continue;
                }
                Log.d(TAG, "collapseDuplicate removing uid=" + ic.getUID()
                        + " keeping uid=" + keepUid + " callsign=" + rawCallsign);
                contacts.removeContact(ic);
            }
            finishContactMerge(keep, rawCallsign);
        } catch (Exception ignored) {
        }
    }

    public static void collapseAllCallsignAliasDuplicates() {
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.isEmpty()) {
                return;
            }
            LinkedHashSet<String> seenRadioKeys = new LinkedHashSet<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                if (isMeshNodeUid(ic.getUID())) {
                    continue;
                }
                String name = ic.getName() != null ? ic.getName().trim() : "";
                if (name.isEmpty()) {
                    continue;
                }
                String radioKey = radioCallsignKey(name);
                if (radioKey.isEmpty() || radioKey.length() < 4) {
                    continue;
                }
                if (!seenRadioKeys.add(radioKey)) {
                    continue;
                }
                collapseDuplicateContactsForCallsign(name, null);
                String compressed = CallsignUtil.toRadioCallsign(name);
                if (compressed != null && !compressed.equalsIgnoreCase(name)) {
                    collapseDuplicateContactsForCallsign(compressed, null);
                }
            }
            removeOrphanSyntheticRadioContacts();
        } catch (Exception e) {
            Log.w(TAG, "collapseAllCallsignAliasDuplicates failed", e);
        }
    }

    static void removeOrphanSyntheticRadioContacts() {
        try {
            Contacts contacts = Contacts.getInstance();
            java.util.List<Contact> all = contacts.getAllContacts();
            if (all == null || all.size() < 2) {
                return;
            }
            java.util.ArrayList<IndividualContact> orphans = new java.util.ArrayList<>();
            for (Contact c : all) {
                if (!(c instanceof IndividualContact)) {
                    continue;
                }
                IndividualContact ic = (IndividualContact) c;
                if (isOrphanSyntheticRadioContact(ic, all)) {
                    orphans.add(ic);
                }
            }
            for (IndividualContact orphan : orphans) {
                Log.d(TAG, "Removing orphan synthetic radio contact uid=" + orphan.getUID()
                        + " callsign=" + orphan.getName());
                contacts.removeContact(orphan);
            }
        } catch (Exception e) {
            Log.w(TAG, "removeOrphanSyntheticRadioContacts failed", e);
        }
    }

    public static String displayCallsignForContact(String callsignFallback, String uid) {
        if (uid != null && !uid.trim().isEmpty()) {
            try {
                Contact c = Contacts.getInstance().getContactByUuid(uid.trim());
                if (c != null && c.getName() != null && !c.getName().trim().isEmpty()) {
                    return c.getName().trim();
                }
            } catch (Exception ignored) {
            }
        }
        String fallback = callsignFallback != null ? callsignFallback.trim() : "";
        if (!fallback.isEmpty()) {
            return fallback;
        }
        if (uid != null && uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            String bare = uid.substring(ANDROID_UID_PREFIX.length());
            if (bare.matches("^[0-9A-F]{16}$")) {
                return uid;
            }
            return bare;
        }
        return uid != null ? uid : "";
    }

    public static Contact findContactByCallsignVariants(Contacts contacts, String rawCallsign) {
        if (contacts == null) {
            return null;
        }
        LinkedHashSet<String> variants = buildCallsignVariants(rawCallsign);
        String queryRadioKey = radioCallsignKey(rawCallsign);
        java.util.List<Contact> all = contacts.getAllContacts();
        if (all == null || all.isEmpty()) {
            return null;
        }

        IndividualContact best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Contact c : all) {
            if (!(c instanceof IndividualContact)) {
                continue;
            }
            IndividualContact ic = (IndividualContact) c;
            String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
            String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
            if (!contactMatchesCallsignVariants(name, uid, variants, queryRadioKey)) {
                continue;
            }
            int score = scorePreferredNativeContact(ic);
            if (score > bestScore) {
                bestScore = score;
                best = ic;
            }
        }
        return best;
    }

    private static void finishContactMerge(IndividualContact keep, String callsignRaw) {
        if (keep == null) {
            return;
        }
        CotBridge bridge = mergeRoutingBridge;
        if (bridge == null) {
            return;
        }
        bridge.registerMergedContact(keep);
        if (callsignRaw != null && !callsignRaw.trim().isEmpty()) {
            bridge.removeOrphanRfMapMarkerForCallsign(callsignRaw, keep.getUID());
        } else if (keep.getName() != null && !keep.getName().trim().isEmpty()) {
            bridge.removeOrphanRfMapMarkerForCallsign(keep.getName(), keep.getUID());
        }
    }

    private static boolean isOrphanSyntheticRadioContact(IndividualContact ic,
                                                         java.util.List<Contact> all) {
        if (ic == null || all == null || isMeshNodeUid(ic.getUID())) {
            return false;
        }
        String uid = ic.getUID();
        String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
        if (name.isEmpty() || !isSyntheticCallsignUid(uid, name)) {
            return false;
        }
        if (name.contains("_") || name.contains("-")) {
            return false;
        }
        String radioKey = radioCallsignKey(name);
        if (radioKey.isEmpty() || radioKey.length() < 4) {
            return false;
        }
        int selfScore = scorePreferredNativeContact(ic);
        for (Contact c : all) {
            if (c == ic || !(c instanceof IndividualContact)) {
                continue;
            }
            IndividualContact other = (IndividualContact) c;
            String otherName = other.getName() != null
                    ? other.getName().trim().toUpperCase(Locale.US) : "";
            if (otherName.isEmpty()) {
                continue;
            }
            if (!radioKey.equals(radioCallsignKey(otherName))) {
                continue;
            }
            if (scorePreferredNativeContact(other) > selfScore) {
                return true;
            }
        }
        return false;
    }

    private static boolean contactMatchesCallsignVariants(String contactName, String contactUid,
                                                            LinkedHashSet<String> variants,
                                                            String queryRadioKey) {
        boolean match = variants.contains(contactName);
        String bareUid = "";
        if (contactUid != null && contactUid.startsWith(ANDROID_UID_PREFIX)) {
            bareUid = contactUid.substring(ANDROID_UID_PREFIX.length());
            if (!match) {
                match = variants.contains(bareUid);
            }
        }
        if (!match && queryRadioKey != null && !queryRadioKey.isEmpty()
                && queryRadioKey.length() >= 4) {
            match = queryRadioKey.equals(radioCallsignKey(contactName));
            if (!match && !bareUid.isEmpty()) {
                match = queryRadioKey.equals(radioCallsignKey(bareUid));
            }
        }
        return match;
    }

    static String radioCallsignKey(String rawCallsign) {
        if (rawCallsign == null) {
            return "";
        }
        String radio = CallsignUtil.toRadioCallsign(rawCallsign.trim());
        return radio != null ? radio.trim().toUpperCase(Locale.US) : "";
    }

    private static LinkedHashSet<String> buildCallsignVariants(String rawCallsign) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (rawCallsign == null) {
            return variants;
        }
        String base = rawCallsign.trim().toUpperCase(Locale.US);
        if (base.isEmpty()) {
            return variants;
        }
        variants.add(base);
        variants.add(base.replaceAll("[^A-Z0-9_\\-]", ""));
        variants.add(base.replace("_", ""));
        variants.add(base.replace("-", ""));
        String radio = CallsignUtil.toRadioCallsign(base);
        if (radio != null && !radio.trim().isEmpty()) {
            variants.add(radio.trim().toUpperCase(Locale.US));
        }
        return variants;
    }

    private static int scorePreferredNativeContact(IndividualContact ic) {
        int score = 0;
        if (ic == null) {
            return score;
        }
        String uid = ic.getUID() != null ? ic.getUID().trim().toUpperCase(Locale.US) : "";
        String name = ic.getName() != null ? ic.getName().trim().toUpperCase(Locale.US) : "";
        if (ic.getConnector(GeoChatConnector.CONNECTOR_TYPE) != null) {
            score += 300;
        }
        if (ic.getConnector(IpConnector.CONNECTOR_TYPE) != null) {
            score += 100;
        }
        if (isOpaqueWifiDeviceUid(uid)) {
            score += 400;
        }
        if (isSyntheticCallsignUid(uid, name)) {
            score -= 250;
        }
        if (!uid.startsWith(ANDROID_UID_PREFIX)) {
            score += 50;
        }
        if (ic.getConnector(PluginConnector.CONNECTOR_TYPE) != null) {
            score -= 40;
        }
        if (name.contains("_")) {
            score += 25;
        }
        if (name.length() > 6) {
            score += 10;
        }
        return score;
    }

    private static boolean isOpaqueWifiDeviceUid(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return false;
        }
        String upper = uid.trim().toUpperCase(Locale.US);
        if (upper.startsWith(ANDROID_UID_PREFIX)) {
            upper = upper.substring(ANDROID_UID_PREFIX.length());
        }
        return OPAQUE_WIFI_DEVICE_UID.matcher(upper).matches();
    }

    private static boolean isSyntheticCallsignUid(String uid, String callsign) {
        if (uid == null || callsign == null) {
            return false;
        }
        String normalizedCall = callsign.trim().toUpperCase(Locale.US);
        if (normalizedCall.isEmpty()) {
            return false;
        }
        return uid.trim().equalsIgnoreCase(ANDROID_UID_PREFIX + normalizedCall);
    }

    static boolean isMeshNodeUid(String uid) {
        if (uid == null) {
            return false;
        }
        String u = uid.trim().toUpperCase(Locale.US);
        return u.startsWith(MESH_NODE_UID_PREFIX) || u.startsWith(MESH_RPTR_UID_PREFIX);
    }
}
