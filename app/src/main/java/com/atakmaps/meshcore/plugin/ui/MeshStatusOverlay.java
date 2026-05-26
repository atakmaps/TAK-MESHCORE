package com.atakmaps.meshcore.plugin.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.MotionEvent;

import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.widgets.LinearLayoutWidget;
import com.atakmap.android.widgets.MapWidget;
import com.atakmap.android.widgets.MarkerIconWidget;
import com.atakmap.android.widgets.RootLayoutWidget;
import com.atakmap.coremap.maps.assets.Icon;
import com.atakmaps.meshcore.plugin.MeshCoreDropDownReceiver;
import com.atakmaps.meshcore.plugin.R;

import java.io.File;
import java.io.FileOutputStream;

/**
 * MeshCore status widget anchored in ATAK top-right layout.
 */
public class MeshStatusOverlay extends MarkerIconWidget implements MapWidget.OnClickListener {

    private static final String TAG = "MeshCore.StatusOverlay";
    private static final int ICON_WIDTH = 64;
    private static final int ICON_HEIGHT = 64;

    private static MeshStatusOverlay instance;
    private static boolean lastKnownConnected = false;

    private final MapView mapView;
    private final String connectedUri;
    private final String disconnectedUri;

    private MeshStatusOverlay(Context pluginContext, MapView mv, LinearLayoutWidget trLayout) {
        this.mapView = mv;
        this.connectedUri = extractIcon(pluginContext, mv,
                R.drawable.ic_mesh_status_connected, "ic_mesh_connected.png");
        this.disconnectedUri = extractIcon(pluginContext, mv,
                R.drawable.ic_mesh_status_disconnected, "ic_mesh_disconnected.png");
        trLayout.addWidget(this);
        setMargins(0f, 24f, 12f, 0f);
        setTouchable(true);
        addOnClickListener(this);
        applyIcon(false);
    }

    @Override
    public void onMapWidgetClick(MapWidget widget, MotionEvent event) {
        try {
            AtakBroadcast.getInstance().sendBroadcast(
                    new Intent(MeshCoreDropDownReceiver.SHOW_PLUGIN));
        } catch (Exception e) {
            Log.e(TAG, "Failed to open panel from status icon", e);
        }
    }

    public static void install(Context pluginContext) {
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        uninstall();
        try {
            RootLayoutWidget root =
                    (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
            if (root == null) {
                mv.postDelayed(() -> install(pluginContext), 2000);
                return;
            }
            LinearLayoutWidget tr = root.getLayout(RootLayoutWidget.TOP_RIGHT);
            if (tr == null) {
                return;
            }
            instance = new MeshStatusOverlay(pluginContext, mv, tr);
            instance.applyIcon(lastKnownConnected);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
        }
    }

    public static void uninstall() {
        if (instance == null) {
            return;
        }
        try {
            instance.removeOnClickListener(instance);
            RootLayoutWidget root =
                    (RootLayoutWidget) instance.mapView.getComponentExtra("rootLayoutWidget");
            root.getLayout(RootLayoutWidget.TOP_RIGHT).removeWidget(instance);
        } catch (Exception ignored) {
        }
        instance = null;
    }

    public static void setConnected(boolean connected) {
        lastKnownConnected = connected;
        if (instance == null) {
            return;
        }
        MapView mv = MapView.getMapView();
        if (mv == null) {
            return;
        }
        mv.post(() -> {
            if (instance != null) {
                instance.applyIcon(connected);
            }
        });
    }

    private void applyIcon(boolean connected) {
        String uri = connected ? connectedUri : disconnectedUri;
        if (uri == null) {
            return;
        }
        Icon.Builder b = new Icon.Builder();
        b.setAnchor(0, 0);
        b.setColor(Icon.STATE_DEFAULT, Color.WHITE);
        b.setSize(ICON_WIDTH, ICON_HEIGHT);
        b.setImageUri(Icon.STATE_DEFAULT, uri);
        setIcon(b.build());
    }

    private static String extractIcon(Context pluginCtx, MapView mv, int resId, String filename) {
        try {
            File dir = new File(mv.getContext().getCacheDir(), "meshcore_icons");
            dir.mkdirs();
            File out = new File(dir, filename);

            Drawable drawable = pluginCtx.getResources().getDrawable(resId, pluginCtx.getTheme());
            if (drawable == null) {
                return null;
            }

            int width = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : ICON_WIDTH;
            int height = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : ICON_HEIGHT;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);

            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            bitmap.recycle();
            return "file://" + out.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "extractIcon failed for " + filename, e);
            return null;
        }
    }
}
