package com.atakmaps.meshcore.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import gov.tak.api.plugin.IServiceController;

public class MeshCoreLifecycle extends AbstractPlugin {
    public MeshCoreLifecycle(IServiceController serviceController) {
        super(serviceController,
                new MeshCoreTool(serviceController.getService(
                        PluginContextProvider.class).getPluginContext()),
                new MeshCoreMapComponent());
    }
}
