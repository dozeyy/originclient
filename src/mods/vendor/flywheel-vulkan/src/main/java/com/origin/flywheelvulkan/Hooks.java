package com.origin.flywheelvulkan;

import com.origin.flywheelvulkan.backend.Graveyard;
import com.origin.flywheelvulkan.backend.VkBackend;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Everything that touches Flywheel or VulkanMod classes, reached only past {@link Gate}.
 */
final class Hooks {
	private Hooks() {
	}

	static void init() {
		VkBackend.register();
		ClientTickEvents.START_CLIENT_TICK.register(client -> Graveyard.flush());
		FlywheelVulkanClient.LOGGER.info("Vulkan Flywheel backend gate open (VulkanMod + Flywheel pinned builds found); origin:vulkan registered");
	}
}
