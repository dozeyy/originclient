package com.vulkairis.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Utility wrapper for Vulkan synchronization primitives: fences, semaphores, and memory barriers.
 */
public class VulkanSyncPrimitives {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Sync");

    public static long createFence(boolean signaled) {
        VkDevice device = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice();
        if (!com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(device)) return 0L;


        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFenceCreateInfo createInfo = VkFenceCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(signaled ? VK10.VK_FENCE_CREATE_SIGNALED_BIT : 0);

            LongBuffer pFence = stack.mallocLong(1);
            int res = VK10.vkCreateFence(device, createInfo, null, pFence);
            if (res != VK10.VK_SUCCESS) {
                LOGGER.error("[Vulkairis] Failed to create fence: {}", res);
                return 0L;
            }
            return pFence.get(0);
        }
    }

    public static void waitForFence(long fence, long timeoutNs) {
        VkDevice device = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice();
        if (!com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(device) || fence == 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFence = stack.longs(fence);
            VK10.vkWaitForFences(device, pFence, true, timeoutNs);
        }
    }

    public static void resetFence(long fence) {
        VkDevice device = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice();
        if (!com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(device) || fence == 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pFence = stack.longs(fence);
            VK10.vkResetFences(device, pFence);
        }
    }

    public static void destroyFence(long fence) {
        VkDevice device = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice();
        if (com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(device) && fence != 0L) {
            VK10.vkDestroyFence(device, fence, null);
        }
    }

    public static long createSemaphore() {
        VkDevice device = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice();
        if (!com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(device)) return 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            LongBuffer pSem = stack.mallocLong(1);
            int res = VK10.vkCreateSemaphore(device, createInfo, null, pSem);
            if (res != VK10.VK_SUCCESS) {
                LOGGER.error("[Vulkairis] Failed to create semaphore: {}", res);
                return 0L;
            }
            return pSem.get(0);
        }
    }

    public static void destroySemaphore(long semaphore) {
        VkDevice device = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice();
        if (com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(device) && semaphore != 0L) {
            VK10.vkDestroySemaphore(device, semaphore, null);
        }
    }

    public static void pipelineImageBarrier(
            VkCommandBuffer cmdBuffer,
            long image,
            int oldLayout,
            int newLayout,
            int srcAccessMask,
            int dstAccessMask,
            int srcStageMask,
            int dstStageMask,
            int aspectMask
    ) {
        if (cmdBuffer == null || image == 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                    .image(image)
                    .srcAccessMask(srcAccessMask)
                    .dstAccessMask(dstAccessMask);

            barrier.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            VK10.vkCmdPipelineBarrier(
                    cmdBuffer,
                    srcStageMask,
                    dstStageMask,
                    0,
                    null,
                    null,
                    barrier
            );
        }
    }
}
