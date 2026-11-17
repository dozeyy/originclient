package com.vulkairis.rendering.backend.vulkanmod.v1211;

/**
 * Phase 2: Metadata describing a single verified render lifecycle hook in Minecraft 1.21.1 / VulkanMod.
 */
public record RenderHookDescriptor(
        VulkanModRenderStage stage,
        String targetOwner,
        String methodName,
        String methodDescriptor,
        String injectionPoint,
        String activeRenderPass,
        String targetAttachment,
        String commandBufferSource,
        String depthConvention,
        boolean critical
) {
    public RenderHookDescriptor {
        targetOwner = (targetOwner == null) ? "" : targetOwner;
        methodName = (methodName == null) ? "" : methodName;
        methodDescriptor = (methodDescriptor == null) ? "" : methodDescriptor;
        injectionPoint = (injectionPoint == null) ? "HEAD" : injectionPoint;
        activeRenderPass = (activeRenderPass == null) ? "MainPass" : activeRenderPass;
        targetAttachment = (targetAttachment == null) ? "MainTarget" : targetAttachment;
        commandBufferSource = (commandBufferSource == null) ? "Renderer.getCommandBuffer()" : commandBufferSource;
        depthConvention = (depthConvention == null) ? "Vulkan [0, 1] forward-Z" : depthConvention;
    }
}
