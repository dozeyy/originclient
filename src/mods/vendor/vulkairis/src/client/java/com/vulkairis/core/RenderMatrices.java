package com.vulkairis.core;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central, renderer-agnostic holder for the camera/transform matrices used by the
 * Vulkairis Vulkan bridge.
 *
 * <p>This is the single source of truth for matrix conventions in Vulkairis. Callers set the
 * three independent inputs ({@link #setModel(Matrix4f) model}, {@link #setView(Matrix4f) view},
 * {@link #setProjection(Matrix4f) projection}); every derived matrix is recomputed eagerly on
 * each set so no consumer can observe a stale value:
 *
 * <ul>
 *   <li>{@code normal} &mdash; inverse-transpose of the upper 3x3 of {@code model}. Required
 *       whenever non-uniform scaling is present; transforming normals by {@code model} directly
 *       skews lighting.</li>
 *   <li>{@code inverseView} / {@code inverseProjection} &mdash; used to reconstruct world/view
 *       space positions (e.g. for depth linearization and reprojection).</li>
 *   <li>{@code viewProjection} = {@code projection * view}.</li>
 *   <li>{@code modelView} = {@code view * model}.</li>
 *   <li>{@code modelViewProjection} = {@code viewProjection * model} = {@code projection * view * model}.</li>
 * </ul>
 *
 * <h2>Coordinate conventions</h2>
 *
 * <p><b>Depth range:</b> Iris/GLSL shaders emit OpenGL clip-space depth in {@code [-1, 1]}.
 * Vulkan's normalized device coordinates use {@code [0, 1]}. The mapping is a scale-and-bias,
 * {@code z_vk = 0.5 * z_gl + 0.5 * w}, applied after the projection matrix (see
 * {@link #CLIP_SPACE_CORRECTION}).</p>
 *
 * <p><b>Near/far:</b> projection matrices are expected to be authored in OpenGL convention
 * ("reversed-Z" or standard), with near/far mapping still targeting {@code [-1, 1]}. The depth
 * correction is applied uniformly and does not itself change near/far distances.</p>
 *
 * <p><b>Y orientation:</b> OpenGL clip space is Y-up, Vulkan framebuffers are conventionally
 * Y-down. Y is <em>not</em> corrected here because the SPIR-V compiler already performs the Y
 * inversion at compile time; see {@link #CLIP_SPACE_CORRECTION} for the full rationale.</p>
 *
 * <p><b>Viewport scaling:</b> viewport/scissor rectangles and any resolution-dependent projection
 * adjustments remain the responsibility of the render state tracker and the projection matrix,
 * not of this holder. This class only stores and derives matrices.</p>
 *
 * <p>This class intentionally has no Minecraft, Blaze3D or LWJGL coupling (JOML + optional
 * slf4j only) so it can be exercised by device-free unit tests.</p>
 */
public final class RenderMatrices {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/RenderMatrices");

    /**
     * THE single authoritative OpenGL &rarr; Vulkan clip-space correction matrix.
     *
     * <p><b>Chosen form:</b> Z scale {@code 0.5} + Z translate {@code 0.5}, <em>no Y flip</em>:</p>
     * <pre>
     *     [ 1   0   0   0 ]
     *     [ 0   1   0   0 ]
     *     [ 0   0  0.5 0.5]   // z' = 0.5*z + 0.5*w
     *     [ 0   0   0   1 ]
     * </pre>
     *
     * <p><b>Why no Y flip here?</b> OpenGL clip space is Y-up and Vulkan framebuffers are
     * conventionally Y-down, so a Y flip is genuinely needed <em>somewhere</em>. Vulkairis already
     * performs it at shader-compile time: {@code SpirvCompiler} calls
     * {@code shaderc_compile_options_set_invert_y(options, true)}. Baking another Y flip into this
     * matrix would double-flip and restore the wrong orientation. Therefore this matrix only fixes
     * the depth range.</p>
     *
     * <p>This constant replaces two divergent, previously-dead matrices:
     * {@code OpenGLBridge.CLIP_SPACE_CORRECTION} (which wrongly contained {@code y = -y}) and
     * {@code BridgeTestRenderer.VULKAN_DEPTH_CORRECTION} (which had the correct Z-only form). The
     * Y-flipping variant must not return while the compiler Y-inversion is enabled.</p>
     */
    public static final Matrix4f CLIP_SPACE_CORRECTION = buildClipSpaceCorrection();

    /**
     * Builds the depth-range correction explicitly (rather than via a positional literal) so the
     * JOML column-major argument order cannot be misread. Result: {@code z' = 0.5*z + 0.5*w}.
     */
    private static Matrix4f buildClipSpaceCorrection() {
        Matrix4f correction = new Matrix4f();
        correction.m00(1.0f);
        correction.m11(1.0f);  // Y deliberately untouched (compiler already inverted Y)
        correction.m22(0.5f);  // depth scale: z' = 0.5*z + 0.5*w
        correction.m32(0.5f);  // depth bias applied to w (JOML's transform is column-vector M*v)
        return correction;
    }

    private final Matrix4f model = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();

    private final Matrix4f normal = new Matrix4f();
    private final Matrix4f inverseView = new Matrix4f();
    private final Matrix4f inverseProjection = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Matrix4f modelViewProjection = new Matrix4f();

    public RenderMatrices() {
        // Inputs default to identity; derived matrices are (re)computed once below so they are
        // consistent from construction rather than only after the first setter call.
        recomputeDerived();
    }

    /** Sets the model (object-to-world) matrix and recomputes every derived matrix. */
    public void setModel(Matrix4f model) {
        if (model == null) return;
        this.model.set(model);
        recomputeDerived();
    }

    /** Sets the view (world-to-camera) matrix and recomputes every derived matrix. */
    public void setView(Matrix4f view) {
        if (view == null) return;
        this.view.set(view);
        recomputeDerived();
    }

    /** Sets the projection (camera-to-clip) matrix and recomputes every derived matrix. */
    public void setProjection(Matrix4f projection) {
        if (projection == null) return;
        this.projection.set(projection);
        recomputeDerived();
    }

    public Matrix4f getModel() {
        return model;
    }

    public Matrix4f getView() {
        return view;
    }

    public Matrix4f getProjection() {
        return projection;
    }

    /** Inverse-transpose of the upper 3x3 of {@link #getModel()}, stored as an affine 4x4. */
    public Matrix4f getNormal() {
        return normal;
    }

    public Matrix4f getInverseView() {
        return inverseView;
    }

    public Matrix4f getInverseProjection() {
        return inverseProjection;
    }

    public Matrix4f getViewProjection() {
        return viewProjection;
    }

    public Matrix4f getModelView() {
        return modelView;
    }

    public Matrix4f getModelViewProjection() {
        return modelViewProjection;
    }

    /**
     * Recomputes all derived matrices from {@code model}, {@code view} and {@code projection}.
     * Called after every input mutation.
     */
    private void recomputeDerived() {
        // Normal matrix: inverse-transpose of the upper-left 3x3. JOML's Matrix3f copy of a
        // Matrix4f takes exactly that 3x3 block.
        Matrix3f upperLeft = new Matrix3f(model);
        upperLeft.invert().transpose();
        normal.set(upperLeft);

        view.invert(inverseView);
        projection.invert(inverseProjection);

        projection.mul(view, viewProjection);       // viewProjection = projection * view
        view.mul(model, modelView);                 // modelView      = view * model
        viewProjection.mul(model, modelViewProjection); // MVP        = projection * view * model
    }
}
