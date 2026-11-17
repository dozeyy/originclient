package com.vulkairis.client.mixin.iris;

import com.vulkairis.vulkan.VulkanCapabilities;
import net.irisshaders.iris.gl.shader.StandardMacros;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

@Mixin(value = StandardMacros.class, remap = false)
public class StandardMacrosMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/StandardMacrosMixin");

    /**
     * @author Vulkairis
     * @reason Return safe OpenGL/GLSL version numbers under Vulkan without calling raw OpenGL.
     */
    @Overwrite
    public static String getGlVersion(int name) {
        // Iris standard macros format: "460" for OpenGL 4.6.0
        return "460";
    }

    /**
     * @author Vulkairis
     * @reason Return safe extension set under Vulkan without calling raw GL30C.glGetInteger.
     */
    @Overwrite
    public static Set<String> getGlExtensions() {
        return Collections.emptySet();
    }

    /**
     * @author Vulkairis
     * @reason Determine vendor from Vulkan physical device rather than raw OpenGL.
     */
    @Overwrite
    public static String getVendor() {
        try {
            VulkanCapabilities caps = VulkanCapabilities.getActiveCapabilities();
            String vendor = caps.getVendorName().toLowerCase(Locale.ROOT);
            if (vendor.contains("nvidia")) return "MC_GL_VENDOR_NVIDIA";
            if (vendor.contains("amd") || vendor.contains("ati") || vendor.contains("radeon")) return "MC_GL_VENDOR_AMD";
            if (vendor.contains("intel")) return "MC_GL_VENDOR_INTEL";
            if (vendor.contains("apple")) return "MC_GL_VENDOR_APPLE";
            if (vendor.contains("mesa")) return "MC_GL_VENDOR_MESA";
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Failed to query Vulkan vendor, defaulting to OTHER", t);
        }
        return "MC_GL_VENDOR_OTHER";
    }

    /**
     * @author Vulkairis
     * @reason Determine renderer from Vulkan physical device rather than raw OpenGL.
     */
    @Overwrite
    public static String getRenderer() {
        try {
            VulkanCapabilities caps = VulkanCapabilities.getActiveCapabilities();
            String renderer = caps.getDeviceName().toLowerCase(Locale.ROOT);
            if (renderer.contains("geforce") || renderer.contains("nvidia")) return "MC_GL_RENDERER_GEFORCE";
            if (renderer.contains("quadro")) return "MC_GL_RENDERER_QUADRO";
            if (renderer.contains("radeon") || renderer.contains("amd")) return "MC_GL_RENDERER_RADEON";
            if (renderer.contains("intel")) return "MC_GL_RENDERER_INTEL";
            if (renderer.contains("gallium")) return "MC_GL_RENDERER_GALLIUM";
            if (renderer.contains("mesa")) return "MC_GL_RENDERER_MESA";
            if (renderer.contains("apple")) return "MC_GL_RENDERER_APPLE";
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Failed to query Vulkan renderer, defaulting to OTHER", t);
        }
        return "MC_GL_RENDERER_OTHER";
    }
}
