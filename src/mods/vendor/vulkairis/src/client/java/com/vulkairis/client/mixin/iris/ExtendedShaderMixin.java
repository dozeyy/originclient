package com.vulkairis.client.mixin.iris;

import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Mixin into Iris's ExtendedShader to prevent NullPointerException when programs are null
 * (since VulkanMod redirects Program compilation).
 */
@Mixin(value = ExtendedShader.class, remap = false)
public class ExtendedShaderMixin {
    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; avoid calling getVertexProgram().getId() when programs are null.
     */
    @Overwrite
    private void setupDebugNames(String name) {
        // Safe no-op under Vulkan: OpenGL debug labels do not exist and programs are virtual
    }
}
