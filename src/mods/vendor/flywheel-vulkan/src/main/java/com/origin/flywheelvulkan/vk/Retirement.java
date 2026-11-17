package com.origin.flywheelvulkan.vk;

import java.util.ArrayList;
import java.util.List;

/**
 * Deferred destruction of Vulkan objects the GPU may still be reading.
 *
 * <p>Something retired while VulkanMod records frame slot k is destroyed the next time slot k
 * starts. VulkanMod waits on slot k's fence before recording into it again, and a fence's signal
 * covers every batch submitted before it on the queue, so by then every frame that could have
 * used the object (slot k's own and all earlier ones) has finished.
 */
public final class Retirement {
	private List<Runnable>[] slots;

	@SuppressWarnings("unchecked")
	public Retirement(int frames) {
		slots = new List[frames];
		for (int i = 0; i < frames; i++) {
			slots[i] = new ArrayList<>();
		}
	}

	public int frames() {
		return slots.length;
	}

	public void retire(int slot, Runnable destroy) {
		slots[slot].add(destroy);
	}

	/** Call once at the start of each frame that records into {@code slot}. */
	public void beginFrame(int slot) {
		List<Runnable> list = slots[slot];
		for (Runnable r : list) {
			r.run();
		}
		list.clear();
	}

	/** Device must be idle (or the objects never used). */
	public void destroyAll() {
		for (List<Runnable> list : slots) {
			for (Runnable r : list) {
				r.run();
			}
			list.clear();
		}
	}
}
