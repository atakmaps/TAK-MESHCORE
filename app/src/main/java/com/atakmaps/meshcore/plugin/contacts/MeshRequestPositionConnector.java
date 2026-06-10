package com.atakmaps.meshcore.plugin.contacts;

import com.atakmap.android.contact.Connector;
import com.atakmaps.meshcore.plugin.R;

/**
 * Contact-card action to request a position update from a mesh RF peer.
 */
public final class MeshRequestPositionConnector extends Connector {

    public static final String CONNECTOR_TYPE = "connector.meshcore.mesh_request_position";
    public static final String CONTACT_ACTION =
            "com.atakmaps.meshcore.plugin.action.MESH_REQUEST_POSITION";
    private static final String PACKAGE = "com.atakmaps.meshcore.plugin";

    @Override
    public String getConnectionString() {
        return CONTACT_ACTION;
    }

    @Override
    public String getConnectionType() {
        return CONNECTOR_TYPE;
    }

    @Override
    public String getConnectionLabel() {
        return "Ping";
    }

    @Override
    public String getIconUri() {
        return "android.resource://" + PACKAGE + "/" + R.drawable.ic_meshcore;
    }
}
