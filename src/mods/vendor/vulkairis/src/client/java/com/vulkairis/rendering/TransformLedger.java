package com.vulkairis.rendering;

import com.vulkairis.core.RenderMatrices;
import com.vulkairis.translation.ShadercProfile;
import org.lwjgl.vulkan.VK10;

import java.util.List;

/**
 * The single transform ledger from Iris matrices to Vulkan output (Phase 9).
 *
 * <p>Every coordinate/depth/winding conversion in the bridge happens <em>exactly once</em>,
 * at the layer named below. Toggling any of them without updating this ledger reintroduces
 * double transforms (inverted geometry, depth leaks, bright outlines):
 *
 * <ol>
 *   <li><b>Y flip</b> — shaderc {@code invert_y} at compile time ({@link ShadercProfile}).
 *       Nothing else flips Y: not the matrices, not the viewport.</li>
 *   <li><b>Depth range</b> — {@code z_vk = 0.5 * z_gl + 0.5 * w} premultiplied once into the
 *       stored projection by {@code UniformBridge.updateMatrices}.</li>
 *   <li><b>Winding</b> — a Y flip reverses triangle winding, so any pipeline that culls must
 *       use the <em>mirror</em> of its GL front face ({@link #vulkanFrontFaceFor(int)}).</li>
 *   <li><b>Viewport/scissor</b> — origin (0, 0), positive extents, full-framebuffer; narrowed
 *       only inside controlled draws and restored afterwards.</li>
 *   <li><b>Color</b> — intermediate targets hold linear/HDR values; tone map and gamma run
 *       exactly once, owned by the pack's final pass or by presentation, never both.</li>
 * </ol>
 */
public final class TransformLedger {

    /** GL_FRONT_FACE value for counter-clockwise. */
    public static final int GL_CCW = 0x0901;
    /** GL_FRONT_FACE value for clockwise. */
    public static final int GL_CW = 0x0900;

    /** Vulkan clear depth: the far plane in {@code [0, 1]} NDC. */
    public static final float VULKAN_CLEAR_DEPTH = 1.0f;

    private TransformLedger() {
    }

    /** @return OpenGL NDC depth ({@code [-1, 1]}) mapped to Vulkan NDC ({@code [0, 1]}) */
    public static float ndcDepthToVulkan(float glDepth) {
        return 0.5f * glDepth + 0.5f;
    }

    /**
     * Mirrors a GL front face for a Y-flipped pipeline so culling keeps GL semantics.
     *
     * @param glFrontFace {@link #GL_CCW} or {@link #GL_CW}
     * @return the Vulkan front face preserving the same visible faces
     * @throws IllegalArgumentException for any other value (never guessed)
     */
    public static int vulkanFrontFaceFor(int glFrontFace) {
        if (glFrontFace == GL_CCW) {
            return VK10.VK_FRONT_FACE_CLOCKWISE;
        }
        if (glFrontFace == GL_CW) {
            return VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
        }
        throw new IllegalArgumentException(
                "Unknown GL front face 0x" + Integer.toHexString(glFrontFace));
    }

    /**
     * Guards the exactly-once Y contract: the compiler must flip Y and the correction matrix
     * must not flip it again.
     */
    public static boolean isSingleFlipConfigured() {
        return ShadercProfile.DEFAULT.invertY()
                && RenderMatrices.CLIP_SPACE_CORRECTION.m11() == 1.0f;
    }

    /**
     * Bright-outline triage order from the plan: change one variable per run, in this order.
     */
    public static List<String> brightOutlineTriage() {
        return List.of(
                "depth-copy-compare",
                "target-alignment-resolution",
                "normal-attachments",
                "blend-alpha",
                "final-composite-color-space");
    }

    /** @return a one-line ledger summary for diagnostics */
    public static String describe() {
        return "Y=compile-time invert_y only; depth=[-1,1]->[0,1] in projection upload; "
                + "culling pipelines mirror frontFace; viewport origin (0,0); "
                + "linear/HDR intermediates, single tone-map/gamma";
    }
}
