package com.vulkairis.test;

import com.vulkairis.bridge.UniformBridge;
import com.vulkairis.core.RenderMatrices;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Phase 9: matrix upload order — Iris GL matrices in, Vulkan-corrected std140 out, no transpose,
 * basis vectors landing where the ledger says.
 */
public class UniformBridgeTest {

    @Test
    public void testProjectionArrivesDepthCorrected() {
        UniformBridge bridge = new UniformBridge();
        bridge.updateMatrices(new Matrix4f(), new Matrix4f());
        Assertions.assertEquals(RenderMatrices.CLIP_SPACE_CORRECTION, bridge.getProjectionMatrix(),
                "identity GL projection must store exactly the depth correction");
    }

    @Test
    public void testDepthBasisVectorsMapToVulkanRange() {
        UniformBridge bridge = new UniformBridge();
        bridge.updateMatrices(new Matrix4f(), new Matrix4f());

        Vector4f near = new Vector4f(0.0f, 0.0f, -1.0f, 1.0f);
        near.mul(bridge.getModelViewProjection());
        Assertions.assertEquals(0.0f, near.z, 1e-6f, "GL near plane must land on Vulkan 0");

        Vector4f far = new Vector4f(0.0f, 0.0f, 1.0f, 1.0f);
        far.mul(bridge.getModelViewProjection());
        Assertions.assertEquals(1.0f, far.z, 1e-6f, "GL far plane must land on Vulkan 1");

        Vector4f up = new Vector4f(0.0f, 5.0f, 0.0f, 1.0f);
        up.mul(bridge.getModelViewProjection());
        Assertions.assertEquals(5.0f, up.y, 1e-6f, "Y must survive the upload (flip lives in compile)");
        Assertions.assertEquals(1.0f, up.w, 1e-6f);
    }

    @Test
    public void testUploadLayoutIsColumnMajorStd140() {
        UniformBridge bridge = new UniformBridge();
        bridge.updateMatrices(new Matrix4f(), new Matrix4f());

        ByteBuffer target = ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);
        bridge.writeStandardUniforms(target);
        Assertions.assertEquals(128, target.position(), "both matrices must be written");

        target.flip();
        // ModelView identity occupies floats [0, 16).
        Assertions.assertEquals(1.0f, target.asFloatBuffer().get(0), 1e-6f);
        Assertions.assertEquals(0.0f, target.asFloatBuffer().get(1), 1e-6f);
        // Projection correction occupies floats [16, 32): column-major m22/m32/m33.
        Assertions.assertEquals(0.5f, target.asFloatBuffer().get(16 + 10), 1e-6f,
                "m22 must carry the depth scale");
        Assertions.assertEquals(0.5f, target.asFloatBuffer().get(16 + 14), 1e-6f,
                "m32 must carry the depth bias");
        Assertions.assertEquals(1.0f, target.asFloatBuffer().get(16 + 15), 1e-6f);
    }

    @Test
    public void testShortBufferIsNeverOverwritten() {
        UniformBridge bridge = new UniformBridge();
        ByteBuffer target = ByteBuffer.allocateDirect(64).order(ByteOrder.LITTLE_ENDIAN);
        bridge.writeStandardUniforms(target);
        Assertions.assertEquals(0, target.position(), "undersized targets are left untouched");
        Assertions.assertDoesNotThrow(() -> bridge.writeStandardUniforms(null));
    }

    @Test
    public void testNullInputsAreIgnored() {
        UniformBridge bridge = new UniformBridge();
        Assertions.assertDoesNotThrow(() -> bridge.updateMatrices(null, null));
        Assertions.assertEquals(new Matrix4f(), bridge.getModelViewMatrix());
    }
}
