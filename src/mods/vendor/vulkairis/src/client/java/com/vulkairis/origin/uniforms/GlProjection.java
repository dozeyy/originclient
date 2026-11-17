package com.vulkairis.origin.uniforms;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Origin (step 3): VulkanMod forces every JOML perspective/ortho to Vulkan's 0..1 depth range
 * ({@code Matrix4fM}, zZeroToOne = true). Shader packs are written for OpenGL's -1..1 clip depth
 * (they rebuild view positions with {@code depth * 2 - 1} through {@code gbufferProjectionInverse}),
 * so the projection Iris hands the pack is converted back here: {@code z_gl = 2 * z_vk - w}.
 *
 * <p>The stored depth needs no conversion: GL window depth {@code (z_gl/w + 1) / 2} equals Vulkan's
 * {@code z_vk/w}, so a depth buffer rendered by VulkanMod already holds the values a pack expects.</p>
 */
public final class GlProjection {
    private GlProjection() {
    }

    /** {@code dest} = {@code zeroToOne} rewritten for -1..1 clip depth; returns {@code dest}. */
    public static Matrix4f toGlDepth(Matrix4fc zeroToOne, Matrix4f dest) {
        dest.set(zeroToOne);
        return dest.m02(2f * zeroToOne.m02() - zeroToOne.m03())
                .m12(2f * zeroToOne.m12() - zeroToOne.m13())
                .m22(2f * zeroToOne.m22() - zeroToOne.m23())
                .m32(2f * zeroToOne.m32() - zeroToOne.m33());
    }
}
