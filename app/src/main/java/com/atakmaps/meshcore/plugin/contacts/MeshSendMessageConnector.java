package com.atakmaps.meshcore.plugin.contacts;

import com.atakmap.android.contact.Connector;
import com.atakmaps.meshcore.plugin.R;
import com.atakmaps.meshcore.plugin.chat.ChatBridge;

/**
 * Contact-card action to open standard GeoChat message UI for Mesh contacts.
 */
public final class MeshSendMessageConnector extends Connector {

    public static final String CONNECTOR_TYPE = "connector.uvpro.mesh_send_message";
    private static final String PACKAGE = "com.atakmaps.meshcore.plugin";

    @Override
    public String getConnectionString() {
        return ChatBridge.ACTION_PLUGIN_CONTACT_GEOCHAT_SEND;
    }

    @Override
    public String getConnectionType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public String getConnectionLabel() {
        return "Send Message";
    }

    @Override
    public String getIconUri() {
        return "android.resource://" + PACKAGE + "/" + R.drawable.ic_uvpro;
    }
}
