package com.origin.flywheelvulkan.vk;

import com.origin.flywheelvulkan.mixin.VulkanRendererAccessor;

import net.vulkanmod.vulkan.Renderer;

/** Keeps VulkanMod's cached command-buffer state honest after Origin records raw Vulkan commands. */
public final class VkStateSync {
	private VkStateSync() {
	}

	/**
	 * Call after any raw vkCmdBindPipeline. VulkanMod re-binds descriptor sets on every draw
	 * (DescriptorSets.bindSets has no cache, javap 0.6.7), so the pipeline handle is the only
	 * cached state to drop. Dynamic viewport/scissor/depth-bias/line-width state survives because
	 * Origin's pipelines declare the same dynamic set as VulkanMod's.
	 */
	public static void afterForeignBind() {
		((VulkanRendererAccessor) Renderer.getInstance()).origin$setBoundPipelineHandle(0L);
	}
}
