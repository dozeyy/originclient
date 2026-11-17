package com.origin.flywheelvulkan.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** The pipeline cache key: same formats share pipelines, any format change gets new ones. */
class RenderPassFormatsTest {
	private static final int RGBA8 = 37;   // VK_FORMAT_R8G8B8A8_UNORM
	private static final int BGRA8 = 44;   // VK_FORMAT_B8G8R8A8_UNORM
	private static final int D32 = 126;    // VK_FORMAT_D32_SFLOAT

	@Test
	void passRebuiltOnResizeWithSameFormatsReusesPipelines() {
		assertEquals(RenderPassFormats.of(RGBA8, D32), RenderPassFormats.of(RGBA8, D32));
	}

	@Test
	void differentColorFormatNeverSharesAPipeline() {
		assertNotEquals(RenderPassFormats.of(RGBA8, D32), RenderPassFormats.of(BGRA8, D32));
	}

	@Test
	void missingAttachmentIsItsOwnKey() {
		assertEquals(new RenderPassFormats(RGBA8, RenderPassFormats.NONE), RenderPassFormats.of(RGBA8, null));
		assertNotEquals(RenderPassFormats.of(RGBA8, null), RenderPassFormats.of(RGBA8, D32));
		assertNotEquals(RenderPassFormats.of(null, D32), RenderPassFormats.of(0, D32));
	}
}
