package com.vulkairis.framebuffer;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import com.vulkairis.vulkan.VulkanSyncPrimitives;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Encapsulates a Vulkan image allocation, dimensions, format, and layout transitions.
 */
public class VulkanImageResource {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ImageResource");

    private final int width;
    private final int height;
    private final int format;
    private long imageHandle = 0L;
    private long memoryHandle = 0L;
    private int currentLayout = VK10.VK_IMAGE_LAYOUT_UNDEFINED;

    public VulkanImageResource(int width, int height, int format, int usage) {
        this.width = width;
        this.height = height;
        this.format = format;

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                    .imageType(VK10.VK_IMAGE_TYPE_2D)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .format(format)
                    .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                    .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                    .usage(usage)
                    .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                    .samples(VK10.VK_SAMPLE_COUNT_1_BIT);

            LongBuffer pImage = stack.mallocLong(1);
            int res = VK10.vkCreateImage(device, imageInfo, null, pImage);
            if (res == VK10.VK_SUCCESS) {
                this.imageHandle = pImage.get(0);
                LOGGER.debug("[Vulkairis/Image] Allocated VkImage 0x{} ({}x{}, format: {})",
                        Long.toHexString(imageHandle), width, height, format);
            }
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/Image] Error creating image: {}", t.getMessage());
        }
    }

    public void transitionLayout(VkCommandBuffer cmd, int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage, int aspect) {
        if (cmd == null || imageHandle == 0L || currentLayout == newLayout) return;

        VulkanSyncPrimitives.pipelineImageBarrier(cmd, imageHandle, currentLayout, newLayout, srcAccess, dstAccess, srcStage, dstStage, aspect);
        this.currentLayout = newLayout;
    }

    public long getImageHandle() {
        return imageHandle;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFormat() {
        return format;
    }

    public int getCurrentLayout() {
        return currentLayout;
    }

    public void destroy() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device)) {
            if (imageHandle != 0L) {
                VK10.vkDestroyImage(device, imageHandle, null);
                imageHandle = 0L;
            }
            if (memoryHandle != 0L) {
                VK10.vkFreeMemory(device, memoryHandle, null);
                memoryHandle = 0L;
            }
        }
    }
}
