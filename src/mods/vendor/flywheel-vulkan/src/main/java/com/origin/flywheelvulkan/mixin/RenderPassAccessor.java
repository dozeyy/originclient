package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.vulkanmod.vulkan.framebuffer.RenderPass;

/**
 * Target: VulkanMod 0.6.7 {@code RenderPass.colorAttachmentInfo / depthAttachmentInfo}
 * (package-private; javap -p on vulkanmod-2eqroDKD.jar, 2026-11-17).
 * Why: VkPipelines keys its pipeline cache on the pass's attachment formats, because VulkanMod
 * rebuilds its render passes (new handles) on every resize.
 * If this stops applying: the backend's first pipeline lookup throws ClassCastException. The
 * plugin only applies net.vulkanmod mixins when VulkanMod matches the pin, the same condition
 * the backend needs to turn on at all.
 */
@Mixin(value = RenderPass.class, remap = false)
public interface RenderPassAccessor {
	@Accessor(value = "colorAttachmentInfo", remap = false)
	RenderPass.AttachmentInfo origin$colorAttachment();

	@Accessor(value = "depthAttachmentInfo", remap = false)
	RenderPass.AttachmentInfo origin$depthAttachment();
}
