package com.vulkairis.client.mixin.vulkan;

import net.vulkanmod.gl.GlUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = GlUtil.class, remap = false)
public class GlUtilMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/GlUtilMixin");

    /**
     * @author Vulkairis
     * @reason Support Vulkan depth/stencil formats (including 129: VK_FORMAT_D24_UNORM_S8_UINT)
     *         and avoid throwing IllegalStateException when Iris queries texture formats.
     */
    @Overwrite
    public static int getGlFormat(int vkFormat) {
        return switch (vkFormat) {
            case 9 -> 6403;   // VK_FORMAT_R8_UNORM -> GL_RED
            case 16 -> 33319; // VK_FORMAT_R8G8_UNORM -> GL_RG
            case 37 -> 6408;  // VK_FORMAT_R8G8B8A8_UNORM -> GL_RGBA
            case 44 -> 32993; // VK_FORMAT_B8G8R8A8_UNORM -> GL_BGRA
            case 124 -> 6402; // VK_FORMAT_D16_UNORM -> GL_DEPTH_COMPONENT
            case 125 -> 35056; // VK_FORMAT_X8_D24_UNORM_PACK32 -> GL_DEPTH24_STENCIL8
            case 126 -> 36012; // VK_FORMAT_D32_SFLOAT -> GL_DEPTH_COMPONENT32F
            case 129 -> 35056; // VK_FORMAT_D24_UNORM_S8_UINT -> GL_DEPTH24_STENCIL8
            case 130 -> 36013; // VK_FORMAT_D32_SFLOAT_S8_UINT -> GL_DEPTH32F_STENCIL8
            default -> {
                VULKAIRIS_LOGGER.warn("[Vulkairis] Unknown VkFormat {} queried in getGlFormat, defaulting to GL_RGBA", vkFormat);
                yield 6408; // Fallback to GL_RGBA instead of crashing
            }
        };
    }
}
