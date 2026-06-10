package com.atakmaps.meshcore.plugin.contacts;

import android.content.Context;

import com.atakmap.android.action.MapAction;
import com.atakmap.android.maps.MapDataRef;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.assets.MapAssets;
import com.atakmap.android.menu.MapMenuButtonWidget;
import com.atakmap.android.menu.MapMenuFactory;
import com.atakmap.android.menu.MapMenuReceiver;
import com.atakmap.android.menu.MapMenuWidget;
import com.atakmap.android.menu.MenuMapAdapter;
import com.atakmap.android.menu.MenuResourceFactory;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.WidgetIcon;
import com.atakmaps.meshcore.plugin.MeshCoreContactHandler;

import java.io.IOException;

import gov.tak.api.widgets.IMapMenuButtonWidget;
import gov.tak.api.widgets.IMapMenuWidget;
import gov.tak.api.widgets.IMapWidget;

/**
 * Extends ATAK's stock radial menu for ping-capable contacts by adding Ping to the
 * contact connector submenu only. Does not replace the top-level friendly menu.
 */
public final class ContactRadialMenuFactory implements MapMenuFactory {

    static final String META_PING_CAPABLE = "meshcorePingCapable";

    private static final String CONTACT_ICON = "icons/contact.png";

    private final Context pluginContext;
    private final MenuResourceFactory resourceFactory;
    private final String pingActionResource;
    private volatile boolean registered;

    public ContactRadialMenuFactory(Context pluginContext, String pingActionResource) {
        this.pluginContext = pluginContext;
        this.pingActionResource = pingActionResource;
        MapView mapView = MapView.getMapView();
        Context appContext = mapView.getContext();
        MapAssets mapAssets = new MapAssets(appContext);
        MenuMapAdapter adapter = new MenuMapAdapter();
        try {
            adapter.loadMenuFilters(mapAssets, "filters/menu_filters.xml");
        } catch (IOException ignored) {
        }
        this.resourceFactory = new MenuResourceFactory(
                mapView, mapView.getMapData(), mapAssets, adapter);
    }

    public void register() {
        if (registered) {
            return;
        }
        MapMenuReceiver receiver = MapMenuReceiver.getInstance();
        if (receiver != null && receiver.registerMapMenuFactory(this)) {
            registered = true;
        }
    }

    public void unregister() {
        if (!registered) {
            return;
        }
        MapMenuReceiver receiver = MapMenuReceiver.getInstance();
        if (receiver != null && receiver.unregisterMapMenuFactory(this)) {
            registered = false;
        }
    }

    @Override
    public MapMenuWidget create(MapItem mapItem) {
        if (mapItem == null || !isPingCapable(mapItem)) {
            return null;
        }
        ContactRadialMenuUtil.clearBrokenCustomMenu(mapItem);
        MapMenuWidget menuWidget = resourceFactory.create(mapItem);
        if (menuWidget == null) {
            return null;
        }
        injectPingIntoContactSubmenu(menuWidget, mapItem);
        return menuWidget;
    }

    static boolean isPingCapable(MapItem item) {
        if (item == null) {
            return false;
        }
        if (item.getMetaBoolean(META_PING_CAPABLE, false)) {
            return true;
        }
        String uid = item.getUID();
        if (uid == null) {
            return false;
        }
        if (uid.startsWith("MESHCORE-RPTR-")) {
            return false;
        }
        if (!uid.startsWith("MESHCORE-NODE-") && !uid.startsWith("ANDROID-")) {
            return false;
        }
        com.atakmap.android.contact.Contact c =
                com.atakmap.android.contact.Contacts.getInstance().getContactByUuid(uid);
        if (c instanceof com.atakmap.android.contact.IndividualContact) {
            return !MeshCoreContactHandler.resolvePingTargetCallsign(
                    (com.atakmap.android.contact.IndividualContact) c).isEmpty();
        }
        return uid.startsWith("MESHCORE-NODE-");
    }

    private void injectPingIntoContactSubmenu(MapMenuWidget menuWidget, MapItem mapItem) {
        IMapMenuWidget contactSubmenu = findContactSubmenu(menuWidget);
        if (contactSubmenu == null) {
            return;
        }
        MapMenuButtonWidget pingButton = buildPingButton(mapItem);
        if (pingButton == null) {
            return;
        }
        float span = 0f;
        float width = 0f;
        int count = contactSubmenu.getChildWidgetCount();
        for (int i = 0; i < count; i++) {
            IMapWidget child = contactSubmenu.getChildWidgetAt(i);
            if (child instanceof MapMenuButtonWidget) {
                MapMenuButtonWidget b = (MapMenuButtonWidget) child;
                span += b.getButtonSpan();
                width += b.getButtonWidth();
            }
        }
        if (count > 0) {
            pingButton.setLayoutWeight(span / count);
            pingButton.setButtonSize(span / count, width / count);
        }
        int insertAt = findGeoChatIndex(contactSubmenu) + 1;
        if (insertAt < 0 || insertAt > contactSubmenu.getChildWidgetCount()) {
            insertAt = contactSubmenu.getChildWidgetCount();
        }
        contactSubmenu.addChildWidgetAt(insertAt, pingButton);
    }

    private int findGeoChatIndex(IMapMenuWidget submenu) {
        for (int i = 0; i < submenu.getChildWidgetCount(); i++) {
            IMapWidget child = submenu.getChildWidgetAt(i);
            if (!(child instanceof MapMenuButtonWidget)) {
                continue;
            }
            WidgetIcon icon = ((MapMenuButtonWidget) child).getIcon();
            if (icon == null) {
                continue;
            }
            MapDataRef ref = icon.getIconRef(0);
            if (ref != null && ref.toUri().contains("chatsmall")) {
                return i;
            }
        }
        return -1;
    }

    private IMapMenuWidget findContactSubmenu(MapMenuWidget menuWidget) {
        for (MapWidget child : menuWidget.getChildWidgets()) {
            if (!(child instanceof MapMenuButtonWidget)) {
                continue;
            }
            MapMenuButtonWidget button = (MapMenuButtonWidget) child;
            IMapMenuWidget submenu = button.getSubmenu();
            if (submenu == null) {
                continue;
            }
            WidgetIcon icon = button.getIcon();
            if (icon == null) {
                continue;
            }
            MapDataRef ref = icon.getIconRef(0);
            if (ref != null && ref.toUri().contains(CONTACT_ICON)) {
                return submenu;
            }
        }
        return null;
    }

    private MapMenuButtonWidget buildPingButton(MapItem mapItem) {
        if (pingActionResource == null || pingActionResource.isEmpty()) {
            return null;
        }
        final MapAction pingAction = resourceFactory.resolveAction(pingActionResource);
        if (pingAction == null) {
            return null;
        }
        MapView mapView = MapView.getMapView();
        if (mapView == null) {
            return null;
        }
        WidgetIcon pingIcon = buildPingIcon();
        if (pingIcon == null) {
            android.util.Log.w("MeshCore.RadialMenu", "Ping icon unavailable — omitting radial button");
            return null;
        }
        MapMenuButtonWidget button = new MapMenuButtonWidget(mapView.getContext());
        button.setIcon(pingIcon);
        button.setOnButtonClickHandler(new IMapMenuButtonWidget.OnButtonClickHandler() {
            @Override
            public boolean isSupported(Object target) {
                return target instanceof MapItem;
            }

            @Override
            public void performAction(Object target) {
                if (target instanceof MapItem) {
                    pingAction.performAction(mapView, (MapItem) target);
                }
            }
        });
        return button;
    }

    private WidgetIcon buildPingIcon() {
        String iconUri = ContactConnectorIcons.getRadialPingIconDataUri(pluginContext);
        if (iconUri == null || iconUri.isEmpty()) {
            return null;
        }
        MapDataRef ref = MapDataRef.parseUri(iconUri);
        if (ref == null) {
            return null;
        }
        return new WidgetIcon.Builder()
                .setImageRef(0, ref)
                .setAnchor(16, 16)
                .setSize(32, 32)
                .build();
    }
}
