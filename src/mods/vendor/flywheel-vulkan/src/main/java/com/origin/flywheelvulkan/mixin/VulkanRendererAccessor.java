package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.vulkanmod.vulkan.Renderer;

/**
 * Target: VulkanMod 0.6.7 {@code net.vulkanmod.vulkan.Renderer.boundPipelineHandle} (private long;
 * javap -p on vulkanmod-2eqroDKD.jar, 2026-11-16).
 * Why: Renderer.bindGraphicsPipeline returns early when the pipeline handle it wants equals
 * boundPipelineHandle. After Origin binds its own pipeline with raw vkCmdBindPipeline, the cache
 * is stale; zeroing it makes VulkanMod's next draw bind its pipeline again.
 * If this stops applying: VulkanMod's next draw after an Origin draw runs with Origin's pipeline
 * (wrong shaders, wrong layout: garbage or a device lost). The plugin only applies it when
 * VulkanMod's version matches the pin, and the backend refuses to turn on without it.
 */
@Mixin(value = Renderer.class, remap = false)
public interface VulkanRendererAccessor {
	@Accessor(value = "boundPipelineHandle", remap = false)
	void origin$setBoundPipelineHandle(long handle);
}
