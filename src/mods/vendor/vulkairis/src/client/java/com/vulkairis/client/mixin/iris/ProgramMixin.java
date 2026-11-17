package com.vulkairis.client.mixin.iris;

import com.vulkairis.resource.VulkairisResourceManager;
import com.vulkairis.translation.VulkanShaderRegistry;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.program.Program;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin into Iris Program to safely handle destroyInternal().
 *
 * In vanilla Iris, Program.destroyInternal() calls getGlId() (which asserts validity)
 * and then calls GlStateManager.glDeleteProgram(). Under Vulkairis, the "program id"
 * is a virtual handle, so we redirect cleanup to VulkanShaderRegistry instead.
 */
@Mixin(value = Program.class, remap = false)
public abstract class ProgramMixin extends GlResource {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LoggerFactory.getLogger("Vulkairis/ProgramMixin");

    protected ProgramMixin(int id) {
        super(id);
    }

    /**
     * @author Vulkairis
     * @reason Redirect program deletion from raw OpenGL glDeleteProgram to
     * VulkanShaderRegistry cleanup. getGlId() is now safe via GlResourceMixin.
     */
    @Overwrite
    public void destroyInternal() {
        int progId = this.getGlId();
        VULKAIRIS_LOGGER.debug("[Vulkairis] Program.destroyInternal() for virtual program id={}", progId);
        VulkanShaderRegistry.deleteProgram(progId);
    }
}
