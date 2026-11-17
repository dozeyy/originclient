package com.vulkairis.backend.vulkanmod;

import org.lwjgl.vulkan.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * ponytail: Adapter to use VulkanMod's device/queue.
 * Prevents Vulkairis from creating its own VkInstance/VkDevice.
 */
public class VulkanBackendAdapter {
    private static VkDevice device;
    private static VkQueue graphicsQueue;
    private static VkPhysicalDevice physicalDevice;

    private static final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong(0L);
    private static long lastObservedDeviceAddress = 0L;

    public static void init(VkDevice dev, VkQueue queue, VkPhysicalDevice physDev) {
        device = dev;
        graphicsQueue = queue;
        physicalDevice = physDev;
        if (dev != null) {
            observeDevice(dev.address());
        }
    }

    public static void observeDevice(long deviceAddress) {
        if (deviceAddress == 0L) {
            // dead observations must be ignored
            return;
        }
        if (deviceAddress != lastObservedDeviceAddress) {
            lastObservedDeviceAddress = deviceAddress;
            generation.incrementAndGet();
        }
    }

    /**
     * Origin (step 3b): nothing ever called {@link #init}, so upstream's device was always null and every
     * Iris program failed with "Vulkan device not ready" (no shader module, no pipeline, every post pass
     * skipped). The device is VulkanMod's own, so it is read from {@code DeviceManager} once it exists.
     */
    public static VkDevice getDevice() {
        if (device == null) {
            adoptVulkanModDevice();
        }
        return device;
    }

    public static VkQueue getQueue() { return graphicsQueue; }

    public static VkPhysicalDevice getPhysicalDevice() {
        if (physicalDevice == null) {
            adoptVulkanModDevice();
        }
        return physicalDevice;
    }

    private static void adoptVulkanModDevice() {
        try {
            VkDevice dev = net.vulkanmod.vulkan.device.DeviceManager.vkDevice;
            if (dev != null) {
                device = dev;
                physicalDevice = net.vulkanmod.vulkan.device.DeviceManager.physicalDevice;
                observeDevice(dev.address());
            }
        } catch (Throwable ignored) {
            // VulkanMod absent (unit tests) or not initialised yet: stay null, callers fail soft.
        }
    }

    public static boolean isLive(VkDevice dev) {
        return dev != null;
    }

    public static long currentGeneration() {
        return generation.get();
    }

    public static boolean isStaleGeneration(long gen) {
        return gen != currentGeneration();
    }

    public static void waitIdle() {
        if (device != null) {
            org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(device);
        }
    }

    public static void destroy() {
        device = null;
        // getDevice() re-adopts VulkanMod's next device; the generation bump marks old modules stale.
        graphicsQueue = null;
        physicalDevice = null;
        lastObservedDeviceAddress = 0L;
        generation.incrementAndGet();
    }

    // Helper for common VulkanMod resource allocation
    public static long createBuffer(long size, int usage, int memoryProperties) {
        // ponytail: VulkanMod usually provides its own buffer management.
        // If not, we perform the standard VK sequence.
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferCreateInfo bufferInfo = org.lwjgl.vulkan.VkBufferCreateInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                    .size(size)
                    .usage(usage)
                    .sharingMode(org.lwjgl.vulkan.VK10.VK_SHARING_MODE_EXCLUSIVE);

            java.nio.LongBuffer pBuffer = stack.mallocLong(1);
            if (org.lwjgl.vulkan.VK10.vkCreateBuffer(getDevice(), bufferInfo, null, pBuffer) != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                return 0;
            }
            long buffer = pBuffer.get(0);

            org.lwjgl.vulkan.VkMemoryRequirements memReqs = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
            org.lwjgl.vulkan.VK10.vkGetBufferMemoryRequirements(getDevice(), buffer, memReqs);

            org.lwjgl.vulkan.VkMemoryAllocateInfo allocInfo = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(findMemoryType(getPhysicalDevice(), memReqs.memoryTypeBits(), memoryProperties));

            java.nio.LongBuffer pMemory = stack.mallocLong(1);
            if (org.lwjgl.vulkan.VK10.vkAllocateMemory(getDevice(), allocInfo, null, pMemory) != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                return 0;
            }
            long memory = pMemory.get(0);
            org.lwjgl.vulkan.VK10.vkBindBufferMemory(getDevice(), buffer, memory, 0);

            return buffer;
        }
    }

    /**
     * Origin: allocates device-local memory for {@code image} and binds it. Upstream created render-target
     * images without any memory and then made views of them, which is invalid Vulkan: lavapipe tolerated
     * it, AMD's driver crashed in vkCreateImageView (EXCEPTION_ACCESS_VIOLATION reading 0x8) the moment a
     * pack loaded. Returns the memory handle, or 0 when nothing could be bound (the caller must then drop
     * the image).
     */
    public static long allocateAndBindImageMemory(long image) {
        if (image == 0L || !isLive(getDevice())) {
            return 0L;
        }
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkMemoryRequirements memReqs = org.lwjgl.vulkan.VkMemoryRequirements.malloc(stack);
            org.lwjgl.vulkan.VK10.vkGetImageMemoryRequirements(getDevice(), image, memReqs);
            int type = findMemoryType(getPhysicalDevice(), memReqs.memoryTypeBits(),
                    org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type < 0) {
                type = findMemoryType(getPhysicalDevice(), memReqs.memoryTypeBits(), 0);
            }
            if (type < 0) {
                return 0L;
            }
            org.lwjgl.vulkan.VkMemoryAllocateInfo allocInfo = org.lwjgl.vulkan.VkMemoryAllocateInfo.calloc(stack)
                    .sType(org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                    .allocationSize(memReqs.size())
                    .memoryTypeIndex(type);
            java.nio.LongBuffer pMemory = stack.mallocLong(1);
            if (org.lwjgl.vulkan.VK10.vkAllocateMemory(getDevice(), allocInfo, null, pMemory) != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                return 0L;
            }
            long memory = pMemory.get(0);
            if (org.lwjgl.vulkan.VK10.vkBindImageMemory(getDevice(), image, memory, 0) != org.lwjgl.vulkan.VK10.VK_SUCCESS) {
                org.lwjgl.vulkan.VK10.vkFreeMemory(getDevice(), memory, null);
                return 0L;
            }
            return memory;
        }
    }

    private static int findMemoryType(org.lwjgl.vulkan.VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        // Origin: stack-allocated (the upstream malloc() was never freed).
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
        org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties memProps = org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.malloc(stack);
        org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        return -1;
        }
    }
}
