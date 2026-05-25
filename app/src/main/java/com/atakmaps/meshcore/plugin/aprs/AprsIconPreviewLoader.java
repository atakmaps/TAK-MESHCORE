package com.atakmaps.meshcore.plugin.aprs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.atakmap.android.icons.UserIcon;
import com.atakmaps.meshcore.plugin.ax25.AprsIconsetInstaller;
import com.atakmaps.meshcore.plugin.ax25.AprsSymbolMapper;
import com.atakmaps.meshcore.plugin.ui.SettingsFragment;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Loads the selected outbound APRS symbol bitmap for UI preview (ATAK iconset DB or bundled zip).
 */
public final class AprsIconPreviewLoader {

    private static final String BUNDLED_ICONSET_ASSET = "APRS-Symbols-APRSdroid.zip";
    private static final Map<String, Bitmap> BUNDLED_BITMAP_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean bundledCacheLoaded;

    private AprsIconPreviewLoader() {
    }

    public static boolean isIconSelected(Context context) {
        return SettingsFragment.isAprsIconSelected(context);
    }

    /**
     * @return decoded PNG bitmap, or null if not selected / load failed
     */
    public static Bitmap loadSelectedIconBitmap(Context context, Context pluginContext) {
        if (context == null || !isIconSelected(context)) {
            return null;
        }
        char table = SettingsFragment.getAprsSymbolTable(context);
        char code = SettingsFragment.getAprsSymbolCode(context);
        return loadIconBitmapForSymbol(context, pluginContext, table, code);
    }

    public static Bitmap loadIconBitmapForSymbol(Context context, Context pluginContext,
                                                 char table, char code) {
        String path = AprsSymbolMapper.iconsetPath(table, code);
        if (path == null) {
            return null;
        }
        Bitmap fromDb = loadFromAtakIconDatabase(context, path);
        if (fromDb != null) {
            return fromDb;
        }
        if (pluginContext != null) {
            return loadFromBundledIconZip(pluginContext, path);
        }
        return null;
    }

    private static Bitmap loadFromAtakIconDatabase(Context context, String iconsetPath) {
        try {
            if (!AprsIconsetInstaller.isIconsetInstalled()) {
                return null;
            }
            Context loadCtx = context.getApplicationContext();
            UserIcon icon = UserIcon.GetIconFromIconsetPath(iconsetPath, true, loadCtx);
            if (icon != null) {
                return icon.getBitMap();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Zip entries use {@code Primary/p-2d.png} (no UID prefix). */
    private static Bitmap loadFromBundledIconZip(Context pluginContext, String iconsetPath) {
        int slash = iconsetPath.indexOf('/');
        if (slash < 0 || slash >= iconsetPath.length() - 1) {
            return null;
        }
        String zipEntry = iconsetPath.substring(slash + 1);
        ensureBundledCacheLoaded(pluginContext);
        return BUNDLED_BITMAP_CACHE.get(zipEntry);
    }

    private static synchronized void ensureBundledCacheLoaded(Context pluginContext) {
        if (bundledCacheLoaded || pluginContext == null) {
            return;
        }
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(pluginContext.getAssets().open(BUNDLED_ICONSET_ASSET));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name != null && name.endsWith(".png")) {
                    Bitmap bmp = BitmapFactory.decodeStream(zis);
                    if (bmp != null) {
                        BUNDLED_BITMAP_CACHE.put(name, bmp);
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception ignored) {
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (Exception ignored) {
                }
            }
        }
        bundledCacheLoaded = true;
    }
}
