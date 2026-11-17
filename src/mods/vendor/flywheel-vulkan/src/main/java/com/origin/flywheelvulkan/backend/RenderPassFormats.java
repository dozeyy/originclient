package com.origin.flywheelvulkan.backend;

/**
 * What makes two render passes interchangeable for a pipeline (Vulkan spec, "Render Pass
 * Compatibility"): the attachment formats, since VulkanMod 0.6.7 only builds single-sampled,
 * single-subpass passes. Load/store ops and layouts don't count. Pure, so it is unit-tested.
 */
record RenderPassFormats(int color, int depth) {
	/** Stands for "the pass has no such attachment" (VK_FORMAT_UNDEFINED is 0, so not that). */
	static final int NONE = -1;

	static RenderPassFormats of(Integer color, Integer depth) {
		return new RenderPassFormats(color == null ? NONE : color, depth == null ? NONE : depth);
	}
}
