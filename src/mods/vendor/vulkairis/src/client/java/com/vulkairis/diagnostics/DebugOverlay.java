package com.vulkairis.diagnostics;

import com.vulkairis.config.ConfigManager;
import com.vulkairis.core.VulkairisController;
import com.vulkairis.vulkan.VulkanCapabilities;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic overlay renderer for in-game debugging (F3 / custom overlay).
 */
public class DebugOverlay {
    public static List<String> getDebugLines() {
        List<String> lines = new ArrayList<>();
        VulkairisController controller = VulkairisController.getInstance();
        VulkanCapabilities caps = VulkanCapabilities.getActiveCapabilities();

        lines.add("§6[Vulkairis Experimental Bridge]§r");
        lines.add(String.format("Backend State: %s%s§r",
                controller.getState() == com.vulkairis.core.BackendState.READY ? "§a" : "§e",
                controller.getState()));
        lines.add("GPU: " + caps.getDeviceName() + " (" + caps.getVendorName() + ")");
        lines.add("Vulkan API: " + caps.getApiVersionString());
        lines.add("Active Shaderpack: " + controller.getActiveShaderPackName());
        lines.add("Debug Render Mode: " + ConfigManager.getConfig().getDebugRenderMode());
        lines.add("Iris Pipeline Link: " + ConfigManager.getConfig().isIrisPipelineLink());
        lines.add(String.format("Shaders Compiled: %d | Pipelines: %d",
                controller.getCompiledShadersCount(), controller.getCreatedPipelinesCount()));
        lines.add("Last Fallback Reason: " + DiagnosticManager.getLastFallbackReason());
        lines.add("Active Pass: " + controller.getActivePass()
                + " | Target: " + controller.getCurrentTarget());
        // Phase E: mirror the structured fallback engagement verbatim plus the terminal-step
        // labeling, so the overlay never implies shaders work when the bridge fell back.
        try {
            com.vulkairis.diagnostics.FallbackRecord.Entry lastFallback =
                    controller.getLastFallbackRecord();
            lines.add(com.vulkairis.diagnostics.FallbackRecord.describeSingleLine(lastFallback));
            com.vulkairis.rendering.exec.PackChainPlan plan =
                    com.vulkairis.rendering.exec.VulkairisFrameCoordinator.getChainPlan();
            lines.add(plan != null ? plan.finalLabel() : "final: none (no chain plan installed)");
        } catch (Throwable t) {
            lines.add("fallback detail unavailable: " + t.getMessage());
        }
        lines.add("Backend: " + com.vulkairis.client.gui.services.RendererSettingsService.effectiveBackend()
                + " (requested: " + com.vulkairis.client.gui.services.RendererSettingsService.requestedBackend() + ")");

        return lines;
    }

    public static void render(GuiGraphics graphics) {
        if (graphics == null) return;
        List<String> lines = getDebugLines();
        int y = 10;
        for (String line : lines) {
            graphics.drawString(net.minecraft.client.Minecraft.getInstance().font, line, 10, y, 0xFFFFFF);
            y += 10;
        }
    }
}
