package com.uvpro.plugin;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atak.plugins.impl.AbstractPluginTool;

public class UVProTool extends AbstractPluginTool {
    public UVProTool(Context context) {
        super(context,
                context.getString(context.getResources().getIdentifier(
                        "app_name", "string", context.getPackageName())),
                context.getString(context.getResources().getIdentifier(
                        "plugin_description", "string", context.getPackageName())),
                toolbarIcon(context),
                UVProDropDownReceiver.SHOW_PLUGIN);
    }

    /**
     * Quick-launcher / toolbar icons are tinted by ATAK; the full-color {@code ic_uvpro} badge is
     * almost entirely opaque and becomes a flat white disc. Use stroke-only {@code ic_uvpro_toolbar}.
     */
    private static Drawable toolbarIcon(Context context) {
        Drawable d = context.getResources().getDrawable(R.drawable.ic_uvpro_toolbar, context.getTheme());
        return d != null ? d.mutate() : null;
    }

    public void dispose() {
    }
}
