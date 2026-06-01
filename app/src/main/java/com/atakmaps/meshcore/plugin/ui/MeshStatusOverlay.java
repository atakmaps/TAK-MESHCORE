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
    private static final Object INSTALL_LOCK = new Object();

    private static MeshStatusOverlay instance;
    private static boolean lastKnownConnected = false;
    private static boolean installScheduled = false;

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
        synchronized (INSTALL_LOCK) {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                return;
            }
            uninstallInternal(mv);
            try {
                RootLayoutWidget root =
                        (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
                if (root == null) {
                    if (!installScheduled) {
                        installScheduled = true;
                        mv.postDelayed(() -> {
                            installScheduled = false;
                            install(pluginContext);
                        }, 2000);
                    }
                    return;
                }
                LinearLayoutWidget tr = root.getLayout(RootLayoutWidget.TOP_RIGHT);
                if (tr == null) {
                    return;
                }
                removeOrphanOverlays(tr);
                instance = new MeshStatusOverlay(pluginContext, mv, tr);
                instance.applyIcon(lastKnownConnected);
                Log.d(TAG, "Status overlay installed");
            } catch (Exception e) {
                Log.e(TAG, "install failed", e);
            }
        }
    }

    public static void uninstall() {
        synchronized (INSTALL_LOCK) {
            MapView mv = MapView.getMapView();
            if (mv == null) {
                instance = null;
                return;
            }
            uninstallInternal(mv);
        }
    }

    private static void uninstallInternal(MapView mv) {
        if (instance == null) {
            return;
        }
        try {
            instance.removeOnClickListener(instance);
            RootLayoutWidget root =
                    (RootLayoutWidget) mv.getComponentExtra("rootLayoutWidget");
            if (root != null) {
                LinearLayoutWidget tr = root.getLayout(RootLayoutWidget.TOP_RIGHT);
                if (tr != null) {
                    tr.removeWidget(instance);
                    removeOrphanOverlays(tr);
                }
            }
        } catch (Exception ignored) {
        }
        instance = null;
    }

    /** Removes duplicate overlay widgets left from prior installs. */
    private static void removeOrphanOverlays(LinearLayoutWidget tr) {
        if (tr == null) {
            return;
        }
        try {
            for (int i = tr.getChildWidgetCount() - 1; i >= 0; i--) {
                if (tr.getChildWidgetAt(i) instanceof MeshStatusOverlay) {
                    MeshStatusOverlay overlay = (MeshStatusOverlay) tr.getChildWidgetAt(i);
                    if (overlay != instance) {
                        tr.removeWidget(overlay);
                        Log.w(TAG, "Removed orphan MeshStatusOverlay widget");
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not prune orphan overlays", e);
        }
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
