package com.origin.flywheelvulkan.backend;

import java.util.ArrayList;
import java.util.List;

import net.vulkanmod.vulkan.Vulkan;

import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

/**
 * Vulkan objects of a deleted engine. Flywheel can delete an engine in the middle of a frame
 * (LevelRenderer.allChanged, or this backend's own fallback) after it already recorded draws into
 * VulkanMod's not-yet-submitted command buffer. Destroying then would free objects that command
 * buffer still uses, so destruction waits for the next client tick: by then that frame was
 * submitted, and vkDeviceWaitIdle covers it. Render thread only.
 */
public final class Graveyard {
	private static final List<Runnable> PENDING = new ArrayList<>();

	private Graveyard() {
	}

	static void bury(Runnable destroy) {
		PENDING.add(destroy);
	}

	/** Called at the start of every client tick (Hooks). */
	public static void flush() {
		if (PENDING.isEmpty()) {
			return;
		}
		vkDeviceWaitIdle(Vulkan.getVkDevice());
		List<Runnable> now = new ArrayList<>(PENDING);
		PENDING.clear();
		for (Runnable r : now) {
			r.run();
		}
	}
}
