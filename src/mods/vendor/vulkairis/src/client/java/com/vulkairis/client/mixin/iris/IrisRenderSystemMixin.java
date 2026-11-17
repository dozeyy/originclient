package com.vulkairis.client.mixin.iris;

import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.rendering.state.VulkairisRenderState;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.SamplerLimits;
import net.vulkanmod.gl.VkGlBuffer;
import net.vulkanmod.gl.VkGlFramebuffer;
import net.vulkanmod.gl.VkGlTexture;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(value = IrisRenderSystem.class, remap = false)
public class IrisRenderSystemMixin {
    @Unique
    private static final Logger VULKAIRIS_LOGGER = LogManager.getLogger("Vulkairis/IrisRenderSystemMixin");

    /** One-time notices so an unimplemented GPU path is reported without per-frame log spam. */
    @Unique
    private static final AtomicBoolean VULKAIRIS_COMPUTE_NOTICE = new AtomicBoolean(false);
    @Unique
    private static final AtomicBoolean VULKAIRIS_BLIT_NOTICE = new AtomicBoolean(false);

    @Shadow
    private static IrisRenderSystem.DSAAccess dsaState;
    @Shadow
    private static boolean hasMultibind;
    @Shadow
    private static boolean supportsCompute;
    @Shadow
    private static boolean supportsTesselation;
    @Shadow
    private static int[] samplers;
    @Shadow
    private static int polygonMode;
    @Shadow
    private static int backupPolygonMode;
    @Shadow
    private static boolean cullingState;

    private static VulkairisRenderState state() {
        return VulkairisRenderState.get();
    }

    @Unique
    private static final IrisRenderSystem.DSAAccess VULKAN_DSA = new IrisRenderSystem.DSAAccess() {
        @Override
        public void generateMipmaps(int texture, int target) {}
        @Override
        public void texParameteri(int texture, int target, int pname, int param) {
            state().bindings().setTextureParameter(texture, pname, param);
        }
        @Override
        public void texParameterf(int texture, int target, int pname, float param) {
            state().bindings().setTextureParameter(texture, pname, (int) param);
        }
        @Override
        public void texParameteriv(int texture, int target, int pname, int[] params) {
            if (params != null && params.length > 0) {
                state().bindings().setTextureParameter(texture, pname, params[0]);
            }
        }
        @Override
        public void readBuffer(int framebuffer, int mode) {
            state().bindings().setReadBuffer(framebuffer, mode);
        }
        @Override
        public void drawBuffers(int framebuffer, int[] buffers) {
            state().bindings().setDrawBuffers(framebuffer, buffers);
        }
        @Override
        public int getTexParameteri(int texture, int target, int pname) {
            return state().bindings().getTextureParameter(texture, pname).orElse(0);
        }
        @Override
        public void copyTexSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {}
        @Override
        public void bindTextureToUnit(int unit, int texture, int target) {
            state().bindings().bindTextureToUnit(unit, texture, target);
        }
        @Override
        public int bufferStorage(int target, float[] data, int usage) { return 1; }
        @Override
        public void blitFramebuffer(int src, int dst, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
            reportBlitPending();
        }
        @Override
        public void framebufferTexture2D(int framebuffer, int target, int attachment, int textarget, int texture, int level) {
            state().bindings().attachFramebufferTexture(framebuffer, attachment, texture, level);
        }
        @Override
        public int createFramebuffer() { return VkGlFramebuffer.genFramebufferId(); }
        @Override
        public int createTexture(int target) { return VkGlTexture.genTextureId(); }
        @Override
        public int createBuffers() { return VkGlBuffer.glGenBuffers(); }
    };

    @Unique
    private static void reportComputePending() {
        if (VULKAIRIS_COMPUTE_NOTICE.compareAndSet(false, true)) {
            StageDiagnostics.unsupported("compute dispatch",
                    "dispatch is recorded in render state; GPU execution lands with the compute pipeline phase");
        }
    }

    @Unique
    private static void reportBlitPending() {
        if (VULKAIRIS_BLIT_NOTICE.compareAndSet(false, true)) {
            StageDiagnostics.unsupported("framebuffer blit",
                    "blit request recorded; GPU image-to-image blit lands with the resource layer");
        }
    }

    @Overwrite
    public static void initRenderer() {
        dsaState = VULKAN_DSA;
        hasMultibind = false;
        supportsCompute = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice());
        supportsTesselation = false;
        samplers = new int[SamplerLimits.get().getMaxTextureUnits()];
        com.vulkairis.iris.StateUpdateNotifierManager.ensureInitialized();
        // Phase D mesh contract: settle the Sodium 0.8 extended terrain vertex layout once,
        // at renderer init, so every later terrain draw keys on the same stride/offsets.
        try {
            if (com.vulkairis.vulkan.pipeline.TerrainVertexContract.settle("IrisRenderSystem.initRenderer")) {
                VULKAIRIS_LOGGER.info("[Vulkairis] Settled {}",
                        com.vulkairis.vulkan.pipeline.TerrainVertexContract.describe());
            }
        } catch (Throwable t) {
            VULKAIRIS_LOGGER.warn("[Vulkairis] Terrain vertex contract settle failed: {}", t.getMessage());
        }
        VULKAIRIS_LOGGER.info("[Vulkairis] Initialized IrisRenderSystem under Vulkan backend with complete GL bypass (compute={})", supportsCompute);
    }

    @Overwrite
    public static boolean supportsSSBO() {
        return com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice());
    }

    @Overwrite
    public static boolean supportsImageLoadStore() {
        return com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice());
    }

    @Overwrite
    public static boolean supportsCompute() {
        return com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice());
    }

    @Overwrite
    public static boolean supportsTesselation() { return false; }

    @Overwrite
    public static boolean supportsBufferBlending() { return true; }

    @Overwrite
    public static int getMaxImageUnits() { return 16; }

    @Overwrite
    public static long getVRAM() { return 4294967296L; }

    @Overwrite
    public static void bindBufferBase(int target, Integer index, int buffer) {
        if (index != null) {
            state().bindings().bindBufferBase(index, buffer);
        }
    }

    @Overwrite
    public static void bindBuffer(int target, int buffer) {}

    @Overwrite
    public static void bufferData(int target, float[] data, int usage) {}

    @Overwrite
    public static int bufferStorage(int target, float[] data, int flags) { return 1; }

    @Overwrite
    public static void bufferStorage(int target, long size, int flags) {}

    @Overwrite
    public static int createBuffers() { return VkGlBuffer.glGenBuffers(); }

    @Overwrite
    public static void genBuffers(int[] buffers) {
        if (buffers != null) {
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = VkGlBuffer.glGenBuffers();
            }
        }
    }

    @Overwrite
    public static void deleteBuffers(int buffer) {
        VkGlBuffer.glDeleteBuffers(buffer);
    }

    @Overwrite
    public static void clearBufferSubData(int target, int internalFormat, long offset, long size, int format, int type, int[] data) {}

    /**
     * @author Vulkairis
     * @reason Answer program queries from registry state plus reflection (LINK_STATUS true only
     * for explicitly linked programs; counts from reflection), never raw GL.
     *
     * <p>Phase 5 contract (pinned target: Iris 1.8.14 {@code ProgramUniforms$Builder}, which
     * queries ACTIVE_UNIFORMS). Test: {@code VirtualGlStatusTest}.</p>
     */
    @Overwrite
    public static void getProgramiv(int program, int pname, int[] params) {
        if (params != null && params.length > 0) {
            params[0] = com.vulkairis.translation.VirtualGlFacade.getProgrami(program, pname);
        }
    }

    /**
     * @author Vulkairis
     * @reason Return the retained program info log ("" when linked clean, precise problems
     * after a failed link).
     */
    @Overwrite
    public static String getProgramInfoLog(int program) {
        return com.vulkairis.translation.VirtualGlFacade.getProgramInfoLog(program);
    }

    /**
     * @author Vulkairis
     * @reason Return the retained shader info log ("" when compiled clean, compiler output
     * after a failure).
     */
    @Overwrite
    public static String getShaderInfoLog(int shader) {
        return com.vulkairis.translation.VirtualGlFacade.getShaderInfoLog(shader);
    }

    @Overwrite
    public static int getUniformBlockIndex(int program, String name) { return 0; }

    @Overwrite
    public static void uniformBlockBinding(int program, int blockIndex, int bufferIndex) {
        state().bindings().setUniformBlockBinding(program, blockIndex, bufferIndex);
    }

    @Overwrite
    public static void memoryBarrier(int barriers) {
        state().synchronization().requestMemoryBarrier(barriers);
    }

    @Overwrite
    public static void dispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        if (!state().synchronization().dispatchCompute(numGroupsX, numGroupsY, numGroupsZ)) {
            StageDiagnostics.warn("dispatchCompute",
                    "rejected out-of-limit dispatch " + numGroupsX + "x" + numGroupsY + "x" + numGroupsZ);
            return;
        }
        reportComputePending();
    }

    @Overwrite
    public static void dispatchCompute(Vector3i vector3i) {
        if (vector3i != null) {
            dispatchCompute(vector3i.x, vector3i.y, vector3i.z);
        }
    }

    @Overwrite
    public static void dispatchComputeIndirect(long indirect) {
        if (!state().synchronization().dispatchComputeIndirect(indirect)) {
            StageDiagnostics.warn("dispatchComputeIndirect", "rejected indirect dispatch at offset " + indirect);
            return;
        }
        reportComputePending();
    }

    @Overwrite
    public static void disableBufferBlend(int index) {}

    @Overwrite
    public static void enableBufferBlend(int index) {}

    @Overwrite
    public static void blendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {}

    @Overwrite
    public static int genSampler() { return 1; }

    @Overwrite
    public static void destroySampler(int sampler) {}

    @Overwrite
    public static void bindSamplerToUnit(int unit, int sampler) {
        state().bindings().bindSamplerToUnit(unit, sampler);
    }

    @Overwrite
    public static void unbindAllSamplers() {}

    @Overwrite
    public static void samplerParameteri(int sampler, int pname, int param) {
        applySamplerParameter(sampler, pname, param);
    }

    @Overwrite
    public static void samplerParameterf(int sampler, int pname, float param) {
        applySamplerParameter(sampler, pname, (int) param);
    }

    @Overwrite
    public static void samplerParameteriv(int sampler, int pname, int[] params) {
        if (params != null && params.length > 0) {
            applySamplerParameter(sampler, pname, params[0]);
        }
    }

    @Unique
    private static void applySamplerParameter(int sampler, int pname, int param) {
        // GL_TEXTURE_MAG_FILTER=0x2800, GL_TEXTURE_MIN_FILTER=0x2801,
        // GL_TEXTURE_WRAP_S=0x2802, GL_TEXTURE_WRAP_T=0x2803.
        switch (pname) {
            case 0x2800 -> state().bindings().setSamplerParameters(sampler, 0, param, 0, 0);
            case 0x2801 -> state().bindings().setSamplerParameters(sampler, param, 0, 0, 0);
            case 0x2802 -> state().bindings().setSamplerParameters(sampler, 0, 0, param, 0);
            case 0x2803 -> state().bindings().setSamplerParameters(sampler, 0, 0, 0, param);
            default -> { /* parameters shader packs do not rely on are ignored */ }
        }
    }

    @Overwrite
    public static void vertexAttrib4f(int index, float x, float y, float z, float w) {}

    @Overwrite
    public static void detachShader(int program, int shader) {}

    @Overwrite
    public static void setPolygonMode(int mode) { polygonMode = mode; }

    @Overwrite
    public static void overridePolygonMode() { backupPolygonMode = polygonMode; }

    @Overwrite
    public static void restorePolygonMode() { polygonMode = backupPolygonMode; }

    @Overwrite
    public static void uniform1f(int location, float v0) {
        state().uniforms().setFloat(location, v0);
    }

    @Overwrite
    public static void uniform2f(int location, float v0, float v1) {
        state().uniforms().setVec2(location, v0, v1);
    }

    @Overwrite
    public static void uniform2i(int location, int v0, int v1) {
        state().uniforms().setIVec2(location, v0, v1);
    }

    @Overwrite
    public static void uniform3f(int location, float v0, float v1, float v2) {
        state().uniforms().setVec3(location, v0, v1, v2);
    }

    @Overwrite
    public static void uniform3i(int location, int v0, int v1, int v2) {
        state().uniforms().setIVec3(location, v0, v1, v2);
    }

    @Overwrite
    public static void uniform4f(int location, float v0, float v1, float v2, float v3) {
        state().uniforms().setVec4(location, v0, v1, v2, v3);
    }

    @Overwrite
    public static void uniform4i(int location, int v0, int v1, int v2, int v3) {
        state().uniforms().setIVec4(location, v0, v1, v2, v3);
    }

    @Overwrite
    public static void uniformMatrix4fv(int location, boolean transpose, FloatBuffer matrices) {
        state().uniforms().setMatrix4(location, matrices);
    }

    @Overwrite
    public static void getIntegerv(int pname, int[] params) {
        if (params != null && params.length > 0) {
            params[0] = 0;
        }
    }

    @Overwrite
    public static void getFloatv(int pname, float[] params) {
        if (params != null && params.length > 0) {
            params[0] = 0.0f;
        }
    }

    @Overwrite
    public static void generateMipmaps(int texture, int target) {}

    @Overwrite
    public static void bindTextureForSetup(int target, int texture) {
        VkGlTexture.bindTexture(texture);
    }

    @Overwrite
    public static void bindTextureToUnit(int unit, int texture, int target) {
        state().bindings().bindTextureToUnit(unit, texture, target);
        if (com.vulkairis.rendering.state.TextureUnitPolicy.isSupported(unit)) {
            VkGlTexture.activeTexture(33984 + unit);
            VkGlTexture.bindTexture(texture);
        } else {
            VULKAIRIS_LOGGER.debug("[Vulkairis] Virtualizing bindTextureToUnit unit={} texture={} outside supported range 0..{}",
                    unit, texture, com.vulkairis.rendering.state.TextureUnitPolicy.MAX_SUPPORTED_UNIT);
            com.vulkairis.rendering.state.TextureUnitPolicy.noteOutOfRange(unit);
        }
    }

    @Overwrite
    public static void bindAttributeLocation(int program, int index, CharSequence name) {}

    @Overwrite
    public static void texImage1D(int texture, int target, int level, int internalformat, int width, int border, int format, int type, ByteBuffer pixels) {}

    @Overwrite
    public static void texImage2D(int texture, int target, int level, int internalformat, int width, int height, int border, int format, int type, ByteBuffer pixels) {}

    @Overwrite
    public static void texImage3D(int texture, int target, int level, int internalformat, int width, int height, int depth, int border, int format, int type, ByteBuffer pixels) {}

    @Overwrite
    public static void copyTexImage2D(int target, int level, int internalformat, int x, int y, int width, int height, int border) {}

    @Overwrite
    public static void copyTexSubImage2D(int texture, int target, int level, int xoffset, int yoffset, int x, int y, int width, int height) {}

    @Overwrite
    public static void texParameteri(int texture, int target, int pname, int param) {
        state().bindings().setTextureParameter(texture, pname, param);
    }

    @Overwrite
    public static void texParameterf(int texture, int target, int pname, float param) {
        state().bindings().setTextureParameter(texture, pname, (int) param);
    }

    @Overwrite
    public static void texParameteriv(int texture, int target, int pname, int[] params) {
        if (params != null && params.length > 0) {
            state().bindings().setTextureParameter(texture, pname, params[0]);
        }
    }

    @Overwrite
    public static void texParameterivDirect(int target, int pname, int[] params) {}

    @Overwrite
    public static void drawBuffers(int framebuffer, int[] buffers) {
        state().bindings().setDrawBuffers(framebuffer, buffers);
    }

    @Overwrite
    public static void readBuffer(int framebuffer, int mode) {
        state().bindings().setReadBuffer(framebuffer, mode);
    }

    /**
     * @author Vulkairis
     * @reason Discover active uniforms from SPIR-V reflection (deterministic order) instead of
     * claiming none exist. Length/size buffers mirror the discovery for the builder loop.
     *
     * <p>Phase 5 contract (pinned target: Iris 1.8.14 {@code ProgramUniforms$Builder}, which
     * iterates {@code 0..ACTIVE_UNIFORMS} and resolves each name). Test:
     * {@code VirtualGlStatusTest}. Fallback: "" for unknown indexes.</p>
     */
    @Overwrite
    public static String getActiveUniform(int program, int index, int maxLength, IntBuffer length, IntBuffer size) {
        com.vulkairis.translation.VirtualGlFacade.fillActiveUniformBuffers(
                program, index, maxLength, length, size);
        return com.vulkairis.translation.VirtualGlFacade.getActiveUniformAt(program, index);
    }

    @Overwrite
    public static void readPixels(int x, int y, int width, int height, int format, int type, float[] pixels) {}

    @Overwrite
    public static void framebufferTexture2D(int framebuffer, int target, int attachment, int textarget, int texture, int level) {
        state().bindings().attachFramebufferTexture(framebuffer, attachment, texture, level);
    }

    @Overwrite
    public static int getTexParameteri(int texture, int target, int pname) {
        return state().bindings().getTextureParameter(texture, pname).orElse(0);
    }

    @Overwrite
    public static void bindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        state().bindings().bindImage(unit, texture, level, layered, layer, access, format);
    }

    @Overwrite
    public static int createFramebuffer() { return VkGlFramebuffer.genFramebufferId(); }

    @Overwrite
    public static int createTexture(int target) { return VkGlTexture.genTextureId(); }

    @Overwrite
    public static void backupAndDisableCullingState(boolean culling) {
        cullingState = culling;
        com.vulkairis.rendering.RenderStateTracker.getInstance().setCulling(false);
    }

    @Overwrite
    public static void restoreCullingState() {
        com.vulkairis.rendering.RenderStateTracker.getInstance().setCulling(cullingState);
    }

    @Overwrite
    public static void setShadowProjection(Matrix4f projection) {
        state().setShadowProjection(projection);
    }

    @Overwrite
    public static void restorePlayerProjection() {
        state().restorePlayerProjection();
    }

    @Overwrite
    public static void blitFramebuffer(int src, int dst, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
        reportBlitPending();
    }
}
