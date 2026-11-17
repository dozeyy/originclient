package com.vulkairis.test;

import com.vulkairis.core.RenderMatrices;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Device-free verification of {@link RenderMatrices}: derived-matrix recomputation and the single
 * authoritative OpenGL -&gt; Vulkan clip-space correction.
 */
public class RenderMatricesTest {

    private static final float EPSILON = 1e-5f;

    /**
     * With an identity model and view, the model-view-projection must collapse to the projection
     * matrix, and the view-projection must equal the projection alone.
     */
    @Test
    public void testIdentityModelViewProducesExpectedModelViewProjection() {
        RenderMatrices matrices = new RenderMatrices();

        Matrix4f model = new Matrix4f();
        Matrix4f view = new Matrix4f();
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0), 16.0f / 9.0f, 0.05f, 1000.0f);

        matrices.setModel(model);
        matrices.setView(view);
        matrices.setProjection(projection);

        // Expected MVP computed independently: projection * view * model == projection.
        Matrix4f expectedMvp = new Matrix4f(projection).mul(view).mul(model);
        assertMatrixEquals(expectedMvp, matrices.getModelViewProjection());

        assertMatrixEquals(projection, matrices.getViewProjection());
        assertMatrixEquals(new Matrix4f(), matrices.getModelView());
    }

    /** The stored inverse view must undo the view transform: view * inverseView == identity. */
    @Test
    public void testInverseViewComposedWithViewIsIdentity() {
        RenderMatrices matrices = new RenderMatrices();

        Matrix4f view = new Matrix4f()
                .translate(3.0f, -2.0f, 5.0f)
                .rotateX(0.7f)
                .rotateY(-0.4f);
        matrices.setView(view);

        Matrix4f composed = new Matrix4f(view).mul(matrices.getInverseView());
        assertMatrixEquals(new Matrix4f(), composed);
    }

    /**
     * The clip-space correction must remap OpenGL NDC Z [-1, 1] to Vulkan NDC Z [0, 1] and must
     * leave Y untouched (the SPIR-V compiler performs the Y inversion).
     */
    @Test
    public void testClipSpaceCorrectionMapsDepthAndPreservesY() {
        Matrix4f correction = RenderMatrices.CLIP_SPACE_CORRECTION;

        Vector4f near = new Vector4f(0.0f, 0.0f, -1.0f, 1.0f);
        correction.transform(near);
        Assertions.assertEquals(0.0f, near.z, EPSILON, "OpenGL near plane (-1) must map to Vulkan 0");
        Assertions.assertEquals(1.0f, near.w, EPSILON, "Correction must not alter w");

        Vector4f far = new Vector4f(0.0f, 0.0f, 1.0f, 1.0f);
        correction.transform(far);
        Assertions.assertEquals(1.0f, far.z, EPSILON, "OpenGL far plane (+1) must map to Vulkan 1");

        Vector4f up = new Vector4f(0.0f, 1.0f, 0.0f, 1.0f);
        correction.transform(up);
        Assertions.assertEquals(1.0f, up.y, EPSILON,
                "Y must NOT be flipped by the correction; the compiler's set_invert_y already does it");
        Assertions.assertEquals(0.0f, up.x, EPSILON, "X must be untouched");
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                Assertions.assertEquals(expected.get(column, row), actual.get(column, row), EPSILON,
                        "Mismatch at column " + column + ", row " + row);
            }
        }
    }
}
