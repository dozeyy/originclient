package com.vulkairis.framebuffer;

import com.vulkairis.vulkan.VulkanCapabilities;
import org.lwjgl.vulkan.VK10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages color and depth targets, resolution, and framebuffer image views.
 */
public class VulkanFramebuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Framebuffer");

    private int width;
    private int height;
    private VulkanImageResource colorImage;
    private VulkanImageView colorImageView;
    private VulkanImageResource depthImage;
    private VulkanImageView depthImageView;

    public VulkanFramebuffer(int width, int height) {
        this.width = width;
        this.height = height;
        allocateTargets();
    }

    private void allocateTargets() {
        try {
            // Color target: standard RGBA8
            int colorUsage = VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
            colorImage = new VulkanImageResource(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, colorUsage);
            if (colorImage.getImageHandle() != 0L) {
                colorImageView = new VulkanImageView(colorImage.getImageHandle(), VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_IMAGE_ASPECT_COLOR_BIT);
            }

            // Depth target: D32_SFLOAT
            int depthUsage = VK10.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
            depthImage = new VulkanImageResource(width, height, VK10.VK_FORMAT_D32_SFLOAT, depthUsage);
            if (depthImage.getImageHandle() != 0L) {
                depthImageView = new VulkanImageView(depthImage.getImageHandle(), VK10.VK_FORMAT_D32_SFLOAT, VK10.VK_IMAGE_ASPECT_DEPTH_BIT);
            }

            LOGGER.info("[Vulkairis/Framebuffer] Allocated framebuffer targets ({}x{})", width, height);
        } catch (Throwable t) {
            LOGGER.error("[Vulkairis/Framebuffer] Error allocating framebuffer targets: {}", t.getMessage());
        }
    }

    public void resize(int newWidth, int newHeight) {
        if (this.width == newWidth && this.height == newHeight) return;

        LOGGER.info("[Vulkairis/Framebuffer] Resizing framebuffer from {}x{} to {}x{}",
                width, height, newWidth, newHeight);
        destroy();
        this.width = newWidth;
        this.height = newHeight;
        allocateTargets();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public VulkanImageView getColorImageView() {
        return colorImageView;
    }

    public VulkanImageView getDepthImageView() {
        return depthImageView;
    }

    public void destroy() {
        if (colorImageView != null) colorImageView.destroy();
        if (colorImage != null) colorImage.destroy();
        if (depthImageView != null) depthImageView.destroy();
        if (depthImage != null) depthImage.destroy();
        colorImageView = null;
        colorImage = null;
        depthImageView = null;
        depthImage = null;
    }
}
