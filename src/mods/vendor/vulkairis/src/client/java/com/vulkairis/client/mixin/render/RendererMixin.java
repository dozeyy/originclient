package com.vulkairis.client.mixin.render;

import com.mojang.blaze3d.platform.Window;
import com.vulkairis.config.ConfigManager;
import com.vulkairis.core.DebugRenderMode;
import com.vulkairis.rendering.VulkanRenderer;
import com.vulkairis.vulkan.test.StandaloneVulkanTest;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Renderer.class, remap = false)
public class RendererMixin {

    @Inject(method = "beginFrame()V", at = @At("HEAD"), remap = false)
    private void onBeginFrame(CallbackInfo ci) {
        com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter.getInstance()
                .onFrameBegin(com.vulkairis.core.VulkairisController.getInstance().getFrameCount());
        VulkanRenderer.getInstance().beginFrame();
        com.vulkairis.diagnostics.RenderHookGuard.runHook("beginFrame-coordinator",
                com.vulkairis.diagnostics.DiagnosticManager.Phase.SCHEDULING, () -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client != null && client.getWindow() != null) {
                        int width = client.getWindow().getWidth();
                        int height = client.getWindow().getHeight();
                        if (width > 0 && height > 0) {
                            com.vulkairis.rendering.exec.VulkairisFrameCoordinator.onBeginFrame(width, height);
                        }
                        // Capture world state into the uniform bridge for transport.
                        try {
                            com.vulkairis.bridge.UniformBridge ub = com.vulkairis.bridge.UniformBridge.getInstance();
                            ub.updateResolution(width, height);
                            try {
                                org.joml.Matrix4f mv = com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix();
                                org.joml.Matrix4f proj = com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix();
                                ub.updateMatrices(mv, proj);
                            } catch (Throwable ignored) {
                            }
                            try {
                                net.minecraft.client.Camera cam = client.gameRenderer.getMainCamera();
                                net.minecraft.world.phys.Vec3 pos = cam.getPosition();
                                ub.updateCamera((float) pos.x, (float) pos.y, (float) pos.z,
                                        client.getFrameTimeNs());
                            } catch (Throwable ignored) {
                            }
                            if (client.level != null) {
                                try {
                                    ub.updateTime(1.0f / 20.0f, client.level.getDayTime());
                                } catch (Throwable ignored) {
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    /**
     * Restore viewport/scissor to the full window before any GUI geometry is recorded this frame.
     *
     * <p>The controlled draws narrow dynamic viewport/scissor to their bound framebuffer, and that
     * state is sticky. Resetting at the start of the frame guarantees the menu never inherits the
     * debug quad's viewport, even if a previous controlled draw left the scissor narrowed.</p>
     */
    @Inject(method = "beginFrame()V", at = @At("TAIL"), remap = false)
    private void onBeginFrameResetViewport(CallbackInfo ci) {
        resetViewportScissorToWindow();
    }

    @Inject(method = "endRenderPass(Lorg/lwjgl/vulkan/VkCommandBuffer;)V", at = @At("HEAD"), remap = false)
    private void onEndRenderPass(VkCommandBuffer cmdBuffer, CallbackInfo ci) {
        com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter.getInstance()
                .onStageObserved(com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage.COMPOSITE_POST, "Renderer.endRenderPass");
        com.vulkairis.diagnostics.RenderHookGuard.runHook("endRenderPass-dispatch",
                com.vulkairis.diagnostics.DiagnosticManager.Phase.DRAW, () -> {
                    DebugRenderMode mode = ConfigManager.getConfig().getDebugRenderMode();
                    // Prove (in the log) which render target this frame is presenting to. Runs for every mode
                    // so the menu/UI path is observable even when the controlled quad is disabled.
                    StandaloneVulkanTest.logRenderTargetDiagnostic(mode);
                    switch (mode) {
                        case TRIANGLE -> StandaloneVulkanTest.executeTestDraw(cmdBuffer);
                        case SHADER_TEST -> StandaloneVulkanTest.executeShaderTestDraw(cmdBuffer);
                        case FLAT, UV, LIGHTMAP, DEPTH, NO_POST ->
                                StandaloneVulkanTest.executeVisualizerDraw(mode, cmdBuffer);
                        case OFF, NORMAL -> {
                            // No controlled override. When a shader pack is active and the coordinator
                            // has an executable graph, run the composite/final chain regardless of the
                            // debug mode toggle (auto-engagement).
                            if (com.vulkairis.rendering.exec.VulkairisFrameCoordinator.isActive()) {
                                com.vulkairis.rendering.exec.VulkairisFrameCoordinator.onExecutePasses(cmdBuffer);
                            }
                        }
                    }
                });
    }

    @Inject(method = "endFrame()V", at = @At("TAIL"), remap = false)
    private void onEndFrame(CallbackInfo ci) {
        com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModLifecycleAdapter.getInstance().onFrameEnd();
        com.vulkairis.diagnostics.RenderHookGuard.runHook("endFrame-coordinator",
                com.vulkairis.diagnostics.DiagnosticManager.Phase.PRESENT,
                () -> com.vulkairis.rendering.exec.VulkairisFrameCoordinator.onEndFrame());
        VulkanRenderer.getInstance().endFrame();
    }

    /**
     * Best-effort reset of dynamic viewport/scissor to the window framebuffer size. Guarded against
     * a missing window or command buffer so headless/early-init frames cannot crash.
     */
    private static void resetViewportScissorToWindow() {
        com.vulkairis.diagnostics.RenderHookGuard.runHook("beginFrame-viewport-reset",
                com.vulkairis.diagnostics.DiagnosticManager.Phase.DRAW, () -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client == null) return;

                    Window window = client.getWindow();
                    if (window == null) return;

                    int width = window.getWidth();
                    int height = window.getHeight();
                    if (width <= 0 || height <= 0) return;

                    VkCommandBuffer cmdBuffer = Renderer.getCommandBuffer();
                    StandaloneVulkanTest.resetViewportScissor(cmdBuffer, width, height);
                });
    }
}
