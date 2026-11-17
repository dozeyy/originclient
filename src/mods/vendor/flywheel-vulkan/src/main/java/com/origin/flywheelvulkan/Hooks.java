package com.origin.flywheelvulkan;

/**
 * Everything that touches Flywheel or VulkanMod classes, reached only past {@link Gate}.
 * Phase 1 registers the origin:vulkan backend here.
 */
final class Hooks {
	private Hooks() {
	}

	static void init() {
		FlywheelVulkanClient.LOGGER.info("Vulkan Flywheel backend gate open (VulkanMod + Flywheel pinned builds found)");
	}
}
