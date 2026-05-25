package com.atakmaps.meshcore.plugin;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.atak.plugins.impl.AbstractPluginTool;

public class MeshCoreTool extends AbstractPluginTool {
    public MeshCoreTool(Context context) {
        super(context,
                context.getString(context.getResources().getIdentifier(
                        "app_name", "string", context.getPackageName())),
                context.getString(context.getResources().getIdentifier(
                        "plugin_description", "string", context.getPackageName())),
                toolbarIcon(context),
                MeshCoreDropDownReceiver.SHOW_PLUGIN);
    }

    /**
     * Quick-launcher / toolbar icons are tinted by ATAK; the full-color {@code ic_uvpro} badge is
     * almost entirely opaque and becomes a flat white disc. Use stroke-only {@code ic_uvpro_toolbar}.
     * Clear theme tint so stroke art is not flattened on some ATAK builds.
     */
    public static Drawable toolbarIcon(Context context) {
        Drawable d = context.getResources().getDrawable(R.drawable.ic_uvpro_toolbar, context.getTheme());
        if (d == null) {
            return null;
        }
        d = d.mutate();
        d.clearColorFilter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            d.setTintList(null);
        }
        return d;
    }

    public void dispose() {
    }
}
