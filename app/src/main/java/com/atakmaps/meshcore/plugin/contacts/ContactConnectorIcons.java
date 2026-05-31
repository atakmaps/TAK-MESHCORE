package com.atakmaps.meshcore.plugin.contacts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.atakmap.android.maps.MapView;
import com.atakmaps.meshcore.plugin.R;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Caches connector icons as file:// URIs so ATAK's contact list GL renderer can load them.
 */
public final class ContactConnectorIcons {

    private static final String TAG = "UVPro.ContactIcons";
    private static volatile String positionOnlyIconUri;

    private ContactConnectorIcons() {
    }

    public static void warmCache(Context pluginContext) {
        getPositionOnlyIconUri(pluginContext);
    }

    public static String getPositionOnlyIconUri(Context pluginContext) {
        String cached = positionOnlyIconUri;
        if (cached != null) {
            return cached;
        }
        synchronized (ContactConnectorIcons.class) {
            if (positionOnlyIconUri != null) {
                return positionOnlyIconUri;
            }
            Context ctx = pluginContext;
            if (ctx == null) {
                MapView mv = MapView.getMapView();
                ctx = mv != null ? mv.getContext() : null;
            }
            positionOnlyIconUri = extractDrawableToCache(ctx,
                    R.drawable.ic_contact_position_only,
                    "ic_contact_position_only.png");
            return positionOnlyIconUri;
        }
    }

    private static String extractDrawableToCache(Context context, int resId,
                                                 String filename) {
        if (context == null) {
            return null;
        }
        try {
            Context cacheCtx = MapView.getMapView() != null
                    ? MapView.getMapView().getContext()
                    : context.getApplicationContext();
            File dir = new File(cacheCtx.getCacheDir(), "uvpro_icons");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File out = new File(dir, filename);
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resId);
            if (bitmap == null) {
                return null;
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            String uri = "file://" + out.getAbsolutePath();
            Log.d(TAG, "Cached connector icon " + filename + " -> " + uri);
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "Failed to cache connector icon " + filename, e);
            return null;
        }
    }
}
