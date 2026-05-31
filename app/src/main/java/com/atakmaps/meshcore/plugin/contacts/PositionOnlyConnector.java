package com.atakmaps.meshcore.plugin.contacts;

import com.atakmap.android.contact.Connector;
import com.atakmaps.meshcore.plugin.R;

/**
 * Contact action for peers visible on the map but not reachable for GeoChat.
 * Uses a red-X icon instead of the native chat bubble or plugin plug icon.
 */
public final class PositionOnlyConnector extends Connector {

    public static final String CONNECTOR_TYPE = "connector.uvpro.position_only";
    public static final String CONTACT_ACTION =
            "com.atakmaps.meshcore.plugin.action.POSITION_ONLY_CONTACT";
    private static final String PACKAGE = "com.atakmaps.meshcore.plugin";

    public PositionOnlyConnector() {
    }

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
        return "Position only";
    }

    @Override
    public String getIconUri() {
        String cached = ContactConnectorIcons.getPositionOnlyIconUri(null);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return "android.resource://" + PACKAGE + "/" + R.drawable.ic_contact_position_only;
    }
}
