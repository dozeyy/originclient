package com.vulkairis.core;

import com.vulkairis.client.render.BridgeTestRenderer;
import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import com.vulkairis.vulkan.descriptor.VulkanDescriptorManager;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import com.vulkairis.vulkan.test.StandaloneVulkanTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages clean lifecycle hooks for Vulkairis operations:
 * initialization, shader reloads, window resize, and graceful shutdown.
 */
public class VulkairisLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Lifecycle");

    public static void onClientInit() {
        LOGGER.info("[Vulkairis] Lifecycle: onClientInit");
        VulkairisController.getInstance().initialize();
    }

    public static void onRenderSystemInit() {
        LOGGER.info("[Vulkairis] Lifecycle: onRenderSystemInit");
    }

    public static void onWindowResize(int width, int height) {
        LOGGER.debug("[Vulkairis] Lifecycle: window resized to {}x{}", width, height);
        // Phase 6: render passes may be recreated under the new swapchain; drop cached raw
        // pipelines so the next controlled draw rebuilds against the live pass. (The render
        // thread call site for this hook lands with the resize/target routing work.)
        try {
            StandaloneVulkanTest.invalidatePipelines();
        } catch (Throwable ignored) {
        }
    }

    public static void onShaderPackReload() {
        LOGGER.info("[Vulkairis] Lifecycle: onShaderPackReload");
        DiagnosticManager.log(DiagnosticManager.Category.SHADER, "Shader pack reload initiated");
        try {
            StandaloneVulkanTest.invalidatePipelines();
        } catch (Throwable ignored) {
        }
    }

    public static void onWorldUnload() {
        LOGGER.info("[Vulkairis] Lifecycle: onWorldUnload");
    }

    public static void onShutdown() {
        LOGGER.info("[Vulkairis] Lifecycle: onShutdown initiated — releasing Vulkan resources in reverse dependency order");
        try {
            // 1. Wait for GPU idle
            VulkanBackendAdapter.waitIdle();

            // 2. Clean up test pipelines
            BridgeTestRenderer.cleanUp();
            StandaloneVulkanTest.cleanUp();

            // 3. Clean up pipeline cache and in-memory pipelines
            PipelineManager.cleanUp();

            // 4. Clean up descriptor pools and layouts
            VulkanDescriptorManager.cleanUp();

            // 5. Clean up shader registry and shader modules
            VulkanShaderRegistry.cleanUp();

            // 6. Destroy owned device-level pools
            VulkanBackendAdapter.destroy();

            // 7. Transition state
            VulkairisController.getInstance().setState(BackendState.SHUTDOWN);
            LOGGER.info("[Vulkairis] Lifecycle: onShutdown completed cleanly.");
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis] Lifecycle: error during shutdown: {}", t.getMessage(), t);
        }
    }
}
