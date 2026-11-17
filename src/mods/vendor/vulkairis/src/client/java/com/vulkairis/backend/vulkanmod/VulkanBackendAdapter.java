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

    public static VkDevice getDevice() { return device; }
    public static VkQueue getQueue() { return graphicsQueue; }
    public static VkPhysicalDevice getPhysicalDevice() { return physicalDevice; }

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

    private static int findMemoryType(org.lwjgl.vulkan.VkPhysicalDevice physicalDevice, int typeFilter, int properties) {
        org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties memProps = org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.malloc();
        org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0 && (memProps.memoryTypes(i).propertyFlags() & properties) == properties) {
                return i;
            }
        }
        return -1;
    }
}
