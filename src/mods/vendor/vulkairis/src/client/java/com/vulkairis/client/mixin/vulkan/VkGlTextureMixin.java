package com.vulkairis.client.mixin.vulkan;

import net.vulkanmod.gl.GlUtil;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = VkGlTexture.class, remap = false)
public class VkGlTextureMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/VkGlTextureMixin");

    @Shadow
    private static VkGlTexture boundTexture;

    @Shadow
    int width;

    @Shadow
    int height;

    @Shadow
    int vkFormat;

    /**
     * @author Vulkairis
     * @reason Prevent NullPointerException when querying texture parameters before VulkanImage is allocated.
     */
    @Overwrite
    public static int getTexLevelParameter(int target, int level, int pname) {
        if (target != 3553) {
            return -1;
        }
        if (boundTexture == null) {
            return -1;
        }

        VulkanImage img = boundTexture.getVulkanImage();
        if (img != null) {
            return switch (pname) {
                case 4096 -> img.width;
                case 4097 -> img.height;
                case 4099 -> GlUtil.getGlFormat(img.format);
                default -> -1;
            };
        }

        // Fallback when vulkanImage has not been allocated yet
        VkGlTextureMixin boundMixin = (VkGlTextureMixin)(Object)boundTexture;
        return switch (pname) {
            case 4096 -> boundMixin.width > 0 ? boundMixin.width : 1920;
            case 4097 -> boundMixin.height > 0 ? boundMixin.height : 1080;
            case 4099 -> {
                if (boundMixin.vkFormat != 0) {
                    yield GlUtil.getGlFormat(boundMixin.vkFormat);
                }
                yield 35056; // GL_DEPTH24_STENCIL8
            }
            default -> -1;
        };
    }
}
