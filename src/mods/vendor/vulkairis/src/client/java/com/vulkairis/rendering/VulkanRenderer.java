package com.vulkairis.rendering;

import com.vulkairis.core.VulkairisController;
import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import com.vulkairis.vulkan.descriptor.VulkanDescriptorManager;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concrete Vulkan implementation of RenderBackend.
 */
public class VulkanRenderer implements RenderBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/VulkanRenderer");
    private static final VulkanRenderer INSTANCE = new VulkanRenderer();

    private GraphicsPipeline currentPipeline = null;
    private boolean inFrame = false;

    public static VulkanRenderer getInstance() {
        return INSTANCE;
    }

    @Override
    public void beginFrame() {
        inFrame = true;
        VulkairisController.getInstance().incrementFrameCount();
    }

    @Override
    public void endFrame() {
        inFrame = false;
    }

    public void bindPipeline(GraphicsPipeline pipeline) {
        this.currentPipeline = pipeline;
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null && pipeline != null) {
                renderer.bindGraphicsPipeline(pipeline);
            }
        } catch (Throwable t) {
            DiagnosticManager.log(DiagnosticManager.Category.PIPELINE, "Error binding graphics pipeline", t.getMessage(), t);
        }
    }

    @Override
    public void submitDraw(DrawCommand command) {
        if (!isAvailable() || command == null) return;

        try {
            Renderer renderer = Renderer.getInstance();
            VkCommandBuffer cmd = Renderer.getCommandBuffer();
            if (renderer == null || cmd == null) return;

            if (currentPipeline != null && command.descriptorSet() != 0L) {
                VulkanDescriptorManager.bindDescriptorSets(cmd, currentPipeline.getLayout(), command.descriptorSet());
            }

            if (command.vertexData() != null && command.vertexCount() > 0) {
                Renderer.getDrawer().draw(command.vertexData(), command.mode(), command.format(), command.vertexCount());
            }
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/Renderer] Error submitting draw call: {}", t.getMessage());
            VulkairisController.getInstance().triggerFallback("Draw call submission error", t.getMessage());
        }
    }

    @Override
    public void flush() {
        try {
            Renderer renderer = Renderer.getInstance();
            if (renderer != null) {
                renderer.flushCmds();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isAvailable() {
        return VulkanBackendAdapter.isLive(VulkanBackendAdapter.getDevice()) && VulkairisController.getInstance().isVulkanActive();
    }
}
