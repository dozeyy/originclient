package com.origin.flywheelvulkan.vk;

import java.nio.LongBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import net.vulkanmod.vulkan.Vulkan;

import static org.lwjgl.vulkan.VK10.*;

/**
 * A host-visible, host-coherent, persistently mapped VkBuffer made through VulkanMod's own VMA
 * allocator (Vulkan.getAllocator, javap 0.6.7). The CPU writes through {@link #ptr()}; coherent
 * memory needs no flush. Never written while the GPU may read it: callers keep one per frame in
 * flight and retire old ones through {@link Retirement}.
 */
public final class HostBuffer {
	public final long buffer;
	private final long allocation;
	private final long ptr;
	public final long size;

	private HostBuffer(long buffer, long allocation, long ptr, long size) {
		this.buffer = buffer;
		this.allocation = allocation;
		this.ptr = ptr;
		this.size = size;
	}

	public static HostBuffer create(long size, int usage) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer pBuf = stack.mallocLong(1);
			PointerBuffer pAlloc = stack.mallocPointer(1);
			VmaAllocationInfo info = VmaAllocationInfo.calloc(stack);
			int r = Vma.vmaCreateBuffer(Vulkan.getAllocator(),
					VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE),
					VmaAllocationCreateInfo.calloc(stack)
							.flags(Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT)
							.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT),
					pBuf, pAlloc, info);
			if (r != VK_SUCCESS) {
				throw new IllegalStateException("vmaCreateBuffer(" + size + ") failed: VkResult " + r);
			}
			return new HostBuffer(pBuf.get(0), pAlloc.get(0), info.pMappedData(), size);
		}
	}

	public long ptr() {
		return ptr;
	}

	public void destroy() {
		Vma.vmaDestroyBuffer(Vulkan.getAllocator(), buffer, allocation);
	}

	/** Grows by at least 1.5x so per-frame regrowth settles fast. */
	public static long grownSize(long needed, long current) {
		return Math.max(needed, Math.max(4096, current + current / 2));
	}
}
