package com.vulkairis.bridge;

import com.vulkairis.core.RenderMatrices;
import com.vulkairis.rendering.RenderStateTracker;
import com.vulkairis.vulkan.descriptor.VulkanDescriptorManager;
import org.joml.Matrix4f;

/**
 * Maps high-level OpenGL and Blaze3D rendering concepts to Vulkairis / Vulkan equivalents.
 */
public class OpenGLBridge {
    /**
     * Correction matrix adapting OpenGL clip space Z [-1, 1] to Vulkan clip space Z [0, 1].
     *
     * @deprecated The authoritative definition now lives in
     *     {@link RenderMatrices#CLIP_SPACE_CORRECTION}. This alias is kept only for source
     *     compatibility and references the very same instance. Note that it does <em>not</em> flip
     *     Y, because {@code SpirvCompiler} already emits {@code set_invert_y(true)}; adding a Y
     *     flip here would double-invert and break orientation.
     */
    @Deprecated
    public static final Matrix4f CLIP_SPACE_CORRECTION = RenderMatrices.CLIP_SPACE_CORRECTION;

    public static void setTextureBinding(long descriptorSet, int binding, int glTextureId) {
        VulkanDescriptorManager.updateTextureBinding(descriptorSet, binding, glTextureId);
    }

    public static void updateRenderState(boolean blend, int blendOrdinal, boolean depthTest, boolean depthMask, boolean cull) {
        RenderStateTracker tracker = RenderStateTracker.getInstance();
        tracker.setBlend(blend, blendOrdinal);
        tracker.setDepth(depthTest, depthMask);
        tracker.setCulling(cull);
    }
}
