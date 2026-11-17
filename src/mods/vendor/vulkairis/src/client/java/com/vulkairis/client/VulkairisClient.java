package com.vulkairis.client;

import com.vulkairis.compatibility.CompatibilityManager;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.core.VulkairisLifecycle;
import com.vulkairis.diagnostics.VulkairisCommands;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VulkairisClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Vulkairis] Initializing Vulkairis Client Graphics Bridge...");

        // 1. Initialize lifecycle and controller
        VulkairisLifecycle.onClientInit();

        // 2. Evaluate hardware and mod environment
        CompatibilityManager.evaluateEnvironment();

        // 3. Register client commands (/vulkairis status, vulkan, shader, etc.)
        VulkairisCommands.register();

        // 4. Ensure Iris StateUpdateNotifiers are safe and non-null
        com.vulkairis.iris.StateUpdateNotifierManager.ensureInitialized();

        // 5. Bind the VulkanMod terrain hook so chunk draws resolve pack pipelines
        try {
            com.vulkairis.bridge.VulkanModTerrainHook.bind();
        } catch (Throwable ignored) {
        }

        LOGGER.info("[Vulkairis] Client initialization complete. Bridge state: {}",
                VulkairisController.getInstance().getState());
    }
}
