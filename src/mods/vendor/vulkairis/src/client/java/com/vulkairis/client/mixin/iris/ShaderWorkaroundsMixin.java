package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.gl.shader.ShaderWorkarounds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = ShaderWorkarounds.class, remap = false)
public class ShaderWorkaroundsMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/ShaderWorkaroundsMixin");

    /**
     * @author Vulkairis
     * @reason Safe no-op for Iris safeShaderSource to prevent raw LWJGL GL20C crashes when running Vulkan.
     */
    @Overwrite
    public static void safeShaderSource(int shader, CharSequence source) {
        // Intercepted: raw GL20C.nglShaderSource call bypassed
        VULKAIRIS_LOGGER.debug("[Vulkairis] Bypassed raw GL20C shader source upload for shader handle {}", shader);
    }
}
