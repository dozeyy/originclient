package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.vulkanmod.vulkan.framebuffer.RenderPass;

/**
 * Target: VulkanMod 0.6.7 {@code RenderPass.AttachmentInfo.format} (final int, package-private;
 * javap -p on vulkanmod-2eqroDKD.jar, 2026-11-17).
 * Why and what breaks: see {@link RenderPassAccessor}.
 */
@Mixin(value = RenderPass.AttachmentInfo.class, remap = false)
public interface AttachmentInfoAccessor {
	@Accessor(value = "format", remap = false)
	int origin$format();
}
