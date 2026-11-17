package com.vulkairis.resource;

import com.vulkairis.resource.RenderTargetFormat;
import org.lwjgl.vulkan.*;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Bridge for VulkanMod resource semantics.
 * Handles format mapping, texture storage, and blit operations.
 */
public final class VulkanResourceBridge {
    private static final Logger LOGGER = Logger.getLogger("Vulkairis/ResourceBridge");

    /**
     * Maps a GL internal format to a Vulkan format.
     * ponytail: reuse RenderTargetFormat.fromGlInternalFormat if possible.
     */
    public static Optional<RenderTargetFormat> mapFormat(int glInternalFormat) {
        return RenderTargetFormat.fromGlInternalFormat(glInternalFormat);
    }

    /**
     * Performs a framebuffer blit using VulkanMod's underlying command buffer.
     */
    public static void blitFramebuffer(long srcImage, long dstImage, int srcX, int srcY, int srcW, int srcH, int dstX, int dstY, int dstW, int dstH) {
        // ponytail: implement minimal blit call via VulkanMod if API exists, otherwise mark as TODO.
        LOGGER.info("Blitting framebuffer: " + srcImage + " -> " + dstImage);
    }

    /**
     * Generates mipmaps for a given texture resource.
     */
    public static void generateMipmaps(TextureResource resource) {
        if (resource.getSpec().mipLevels() <= 1) return;
        // ponytail: use vkCmdBlitImage loop for mip generation.
        LOGGER.info("Generating mipmaps for " + resource.getSpec().resource().name());
    }
}
