package com.atakmaps.meshcore.plugin;

import android.content.Context;
import android.util.Log;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import gov.tak.api.plugin.IServiceController;

public class MeshCoreLifecycle extends AbstractPlugin {
    public MeshCoreLifecycle(IServiceController serviceController) {
        super(serviceController,
                new MeshCoreTool(serviceController.getService(
                        PluginContextProvider.class).getPluginContext()),
                new MeshCoreMapComponent());
        // Apply update-server trust before MapView exists so ATAK's repo HTTPS
        // client has the CA before GetRepoIndexOperation fires.
        try {
            Context pc = serviceController.getService(PluginContextProvider.class).getPluginContext();
            MeshCoreMapComponent.applyUpdateServerTrustEarly(pc);
        } catch (Throwable t) {
            Log.w("MeshCore", "applyUpdateServerTrustEarly failed: " + t.getMessage());
        }
    }
}
