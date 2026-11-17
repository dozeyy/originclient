package com.vulkairis.client.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.vulkairis.resource.VulkairisResourceManager;
import com.vulkairis.vulkan.VulkanCapabilities;
import com.vulkairis.translation.VulkanShaderRegistry;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlRenderbuffer;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.VRenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/GlStateManagerMixin");

    static {
        com.vulkairis.iris.StateUpdateNotifierManager.ensureInitialized();
    }

    /**
     * @author Vulkairis
     * @reason Redirect glActiveTexture to VulkanMod's VkGlTexture when within the supported 0-11 unit range,
     * virtualizing units >= 12 to avoid ArrayIndexOutOfBoundsException or out-of-range error logs.
     */
    @Overwrite
    public static void glActiveTexture(int texture) {
        java.util.OptionalInt unit =
                com.vulkairis.rendering.state.TextureUnitPolicy.toUnit(texture);
        if (unit.isPresent()) {
            VkGlTexture.activeTexture(texture);
        } else {
            VULKAIRIS_LOGGER.debug("[Vulkairis] Virtualizing glActiveTexture({}) outside supported range 0..{}",
                    texture, com.vulkairis.rendering.state.TextureUnitPolicy.MAX_SUPPORTED_UNIT);
        }
    }

    /**
     * @author Vulkairis
     * @reason Redirect glBlendFuncSeparate to VulkanMod's VRenderSystem to prevent raw GL14 access violation.
     */
    @Overwrite
    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        VRenderSystem.blendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
    }

    /**
     * @author Vulkairis
     * @reason Intercept _getString to safely report Vulkan info without invoking raw GL11.glGetString.
     */
    @Overwrite
    public static String _getString(int name) {
        String deviceName = "Vulkan Device";
        try {
            deviceName = VulkanCapabilities.getActiveCapabilities().getDeviceName();
        } catch (Throwable ignored) {}

        return switch (name) {
            case 7936 -> "VulkanMod"; // GL_VENDOR
            case 7937 -> deviceName;  // GL_RENDERER
            case 7938 -> "4.6.0 VulkanMod"; // GL_VERSION
            case 35724 -> "4.60 VulkanMod"; // GL_SHADING_LANGUAGE_VERSION
            case 7939 -> ""; // GL_EXTENSIONS
            default -> "Vulkan";
        };
    }

    /**
     * @author Vulkairis
     * @reason Intercept _getInteger to return safe device capabilities without calling raw GL11.glGetInteger.
     */
    @Overwrite
    public static int _getInteger(int name) {
        return switch (name) {
            case 33309 -> 0;     // GL_NUM_EXTENSIONS
            case 34047 -> 32;    // GL_MAX_TEXTURE_IMAGE_UNITS
            case 34930 -> 32;    // GL_MAX_TEXTURE_COORDS
            case 34921 -> 16;    // GL_MAX_VERTEX_ATTRIBS
            case 3379 -> 16384;  // GL_MAX_TEXTURE_SIZE
            case 34045 -> 16384; // GL_MAX_CUBE_MAP_TEXTURE_SIZE
            case 34852 -> 16;    // GL_MAX_DRAW_BUFFERS
            case 36063 -> 16;    // GL_MAX_COLOR_ATTACHMENTS
            case 34024 -> 16384; // GL_MAX_RENDERBUFFER_SIZE
            case 3386 -> 16384;  // GL_MAX_VIEWPORT_DIMS
            case 35660 -> 128;   // GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS
            case 35661 -> 32;    // GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS
            case 35374 -> 36;    // GL_MAX_UNIFORM_BUFFER_BINDINGS
            case 35375 -> 65536; // GL_MAX_UNIFORM_BLOCK_SIZE
            case 37085 -> 16;    // GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS
            case 37086 -> 134217728; // GL_MAX_SHADER_STORAGE_BLOCK_SIZE
            case 37087 -> 16;    // GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS
            case 37088 -> 64;    // GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS
            case 37099 -> 1024;  // GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS
            case 36664 -> 16;    // GL_MAX_IMAGE_UNITS
            case 32873 -> 0;     // GL_TEXTURE_BINDING_2D
            default -> 0;
        };
    }

    /**
     * @author Vulkairis
     * @reason Return GL_NO_ERROR (0) without calling raw GL11.glGetError.
     * Note: VulkanMod already overwrites this method; we deliberately do not, to avoid a
     * method-overwrite conflict that would mask VulkanMod's handling.
     */
    // _getError() intentionally not overwritten here.

    /**
     * @author Vulkairis
     * @reason Intercept _genTextures to allocate virtual texture IDs via VulkanMod.
     */
    @Overwrite
    public static void _genTextures(int[] textures) {
        if (textures != null) {
            for (int i = 0; i < textures.length; i++) {
                textures[i] = VkGlTexture.genTextureId();
            }
        }
    }

    /**
     * @author Vulkairis
     * @reason Intercept _deleteTextures to clean up virtual texture IDs via VulkanMod.
     */
    @Overwrite
    public static void _deleteTextures(int[] textures) {
        if (textures != null) {
            for (int id : textures) {
                VkGlTexture.glDeleteTextures(id);
            }
        }
    }

    /**
     * @author Vulkairis
     * @reason Intercept _glDeleteFramebuffers to clean up virtual framebuffer via VulkanMod.
     */
    @Overwrite
    public static void _glDeleteFramebuffers(int framebuffer) {
        VkGlFramebuffer.deleteFramebuffer(framebuffer);
    }

    /**
     * @author Vulkairis
     * @reason Intercept _glDeleteRenderbuffers to clean up virtual renderbuffer via VulkanMod.
     */
    @Overwrite
    public static void _glDeleteRenderbuffers(int renderbuffer) {
        VkGlRenderbuffer.deleteRenderbuffer(renderbuffer);
    }

    /**
     * @author Vulkairis
     * @reason Intercept _glBlitFrameBuffer to execute via VulkanMod.
     */
    @Overwrite
    public static void _glBlitFrameBuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        VkGlFramebuffer.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    /**
     * @author Vulkairis
     * @reason Validate framebuffer attachments instead of reporting fabricated completeness.
     * Framebuffers with tracked attachments get a real verdict (missing/broken attachments
     * report the GL incomplete codes); untracked ids are foreign VulkanMod/vanilla objects
     * and keep the legacy COMPLETE answer so their paths keep working.
     *
     * <p>Phase 5/8 contract. Test: {@code FramebufferValidatorTest} (pure) — the mixin stays a
     * thin branch. Fallback: incomplete codes with no crash.</p>
     */
    @Overwrite
    public static int glCheckFramebufferStatus(int target) {
        try {
            java.util.Map<Integer, com.vulkairis.rendering.state.ResourceBindings.FramebufferAttachment> attachments =
                    com.vulkairis.rendering.state.VulkairisRenderState.get()
                            .bindings().getAttachments(target);
            if (attachments.isEmpty()
                    && com.vulkairis.rendering.state.VulkairisRenderState.get()
                            .bindings().getDrawBuffers(target).length == 0) {
                return 36053; // Foreign framebuffer: legacy answer preserved.
            }
            return com.vulkairis.rendering.FramebufferValidator.validate(target, attachments)
                    .glStatus();
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Framebuffer status check notice: {}", t.getMessage());
            return 36053;
        }
    }

    /**
     * @author Vulkairis
     * @reason Intercept getBoundFramebuffer to retrieve VulkanMod bound framebuffer ID.
     */
    @Overwrite
    public static int getBoundFramebuffer() {
        VkGlFramebuffer bound = VkGlFramebuffer.getBoundFramebuffer();
        return bound != null ? bound.id : 0;
    }

    /**
     * @author Vulkairis
     * @reason Check if the shader program actually declares the uniform, backed by SPIR-V
     * reflection instead of source-text scanning (a word inside a comment or a similarly named
     * identifier never counts). Missing uniforms yield -1; deleted/unknown programs yield -1.
     *
     * <p>Phase 5 contract (pinned target: Iris 1.8.14 {@code ProgramUniforms$Builder}, the only
     * live caller on the supported path): locations are stable per program/name and resolve
     * through {@link com.vulkairis.translation.VirtualGlFacade}. Test:
     * {@code VirtualGlStatusTest}. Fallback: -1, which makes Iris skip the uniform.</p>
     */
    @Overwrite
    public static int _glGetUniformLocation(int program, CharSequence name) {
        return com.vulkairis.translation.VirtualGlFacade.getUniformLocation(program, name);
    }

    /**
     * @author Vulkairis
     * @reason Prevent raw OpenGL glGetAttribLocation call; resolve against reflected vertex
     * attributes (exact, then case-insensitive) instead of fuzzy substring guessing, with -1 for
     * unknown names. Deterministic across reloads; no ad-hoc id allocation.
     *
     * <p>Phase 5 contract (pinned target: Iris/Sodium attribute queries on the supported path).
     * Test: {@code VirtualGlStatusTest}. Fallback: -1, which makes the caller skip the
     * attribute.</p>
     */
    @Overwrite
    public static int _glGetAttribLocation(int program, CharSequence name) {
        return com.vulkairis.translation.VirtualGlFacade.getAttribLocation(program, name);
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; layout locations are defined statically in SPIR-V.
     */
    @Overwrite
    public static void _glBindAttribLocation(int program, int index, CharSequence name) {}

    /**
     * @author Vulkairis
     * @reason Return real program status values without calling raw OpenGL glGetProgrami.
     * LINK_STATUS is true only for explicitly linked programs (never simulated); counts come
     * from reflection; unknown ids keep the legacy constants so foreign paths keep working.
     *
     * <p>Phase 5 contract (pinned targets: Iris 1.8.14 {@code ProgramCreator.create} and
     * {@code ProgramUniforms$Builder}, the live query sites on the supported path). Test:
     * {@code VirtualGlStatusTest}. Fallback: failed/deleted programs report 0/empty.</p>
     */
    @Overwrite
    public static int glGetProgrami(int program, int pname) {
        return com.vulkairis.translation.VirtualGlFacade.getProgrami(program, pname);
    }

    /**
     * @author Vulkairis
     * @reason Return real shader compile status without raw OpenGL. COMPILE_STATUS is true only
     * for shaders holding validated bytecode plus complete reflection; deleted/failed shaders
     * report failure with their retained log. Closes the raw-GL escape for any live caller
     * outside the intercepted {@code createShader} path.
     *
     * <p>Phase 5 contract. Test: {@code VirtualGlStatusTest}. Fallback: unknown ids keep the
     * legacy success assumption so foreign-managed objects keep working.</p>
     */
    @Overwrite
    public static int glGetShaderi(int shader, int pname) {
        return com.vulkairis.translation.VirtualGlFacade.getShaderi(shader, pname);
    }

    /**
     * @author Vulkairis
     * @reason Safe no-op under Vulkan; pipelines are linked through VulkanShaderRegistry.
     */
    @Overwrite
    public static void glLinkProgram(int program) {}

    /**
     * @author Vulkairis
     * @reason Return the retained program info log without raw OpenGL calls ("" when linked
     * clean, precise problems after a failed link, deletion note for tombstones).
     */
    @Overwrite
    public static String glGetProgramInfoLog(int program, int maxLength) {
        return com.vulkairis.translation.VirtualGlFacade.getProgramInfoLog(program);
    }

    /**
     * @author Vulkairis
     * @reason Redirect glDeleteProgram to VulkanShaderRegistry and VulkairisResourceManager instead of raw GL20.glDeleteProgram.
     */
    @Overwrite
    public static void glDeleteProgram(int program) {
        VULKAIRIS_LOGGER.debug("[Vulkairis] GlStateManager.glDeleteProgram({}) redirected to Vulkan registry", program);
        VulkanShaderRegistry.deleteProgram(program);
        VulkairisResourceManager.getInstance().releaseResource(program);
    }

    /**
     * @author Vulkairis
     * @reason Redirect glDeleteShader to VulkanShaderRegistry and VulkairisResourceManager instead of raw GL20.glDeleteShader.
     */
    @Overwrite
    public static void glDeleteShader(int shader) {
        VULKAIRIS_LOGGER.debug("[Vulkairis] GlStateManager.glDeleteShader({}) redirected to Vulkan registry", shader);
        VulkanShaderRegistry.deleteShader(shader);
        VulkairisResourceManager.getInstance().releaseResource(shader);
    }

    @Overwrite
    public static void _glUniform1i(int location, int value) {}

    @Overwrite
    public static void _glUniform1(int location, IntBuffer value) {}

    @Overwrite
    public static void _glUniform1(int location, FloatBuffer value) {}

    @Overwrite
    public static void _glUniform2(int location, IntBuffer value) {}

    @Overwrite
    public static void _glUniform2(int location, FloatBuffer value) {}

    @Overwrite
    public static void _glUniform3(int location, IntBuffer value) {}

    @Overwrite
    public static void _glUniform3(int location, FloatBuffer value) {}

    @Overwrite
    public static void _glUniform4(int location, IntBuffer value) {}

    @Overwrite
    public static void _glUniform4(int location, FloatBuffer value) {}

    @Overwrite
    public static void _glUniformMatrix2(int location, boolean transpose, FloatBuffer value) {}

    @Overwrite
    public static void _glUniformMatrix3(int location, boolean transpose, FloatBuffer value) {}

    @Overwrite
    public static void _glUniformMatrix4(int location, boolean transpose, FloatBuffer value) {}

    @Overwrite
    public static void _vertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {}

    @Overwrite
    public static void _vertexAttribIPointer(int index, int size, int type, int stride, long pointer) {}

    @Overwrite
    public static void _enableVertexAttribArray(int index) {}
}
