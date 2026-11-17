package com.vulkairis.bridge;

import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges Minecraft and Iris texture identifiers to underlying VulkanImage handles.
 */
public class TextureBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/TextureBridge");
    private static VulkanImage whiteTexture = null;

    public static VulkanImage getVulkanImage(int textureId) {
        if (textureId > 0) {
            try {
                VkGlTexture glTexture = VkGlTexture.getTexture(textureId);
                if (glTexture != null) {
                    return glTexture.getVulkanImage();
                }
            } catch (Throwable t) {
                LOGGER.debug("[Vulkairis/Texture] Notice retrieving VkGlTexture {}: {}", textureId, t.getMessage());
            }
        }

        return getFallbackWhiteTexture();
    }

    public static synchronized VulkanImage getFallbackWhiteTexture() {
        if (whiteTexture == null) {
            try {
                whiteTexture = VulkanImage.createWhiteTexture();
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis/Texture] Could not create fallback white texture: {}", t.getMessage());
            }
        }
        return whiteTexture;
    }

    public static synchronized void destroy() {
        if (whiteTexture != null) {
            try {
                whiteTexture.free();
            } catch (Throwable ignored) {
            }
            whiteTexture = null;
        }
    }
}
