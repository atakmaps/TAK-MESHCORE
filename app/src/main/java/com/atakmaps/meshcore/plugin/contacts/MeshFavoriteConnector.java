package com.atakmaps.meshcore.plugin.contacts;

import com.atakmap.android.contact.Connector;
import com.atakmaps.meshcore.plugin.R;

/**
 * Contact-card action to favorite a MeshCore-discovered contact.
 */
public final class MeshFavoriteConnector extends Connector {

    public static final String CONNECTOR_TYPE = "connector.uvpro.mesh_favorite";
    public static final String CONTACT_ACTION =
            "com.atakmaps.meshcore.plugin.action.MESH_FAVORITE_CONTACT";
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
        return "Favorite";
    }

    @Override
    public String getIconUri() {
        return "android.resource://" + PACKAGE + "/" + R.drawable.ic_uvpro;
    }
}
