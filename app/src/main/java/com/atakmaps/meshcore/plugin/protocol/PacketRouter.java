package com.atakmaps.meshcore.plugin.protocol;

import android.util.Log;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.contact.IpConnector;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;

import com.atakmaps.meshcore.plugin.ax25.Ax25Frame;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;
import com.atakmaps.meshcore.plugin.cot.CotBridge;
import com.atakmaps.meshcore.plugin.util.CallsignUtil;
import com.atakmaps.meshcore.plugin.contacts.ContactTracker;
import com.atakmaps.meshcore.plugin.contacts.MeshSendMessageConnector;
import com.atakmaps.meshcore.plugin.crypto.EncryptionManager;
import com.atakmaps.meshcore.plugin.protocol.PacketFragmenter;
import com.atakmaps.meshcore.plugin.MeshCoreContactHandler;

/**
 * Routes incoming packets to the appropriate handler based on their type.
 *
 * Incoming data from the radio arrives as raw AX.25 frames. The PacketRouter:
 * 1. Decodes the AX.25 frame to extract callsign and info field
 * 2. Routes MeshCore custom packets to the appropriate handler (CoT bridge, chat bridge, contact tracker)
 *
 * For MeshCore custom packets (from other MeshCore plugins):
 *   - TYPE_GPS → ContactTracker + CotBridge
 *   - TYPE_CHAT → ChatBridge
 *   - TYPE_COT → CotBridge
 *   - TYPE_PING → ContactTracker
 */
public class PacketRouter {

    private static final String TAG = "MeshCore.Router";

    private final CotBridge cotBridge;
    private final ChatBridge chatBridge;
    private final ContactTracker contactTracker;
    private final PacketFragmenter.Reassembler reassembler;
    private final PingReplyScheduler pingReplyScheduler;
    private EncryptionManager encryptionManager;

    /** Listener for packet count updates */
    private PacketCountListener packetCountListener;

    public interface PacketCountListener {
        void onPacketReceived();

        /** One AX.25 frame successfully sent over KISS (beacon, chat, CoT, ping, etc.). */
        void onPacketTransmitted();
    }

    public PacketRouter(CotBridge cotBridge, ChatBridge chatBridge,
                        ContactTracker contactTracker) {
        this.cotBridge = cotBridge;
        this.chatBridge = chatBridge;
        this.contactTracker = contactTracker;
        this.reassembler = new PacketFragmenter.Reassembler();
        this.pingReplyScheduler = new PingReplyScheduler(cotBridge);
    }

    public void setPacketCountListener(PacketCountListener listener) {
        this.packetCountListener = listener;
    }

    /** Called by {@link com.atakmaps.meshcore.plugin.bluetooth.BtConnectionManager} after a successful KISS TX. */
    public void notifyPacketTransmitted() {
        if (packetCountListener != null) {
            packetCountListener.onPacketTransmitted();
        }
    }

    public void setEncryptionManager(EncryptionManager em) {
        this.encryptionManager = em;
    }

    /**
     * Inbound native MeshCore DM ({@code CMD_SEND_TXT_MSG} path): inject the plain text into ATAK
     * GeoChat, threaded by the sender's pubkey prefix.
     */
    public void routeNativeMeshDm(String senderPubKeyPrefixHex, String text) {
        if (chatBridge == null) {
            return;
        }
        chatBridge.injectInboundMeshDm(senderPubKeyPrefixHex, text);
    }

    /**
     * Route an incoming AX.25 frame from the radio.
     * Called by BtConnectionManager on the read thread.
     */
    public void routeIncoming(byte[] ax25Data) {
        Ax25Frame frame = Ax25Frame.decode(ax25Data);
        if (frame == null) {
            Log.w(TAG, "Failed to decode AX.25 frame");
            return;
        }

        String srcCall = frame.getSrcCallsign();
        int srcSsid = frame.getSrcSsid();
        String destCall = frame.getDestCallsign();
        String info = frame.getInfoString();
        byte[] infoBytes = frame.getInfoField();

        Log.d(TAG, "Received from " + srcCall + "-" + srcSsid +
                " → " + destCall + ": " + info.length() + " bytes");

        // Notify listener of received packet
        if (packetCountListener != null) {
            packetCountListener.onPacketReceived();
        }

        // Check if this is an MeshCore custom packet
        if ("OPENRL".equals(destCall)) {
            RfTxArbitrator.get().markInboundOpenRl();
            byte[] infoField = frame.getInfoField();

            // Decrypt if encryption is enabled
            if (encryptionManager != null && encryptionManager.isEnabled()) {
                byte[] decrypted = encryptionManager.decrypt(infoField);
                if (decrypted != null) {
                    infoField = java.util.Arrays.copyOf(decrypted, decrypted.length);
                    java.util.Arrays.fill(decrypted, (byte) 0);
                }
                // If decryption returns null, use raw (unencrypted packet)
            }

            routeMeshCorePacket(srcCall, srcSsid, infoField);
            return;
        }

        // MeshCore-only build: ignore non-OPENRL AX.25 frames.
        Log.d(TAG, "Ignoring non-MeshCore frame dest=" + destCall
                + " src=" + srcCall + "-" + srcSsid);
    }

    /**
     * Route an MeshCore custom packet.
     */
    private void routeMeshCorePacket(String callsign, int ssid, byte[] data) {
        MeshCorePacket packet = MeshCorePacket.decode(data);
        if (packet == null) {
            Log.w(TAG, "Failed to decode MeshCore packet");
            return;
        }

        switch (packet.getType()) {
            case MeshCorePacket.TYPE_GPS:
                MeshCorePacket.GpsData gps =
                        MeshCorePacket.decodeGpsPayload(packet.getPayload());
                if (gps != null) {
                    Log.d(TAG, "GPS from " + gps.callsign +
                            ": " + gps.latitude + ", " + gps.longitude);
                    contactTracker.updateContact(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course, gps.battery);

                    final String normalized = gps.callsign.trim().toUpperCase();
                    final String uid = "ANDROID-" + normalized;

                    // Position CoT first so map marker + __group (sender team) exist before we
                    // register/link the IndividualContact (contacts list color follows MapItem).
                    cotBridge.injectPositionCot(gps.callsign, gps.latitude,
                            gps.longitude, gps.altitude, gps.speed,
                            gps.course,
                            gps.teamColor);

                    cotBridge.registerBtechContactUid(uid);
                    cotBridge.registerBtechContactId(normalized, uid);
                    String radioTrunc = CallsignUtil.toRadioCallsign(normalized);
                    if (radioTrunc != null && !radioTrunc.isEmpty()
                            && !radioTrunc.equalsIgnoreCase(normalized)) {
                        cotBridge.registerBtechContactId(radioTrunc, uid);
                    }

                    MapView mv = MapView.getMapView();
                    if (mv != null) {
                        mv.post(() -> linkRadioIndividualContactToMapMarker(
                                normalized, uid, 0));
                    }

                    // Notify ChatBridge so any pending/failed messages for this peer are sent.
                    chatBridge.onPeerActivity(gps.callsign);
                    MapView pingMv = MapView.getMapView();
                    if (pingMv != null) {
                        PingReplyNotifier.maybeNotifyPingReply(
                                pingMv.getContext(), gps.callsign);
                    }
                }
                break;

            case MeshCorePacket.TYPE_CHAT:
                routeChatPacket(packet.getPayload());
                break;

            case MeshCorePacket.TYPE_COT:
                cotBridge.injectCompressedCot(packet.getPayload());
                break;

            case MeshCorePacket.TYPE_PING:
                byte[] pingBytes = packet.getPayload();
                String pingCall = new String(pingBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
                java.util.Arrays.fill(pingBytes, (byte) 0);
                Log.d(TAG, "Ping from: " + pingCall);
                MapView pingMv = MapView.getMapView();
                if (pingMv != null) {
                    PingReplyNotifier.notifyPingReceived(pingMv.getContext(), pingCall);
                }
                contactTracker.handlePing(pingCall);
                chatBridge.onPeerActivity(callsign);
                if (!callsign.equalsIgnoreCase(pingCall)) {
                    chatBridge.onPeerActivity(pingCall);
                }
                schedulePingReply();
                break;

            case MeshCorePacket.TYPE_NET_SLOT_CONFIG:
                MapView slotMv = MapView.getMapView();
                if (slotMv != null) {
                    NetSlotConfig.applyFromNetwork(
                            slotMv.getContext(), packet.getPayload(), callsign);
                }
                break;

            case MeshCorePacket.TYPE_ACK:
                MeshCorePacket.AckPayload ack =
                        MeshCorePacket.decodeAckPayload(packet.getPayload());
                if (ack != null) {
                    chatBridge.handleIncomingRadioChatAck(
                            ack.wireMessageId, ack.kind);
                }
                break;

            case MeshCorePacket.TYPE_COT_FRAGMENT:
                byte[] reassembled = reassembler.addFragment(
                        packet.getPayload());
                if (reassembled != null) {
                    Log.d(TAG, "Fragment reassembly complete: "
                            + reassembled.length + " bytes");
                    cotBridge.injectCompressedCot(reassembled);
                }
                break;

            default:
                Log.w(TAG, "Unknown MeshCore packet type: " + packet.getType());
        }
    }

    /**
     * Decode and route a chat message from MeshCore packet payload.
     */
    private void routeChatPacket(byte[] payload) {
        if (payload == null || payload.length < 16) return;

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(payload);
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);

        byte[] senderBytes = new byte[6];
        byte[] roomBytes = new byte[6];
        buf.get(senderBytes);
        buf.get(roomBytes);
        int messageId = buf.getInt();

        byte[] msgBytes = new byte[buf.remaining()];
        buf.get(msgBytes);

        String sender = new String(senderBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(senderBytes, (byte) 0);
        String room = new String(roomBytes, java.nio.charset.StandardCharsets.US_ASCII).trim();
        java.util.Arrays.fill(roomBytes, (byte) 0);
        String message = new String(msgBytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.Arrays.fill(msgBytes, (byte) 0);

        Log.d(TAG, "Chat mid=" + messageId + " from " + sender + " [" + room + "] len=" + message.length());
        boolean accepted = chatBridge.injectRadioMessage(sender, room, message, messageId);
        if (accepted) {
            chatBridge.sendRadioChatAck(messageId, MeshCorePacket.ACK_KIND_DELIVERED);
        } else {
            Log.d(TAG, "Skipping DELIVERED ACK for non-local chat mid=" + messageId
                    + " room=" + room + " sender=" + sender);
        }
    }

    /**
     * Associate {@link IndividualContact} with the CoT-driven map marker ({@link MapItem}) so
     * Contacts UI uses the peer's team tint from SA (matches native Wi‑Fi / server contacts).
     * Retries briefly if CoT processing has not yet created the marker.
     */
    private void linkRadioIndividualContactToMapMarker(final String normalized,
                                                       final String uid,
                                                       final int attempt) {
        try {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            MapItem item = mv.getRootGroup().deepFindUID(uid);

            Contacts contacts = Contacts.getInstance();
            Contact existing = contacts.getContactByUuid(uid);
            if (!(existing instanceof IndividualContact) && normalized != null
                    && !normalized.isEmpty()) {
                Contact byCallsign = contacts.getFirstContactWithCallsign(normalized);
                if (byCallsign instanceof IndividualContact) {
                    existing = byCallsign;
                }
            }

            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;
                // Strip any stale PluginConnector left by an older build.
                try { ic.removeConnector(com.atakmap.android.contact.PluginConnector.CONNECTOR_TYPE); } catch (Exception ignored) {}
                if (ic.getConnector(MeshSendMessageConnector.CONNECTOR_TYPE) == null) {
                    ic.addConnector(new MeshSendMessageConnector());
                }
                if (ic.getConnector(com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE) == null) {
                    ic.addConnector(new com.atakmap.android.chat.GeoChatConnector(
                            MeshCoreContactHandler.buildNativeConnectorSeed(normalized)));
                }
                if (ic.getConnector(IpConnector.CONNECTOR_TYPE) == null) {
                    ic.addConnector(new IpConnector((String) null));
                }
                try {
                    com.atakmap.android.preference.AtakPreferences prefs =
                            new com.atakmap.android.preference.AtakPreferences(mv.getContext());
                    prefs.set("contact.connector.default." + ic.getUID(),
                            com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);
                } catch (Exception ignored) {
                }
                if (item != null) {
                    item.setMetaBoolean("sendable", true);
                    item.setMetaString("endpoint", ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
                    ic.setMapItem(item);
                    ic.dispatchChangeEvent();
                    return;
                }
                if (attempt < 12) {
                    mv.postDelayed(() -> linkRadioIndividualContactToMapMarker(
                            normalized, uid, attempt + 1), 50L);
                    return;
                }
                ic.dispatchChangeEvent();
                return;
            }

            if (item == null && attempt < 12) {
                mv.postDelayed(() -> linkRadioIndividualContactToMapMarker(
                        normalized, uid, attempt + 1), 50L);
                return;
            }

            IndividualContact c = new IndividualContact(
                    normalized,
                    uid,
                    item instanceof MapItem ? item : null);

            c.addConnector(new MeshSendMessageConnector());
            c.addConnector(new com.atakmap.android.chat.GeoChatConnector(
                    MeshCoreContactHandler.buildNativeConnectorSeed(normalized)));

            // IpConnector with null sendIntent: makes contact visible in the SEND_LIST
            // (ContactListAdapter hard-filters on IpConnector presence) without hijacking
            // the CoT send path.
            c.addConnector(new IpConnector((String) null));

            com.atakmap.android.preference.AtakPreferences prefs =
                    new com.atakmap.android.preference.AtakPreferences(mv.getContext());
            prefs.set("contact.connector.default." + c.getUID(),
                    com.atakmap.android.chat.GeoChatConnector.CONNECTOR_TYPE);

            contacts.addContact(c);
            if (item != null) {
                item.setMetaBoolean("sendable", true);
                item.setMetaString("endpoint", ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND);
            }
        } catch (Exception e) {
            Log.e(TAG, "linkRadioIndividualContactToMapMarker failed uid=" + uid, e);
        }
    }

    private void schedulePingReply() {
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        pingReplyScheduler.scheduleReply(mv.getContext());
    }
}
