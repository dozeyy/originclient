package com.vulkairis.framebuffer;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;

/**
 * Encapsulates a Vulkan VkImageView handle for rendering and sampling targets.
 */
public class VulkanImageView {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ImageView");

    private long viewHandle = 0L;

    public VulkanImageView(long image, int format, int aspectMask) {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device) || image == 0L) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);

            viewInfo.subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            int res = VK10.vkCreateImageView(device, viewInfo, null, pView);
            if (res == VK10.VK_SUCCESS) {
                this.viewHandle = pView.get(0);
                LOGGER.debug("[Vulkairis/ImageView] Created VkImageView 0x{}", Long.toHexString(viewHandle));
            }
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/ImageView] Error creating image view: {}", t.getMessage());
        }
    }

    public long getViewHandle() {
        return viewHandle;
    }

    public void destroy() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device) && viewHandle != 0L) {
            VK10.vkDestroyImageView(device, viewHandle, null);
            viewHandle = 0L;
        }
    }
}
