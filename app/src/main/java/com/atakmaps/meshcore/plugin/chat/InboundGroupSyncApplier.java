package com.atakmaps.meshcore.plugin.chat;

import android.content.Intent;
import android.os.Bundle;

import com.atakmap.android.chat.ChatDatabase;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.ContactPresenceDropdown;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.GroupContact;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.util.NotificationUtil;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Makes RF-imported GeoChat groups visible in Contacts (same behavior users see on Wi‑Fi).
 */
public final class InboundGroupSyncApplier {

    private static final String TAG = "MeshCore.GroupSync";
    private static final String ANDROID_UID_PREFIX = "ANDROID-";

    private InboundGroupSyncApplier() {
    }

    public static void applyAfterInboundGroupCot(CotEvent event) {
        applyAfterInboundGroupCot(event, true);
    }

    public static void applyAfterInboundGroupCot(CotEvent event, boolean notifyUser) {
        if (event == null) {
            return;
        }
        if (!GeoChatContactListHelper.cotHasContactHierarchy(event)
                && !GeoChatContactListHelper.isContactListUpdateMessage(remarksText(event))) {
            return;
        }
        final String conversationId = GeoChatContactListHelper.extractConversationId(event);
        if (conversationId == null || conversationId.isEmpty()) {
            return;
        }
        final CotEvent cot = event;
        Runnable work = () -> {
            if (!applyFromHierarchyPaths(cot, notifyUser)) {
                applyFromDatabase(conversationId, notifyUser);
            }
        };
        MapView mv = MapView.getMapView();
        if (mv != null) {
            mv.post(work);
        } else {
            work.run();
        }
    }

    private static String remarksText(CotEvent event) {
        if (event.getDetail() == null) {
            return null;
        }
        com.atakmap.coremap.cot.event.CotDetail remarks =
                event.getDetail().getFirstChildByName(0, "remarks");
        return remarks != null ? remarks.getInnerText() : null;
    }

    /**
     * Preferred path: rebuild from incoming CoT hierarchy so we do not depend on DB timing.
     */
    private static boolean applyFromHierarchyPaths(CotEvent event, boolean notifyUser) {
        String conversationId = GeoChatContactListHelper.extractConversationId(event);
        Bundle paths = GeoChatContactListHelper.hierarchyDetailToPathsBundle(event);
        if (conversationId == null || paths == null || paths.isEmpty()) {
            return false;
        }
        try {
            Contacts contacts = Contacts.getInstance();
            GroupContact userGroups = resolveUserGroups(contacts);
            if (userGroups == null) {
                return false;
            }

            Bundle groupNode = findPathNode(paths, conversationId);
            if (groupNode == null) {
                Log.w(TAG, "hierarchy paths missing node for convo=" + conversationId);
                return false;
            }

            // RF chatroom can be generic ("RF"/"All Chat Rooms"). The actual group label
            // lives on the hierarchy node; prefer that so UI shows the created group name.
            String groupName = groupNode.getString("name", conversationId);
            if (groupName == null || groupName.trim().isEmpty()) {
                groupName = GeoChatContactListHelper.extractConversationName(event);
            }
            if (groupName == null || groupName.trim().isEmpty()) {
                groupName = conversationId;
            }

            GroupContact group = buildGroupFromPath(groupNode, conversationId, groupName, contacts);
            Contact existing = contacts.getContactByUuid(conversationId);
            if (existing instanceof GroupContact) {
                GroupContact gc = (GroupContact) existing;
                // ATAK may create inbound groups as non-user-created (hidden/locked).
                // Replace with an explicit user-created GroupContact so it is visible.
                if (!gc.isUserCreated()) {
                    replaceGroupContact(contacts, gc, group, userGroups);
                    Log.i(TAG, "Replaced hidden inbound group from hierarchy: " + groupName
                            + " (" + conversationId + ")");
                } else {
                    gc.setHideIfEmpty(false);
                    gc.getExtras().putBoolean("containsSelf", true);
                    forceMountUnderUserGroups(gc, userGroups, contacts);
                    Log.i(TAG, "Refreshed inbound group from hierarchy: " + groupName
                            + " (" + conversationId + ")");
                }
                if (notifyUser) {
                    notifyRfGroupSynced(groupName, conversationId);
                }
            } else {
                forceMountUnderUserGroups(group, userGroups, contacts);
                Log.i(TAG, "Mounted inbound group from hierarchy: " + groupName
                        + " (" + conversationId + ") members=" + group.getAllContacts(false).size());
                if (notifyUser) {
                    notifyRfGroupSynced(groupName, conversationId);
                }
            }
            refreshContactsUi(conversationId);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "applyFromHierarchyPaths failed convo=" + conversationId, e);
            return false;
        }
    }

    private static Bundle findPathNode(Bundle paths, String targetUid) {
        if (paths == null || targetUid == null) {
            return null;
        }
        String uid = paths.getString("uid");
        if (targetUid.equals(uid)) {
            return paths;
        }
        Bundle direct = paths.getBundle(targetUid);
        if (direct != null) {
            return direct;
        }
        for (String key : paths.keySet()) {
            Object child = paths.get(key);
            if (child instanceof Bundle) {
                Bundle found = findPathNode((Bundle) child, targetUid);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static GroupContact buildGroupFromPath(Bundle node, String uid, String name,
                                                   Contacts contacts) {
        List<Contact> members = new ArrayList<>();
        for (String key : node.keySet()) {
            Object child = node.get(key);
            if (!(child instanceof Bundle)) {
                continue;
            }
            Bundle childBundle = (Bundle) child;
            String type = childBundle.getString("type");
            if ("group".equals(type)) {
                String childUid = childBundle.getString("uid", key);
                String childName = childBundle.getString("name", childUid);
                members.add(buildGroupFromPath(childBundle, childUid, childName, contacts));
            } else {
                String memberUid = childBundle.getString("uid", key);
                if (memberUid == null || memberUid.isEmpty()) {
                    continue;
                }
                if (isLocalOrSystemMember(memberUid)) {
                    continue;
                }
                String memberName = childBundle.getString("name", memberUid);
                memberName = normalizeMemberDisplayName(memberUid, memberName);
                Contact member = contacts.getContactByUuid(memberUid);
                if (member == null) {
                    member = new IndividualContact(memberName, memberUid);
                }
                member = ensureMemberRegistered(contacts, member, memberUid, memberName);
                members.add(member);
            }
        }
        // Force userCreated=true so ATAK does not hide as "empty/locked" non-local group.
        GroupContact gc = new GroupContact(uid, name, members, true);
        gc.setHideIfEmpty(false);
        gc.getExtras().putBoolean("containsSelf", true);
        return gc;
    }

    /**
     * Fallback path when hierarchy parse misses: restore from ChatDatabase row.
     */
    private static void applyFromDatabase(String conversationId, boolean notifyUser) {
        try {
            Contacts contacts = Contacts.getInstance();
            GroupContact userGroups = resolveUserGroups(contacts);
            if (userGroups == null) {
                Log.w(TAG, "UserGroups missing; cannot mount " + conversationId);
                return;
            }

            Contact existing = contacts.getContactByUuid(conversationId);
            if (existing instanceof GroupContact) {
                GroupContact gc = (GroupContact) existing;
                if (!gc.isUserCreated()) {
                    GroupContact replacement = new GroupContact(
                            gc.getUID(),
                            gc.getName(),
                            gc.getAllContacts(false),
                            true);
                    replacement.setHideIfEmpty(false);
                    replacement.getExtras().putBoolean("containsSelf", true);
                    replaceGroupContact(contacts, gc, replacement, userGroups);
                    Log.i(TAG, "Replaced hidden inbound group in Contacts: " + replacement.getName()
                            + " (" + conversationId + ")");
                    if (notifyUser) {
                        notifyRfGroupSynced(replacement.getName(), conversationId);
                    }
                } else {
                    gc.setHideIfEmpty(false);
                    gc.getExtras().putBoolean("containsSelf", true);
                    forceMountUnderUserGroups(gc, userGroups, contacts);
                    Log.i(TAG, "Refreshed inbound group in Contacts: " + gc.getName()
                            + " (" + conversationId + ")");
                    if (notifyUser) {
                        notifyRfGroupSynced(gc.getName(), conversationId);
                    }
                }
                refreshContactsUi(conversationId);
                return;
            }

            MapView mapView = MapView.getMapView();
            if (mapView == null) {
                return;
            }
            List<String> info = ChatDatabase.getInstance(mapView.getContext()).getGroupInfo(conversationId);
            if (info == null || info.size() < 4) {
                Log.w(TAG, "No ChatDatabase group row for " + conversationId
                        + " (size=" + (info != null ? info.size() : 0) + ")");
                return;
            }

            String groupName = info.get(0);
            String childUidsCsv = info.get(1);
            List<Contact> members = parseMembers(contacts, childUidsCsv, userGroups.getUID());
            GroupContact group = new GroupContact(conversationId, groupName, members, true);
            group.setHideIfEmpty(false);
            group.getExtras().putBoolean("containsSelf", true);
            forceMountUnderUserGroups(group, userGroups, contacts);
            Log.i(TAG, "Mounted inbound group in Contacts: " + groupName
                    + " (" + conversationId + ") members=" + members.size()
                    + " parent=" + userGroups.getName());
            if (notifyUser) {
                notifyRfGroupSynced(groupName, conversationId);
            }
            refreshContactsUi(conversationId);
        } catch (Exception e) {
            Log.e(TAG, "applyFromDatabase failed for " + conversationId, e);
        }
    }

    private static GroupContact resolveUserGroups(Contacts contacts) {
        Contact ug = contacts.getContactByUuid(Contacts.USER_GROUPS);
        if (ug instanceof GroupContact) {
            return (GroupContact) ug;
        }
        return null;
    }

    private static List<Contact> parseMembers(Contacts contacts, String csv,
                                              String userGroupsUid) {
        List<Contact> members = new ArrayList<>();
        if (csv == null || csv.isEmpty()) {
            return members;
        }
        String[] parts = csv.split(",");
        for (String raw : parts) {
            String uid = raw != null ? raw.trim() : "";
            if (uid.isEmpty() || uid.equals(userGroupsUid)) {
                continue;
            }
            if (isLocalOrSystemMember(uid)) {
                continue;
            }
            Contact member = contacts.getContactByUuid(uid);
            String name = null;
            if (member == null) {
                name = normalizeMemberDisplayName(uid, uid);
                member = new IndividualContact(name, uid);
                member = ensureMemberRegistered(contacts, member, uid, name);
            } else {
                name = normalizeMemberDisplayName(uid, member.getName());
                member = ensureMemberRegistered(contacts, member, uid, name);
            }
            members.add(member);
        }
        return members;
    }

    /**
     * Ensures the group sits directly under User Groups and remains visible.
     */
    private static void forceMountUnderUserGroups(GroupContact gc, GroupContact userGroups,
                                                  Contacts contacts) {
        if (gc == null || userGroups == null || contacts == null) {
            return;
        }
        Contact priorParent = contacts.getContactByUuid(gc.getParentUID());
        if (priorParent instanceof GroupContact && priorParent != userGroups) {
            ((GroupContact) priorParent).removeContact(gc);
        }
        gc.setParentUID(userGroups.getUID());
        gc.setHideIfEmpty(false);
        gc.getExtras().putBoolean("containsSelf", true);
        contacts.addContact(userGroups, gc);
        gc.updateLocks();
    }

    private static void replaceGroupContact(Contacts contacts,
                                            GroupContact existing,
                                            GroupContact replacement,
                                            GroupContact userGroups) {
        if (contacts == null || existing == null || replacement == null || userGroups == null) {
            return;
        }
        try {
            contacts.removeContact(existing);
        } catch (Exception e) {
            Log.w(TAG, "remove existing group failed " + existing.getUID(), e);
        }
        forceMountUnderUserGroups(replacement, userGroups, contacts);
    }

    private static void refreshContactsUi(String conversationId) {
        AtakBroadcast.getInstance().sendBroadcast(
                new Intent(ContactPresenceDropdown.REFRESH_LIST));
        Log.i(TAG, "Broadcasted UI refresh for convo=" + conversationId);
    }

    private static boolean isLocalOrSystemMember(String uidRaw) {
        if (uidRaw == null) {
            return false;
        }
        String uid = uidRaw.trim();
        if (uid.isEmpty() || Contacts.USER_GROUPS.equals(uid)) {
            return true;
        }
        String local = null;
        try {
            local = MapView.getDeviceUid();
        } catch (Exception ignored) {
        }
        return local != null && !local.isEmpty() && local.equals(uid);
    }

    private static String normalizeMemberDisplayName(String uidRaw, String preferredNameRaw) {
        String uid = uidRaw != null ? uidRaw.trim() : "";
        String preferred = preferredNameRaw != null ? preferredNameRaw.trim() : "";
        if (!preferred.isEmpty() && !preferred.equalsIgnoreCase(uid)) {
            return preferred;
        }
        if (uid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
            return uid.substring(ANDROID_UID_PREFIX.length());
        }
        return uid.isEmpty() ? preferred : uid;
    }

    private static Contact ensureMemberRegistered(Contacts contacts,
                                                  Contact member,
                                                  String uid,
                                                  String name) {
        if (uid == null || uid.trim().isEmpty()) {
            return member;
        }
        String cleanUid = uid.trim();
        try {
            if (cleanUid.toUpperCase(Locale.US).startsWith(ANDROID_UID_PREFIX)) {
                String cs = name != null && !name.trim().isEmpty()
                        ? name.trim()
                        : cleanUid.substring(ANDROID_UID_PREFIX.length());
                String ensured = ChatBridge.ensurePluginChatContactExactUid(cs, cleanUid);
                Contact exact = contacts.getContactByUuid(ensured);
                if (exact != null) {
                    return exact;
                }
            }
            Contact existing = contacts.getContactByUuid(cleanUid);
            if (existing != null) {
                return existing;
            }
            if (member != null) {
                contacts.addContact(member);
                Contact post = contacts.getContactByUuid(cleanUid);
                if (post != null) {
                    return post;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureMemberRegistered failed uid=" + cleanUid, e);
        }
        return member;
    }

    private static void notifyRfGroupSynced(String groupName, String conversationId) {
        // Popups and system notifications suppressed — chat appears only in the inline
        // channel window inside the plugin panel.
        Log.i(TAG, "RF group synced: convo=" + conversationId + " name=" + groupName);
    }
}
