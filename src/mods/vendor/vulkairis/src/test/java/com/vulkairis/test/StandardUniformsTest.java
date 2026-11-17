package com.vulkairis.test;

import com.vulkairis.bridge.StandardUniforms;
import com.vulkairis.bridge.UniformBridge;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Phase 10: standard uniforms resolve to documented sources with known values and cadences.
 */
public class StandardUniformsTest {

    @Test
    public void testSnapshotCarriesKnownValues() {
        UniformBridge bridge = new UniformBridge();
        bridge.updateMatrices(new org.joml.Matrix4f(), new org.joml.Matrix4f());
        bridge.updateResolution(1920, 1080);
        bridge.updateTime(0.033f, 1000L);
        bridge.updateCamera(10.0f, 70.0f, -5.0f, 42L);

        Map<String, float[]> snapshot =
                StandardUniforms.snapshot(bridge, new Vector3f(10.0f, 70.0f, -5.0f), 42L);

        Assertions.assertEquals(16, snapshot.get("ModelViewMat").length);
        Assertions.assertEquals(16, snapshot.get("ProjMat").length);
        Assertions.assertArrayEquals(new float[]{1920.0f, 1080.0f}, snapshot.get("ViewSize"), 1e-6f);
        Assertions.assertEquals(0.033f, snapshot.get("FrameTimeCounter")[0], 1e-6f);
        Assertions.assertArrayEquals(new float[]{10.0f, 70.0f, -5.0f},
                snapshot.get("CameraPosition"), 1e-6f);
        Assertions.assertEquals(42.0f, snapshot.get("Frame")[0], 1e-6f);
    }

    @Test
    public void testSnapshotToleratesNulls() {
        Map<String, float[]> snapshot = StandardUniforms.snapshot(null, null, 0L);
        Assertions.assertEquals(6, snapshot.size(), "every standard uniform is always present");
        Assertions.assertEquals(16, snapshot.get("ModelViewMat").length);
    }

    @Test
    public void testCadencesDocumented() {
        for (StandardUniforms.Standard standard : StandardUniforms.Standard.values()) {
            Assertions.assertFalse(standard.uniformName().isBlank());
            Assertions.assertFalse(standard.source().isBlank(),
                    standard.uniformName() + " must document its source of truth");
            Assertions.assertNotNull(standard.cadence(),
                    standard.uniformName() + " must document its update cadence");
        }
        Assertions.assertEquals(StandardUniforms.Cadence.ON_RESIZE,
                StandardUniforms.Standard.VIEW_SIZE.cadence());
        Assertions.assertEquals(StandardUniforms.Cadence.PER_FRAME,
                StandardUniforms.Standard.MODEL_VIEW.cadence());
    }
}
