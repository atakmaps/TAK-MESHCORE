package com.atakmaps.meshcore.plugin.chat;

import android.os.Bundle;

import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects ATAK contact-list / group hierarchy GeoChat that must ride as full {@code b-t-f}
 * CoT over RF (not compact {@code TYPE_CHAT}).
 */
public final class GeoChatContactListHelper {

    /** ATAK {@code R.string.chat_text5} — group membership sync line. */
    public static final String UPDATED_CONTACTS_TEXT = "[UPDATED CONTACTS]";

    private GeoChatContactListHelper() {
    }

    public static boolean isContactListUpdateMessage(String message) {
        if (message == null) {
            return false;
        }
        String m = message.trim();
        if (m.isEmpty()) {
            return false;
        }
        return UPDATED_CONTACTS_TEXT.equalsIgnoreCase(m)
                || m.toUpperCase(Locale.US).contains("UPDATED CONTACT");
    }

    public static boolean bundleHasContactHierarchy(Bundle bundle) {
        if (bundle == null) {
            return false;
        }
        Bundle paths = bundle.getBundle("paths");
        return paths != null && !paths.isEmpty();
    }

    public static boolean bundleIsGroupContactSync(Bundle bundle) {
        if (bundle == null) {
            return false;
        }
        if (bundleHasContactHierarchy(bundle)) {
            return true;
        }
        return isContactListUpdateMessage(bundle.getString("message"));
    }

    /**
     * True when this GeoChat CoT carries the {@code hierarchy} tree ATAK uses to build
     * {@link com.atakmap.android.contact.GroupContact} entries.
     */
    public static boolean cotHasContactHierarchy(CotEvent event) {
        if (event == null || !"b-t-f".equals(event.getType())) {
            return false;
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return false;
        }
        CotDetail chat = detail.getFirstChildByName(0, "__chat");
        if (chat == null) {
            chat = detail.getFirstChildByName(0, "chat");
        }
        if (chat == null) {
            return false;
        }
        if (findHierarchyUnderChat(chat) != null) {
            return true;
        }
        String groupOwner = chat.getAttribute("groupOwner");
        if (groupOwner == null) {
            groupOwner = chat.getAttribute("groupowner");
        }
        return "true".equalsIgnoreCase(groupOwner)
                && isContactListUpdateMessage(remarksText(detail));
    }

    public static boolean requiresFullCotRelay(CotEvent event) {
        return cotHasContactHierarchy(event);
    }

    private static CotDetail findHierarchyUnderChat(CotDetail chat) {
        for (int i = 0; i < chat.childCount(); i++) {
            CotDetail child = chat.getChild(i);
            if (child == null) {
                continue;
            }
            String tag = child.getElementName();
            if (tag != null && "hierarchy".equalsIgnoreCase(tag)) {
                return child;
            }
        }
        return null;
    }

    private static String remarksText(CotDetail detail) {
        CotDetail remarks = detail.getFirstChildByName(0, "remarks");
        if (remarks == null) {
            return null;
        }
        return remarks.getInnerText();
    }

    private static CotDetail findChatDetail(CotEvent event) {
        if (event == null) {
            return null;
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return null;
        }
        CotDetail chat = detail.getFirstChildByName(0, "__chat");
        if (chat == null) {
            chat = detail.getFirstChildByName(0, "chat");
        }
        return chat;
    }

    /** CHAT3 {@code __chat} {@code id} → ATAK {@code conversationId}. */
    public static String extractConversationId(CotEvent event) {
        CotDetail chat = findChatDetail(event);
        if (chat == null) {
            return null;
        }
        String id = chat.getAttribute("id");
        return id != null ? id.trim() : null;
    }

    /** {@code __chat} {@code chatroom} → human group name (e.g. {@code news}). */
    public static String extractConversationName(CotEvent event) {
        CotDetail chat = findChatDetail(event);
        if (chat == null) {
            return null;
        }
        String room = chat.getAttribute("chatroom");
        if (room == null || room.trim().isEmpty()) {
            room = chat.getAttribute("chatRoom");
        }
        return room != null ? room.trim() : null;
    }

    /** {@code chatgrp} {@code uid0} is the sender on inbound group/contact-list CoT. */
    public static String extractChatSenderUid(CotEvent event) {
        CotDetail chat = findChatDetail(event);
        if (chat == null) {
            return null;
        }
        CotDetail chatgrp = chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp == null) {
            chatgrp = chat.getFirstChildByName(0, "chatGroup");
        }
        if (chatgrp == null) {
            return null;
        }
        String uid0 = chatgrp.getAttribute("uid0");
        return uid0 != null ? uid0.trim() : null;
    }

    public static String extractChatSenderCallsign(CotEvent event) {
        CotDetail chat = findChatDetail(event);
        if (chat == null) {
            return null;
        }
        String cs = chat.getAttribute("senderCallsign");
        if (cs == null || cs.trim().isEmpty()) {
            cs = chat.getAttribute("sendersCallsign");
        }
        return cs != null ? cs.trim() : null;
    }

    /**
     * Reverse of {@link com.atakmap.android.chat.GeoChatService#parseHierarchy} /
     * {@link com.atakmap.android.contact.Contacts#buildLocalPaths} for inbound full CoT.
     */
    public static Bundle hierarchyDetailToPathsBundle(CotEvent event) {
        CotDetail chat = findChatDetail(event);
        if (chat == null) {
            return null;
        }
        CotDetail hierarchy = findHierarchyUnderChat(chat);
        if (hierarchy == null) {
            return null;
        }
        Bundle paths = new Bundle();
        for (int i = 0; i < hierarchy.childCount(); i++) {
            CotDetail child = hierarchy.getChild(i);
            if (child == null) {
                continue;
            }
            Bundle node = cotHierarchyNodeToPathBundle(child);
            String uid = node.getString("uid");
            if (uid != null && !uid.isEmpty()) {
                paths.putBundle(uid, node);
            }
        }
        return paths.isEmpty() ? null : paths;
    }

    private static Bundle cotHierarchyNodeToPathBundle(CotDetail node) {
        Bundle paths = new Bundle();
        if (node == null) {
            return paths;
        }
        String uid = node.getAttribute("uid");
        String name = node.getAttribute("name");
        String tag = node.getElementName();
        String type = tag != null && "group".equalsIgnoreCase(tag) ? "group" : "contact";
        if (uid != null) {
            paths.putString("uid", uid);
        }
        if (name != null) {
            paths.putString("name", name);
        }
        paths.putString("type", type);
        for (int i = 0; i < node.childCount(); i++) {
            CotDetail child = node.getChild(i);
            if (child == null) {
                continue;
            }
            Bundle childPaths = cotHierarchyNodeToPathBundle(child);
            String childUid = childPaths.getString("uid");
            if (childUid != null && !childUid.isEmpty()) {
                paths.putBundle(childUid, childPaths);
            }
        }
        return paths;
    }

    /**
     * Collect contact UIDs embedded in a group-sync GeoChat (chatgrp + hierarchy tree).
     */
    public static List<String> collectContactUidsFromGroupCot(CotEvent event) {
        Set<String> uids = new HashSet<>();
        if (event == null) {
            return new ArrayList<>();
        }
        CotDetail detail = event.getDetail();
        if (detail == null) {
            return new ArrayList<>();
        }
        CotDetail chat = detail.getFirstChildByName(0, "__chat");
        if (chat == null) {
            chat = detail.getFirstChildByName(0, "chat");
        }
        if (chat == null) {
            return new ArrayList<>();
        }
        CotDetail chatgrp = chat.getFirstChildByName(0, "chatgrp");
        if (chatgrp != null) {
            collectUidAttributes(chatgrp, uids);
        }
        CotDetail hierarchy = findHierarchyUnderChat(chat);
        if (hierarchy != null) {
            collectUidsFromHierarchyDetail(hierarchy, uids);
        }
        return new ArrayList<>(uids);
    }

    private static void collectUidAttributes(CotDetail detail, Set<String> uids) {
        if (detail == null) {
            return;
        }
        com.atakmap.coremap.cot.event.CotAttribute[] attrs = detail.getAttributes();
        if (attrs == null) {
            return;
        }
        for (com.atakmap.coremap.cot.event.CotAttribute attr : attrs) {
            if (attr == null) {
                continue;
            }
            String name = attr.getName();
            if (name != null && name.startsWith("uid")) {
                String val = attr.getValue();
                if (val != null && !val.trim().isEmpty()) {
                    uids.add(val.trim());
                }
            }
        }
    }

    private static void collectUidsFromHierarchyDetail(CotDetail node, Set<String> uids) {
        if (node == null) {
            return;
        }
        String uid = node.getAttribute("uid");
        if (uid != null && !uid.trim().isEmpty()) {
            uids.add(uid.trim());
        }
        for (int i = 0; i < node.childCount(); i++) {
            collectUidsFromHierarchyDetail(node.getChild(i), uids);
        }
    }
}
