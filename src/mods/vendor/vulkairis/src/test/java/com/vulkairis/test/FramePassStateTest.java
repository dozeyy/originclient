package com.vulkairis.test;

import com.vulkairis.rendering.state.FrameState;
import com.vulkairis.rendering.state.PassState;
import com.vulkairis.rendering.state.UniformBlockPacker;
import com.vulkairis.translation.ShaderReflection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/**
 * Device-free verification of the Phase 5 frame/pass uniform layer: frame timing and camera history,
 * per-pass current/previous matrices, and reflection-driven uniform-block packing.
 */
public class FramePassStateTest {

    @Test
    public void testBeginFrameTracksPreviousCameraPosition() {
        FrameState frame = new FrameState();
        frame.beginFrame(1, 0.016f, 0.016f);
        frame.setCameraPosition(1, 2, 3);

        // beginFrame snapshots the previous frame's camera; this frame's camera is set afterwards.
        frame.beginFrame(2, 0.032f, 0.016f);
        frame.setCameraPosition(4, 5, 6);

        Assertions.assertEquals(4, frame.getCameraPosition().x, 1e-6);
        Assertions.assertEquals(1, frame.getPreviousCameraPosition().x, 1e-6);
        Assertions.assertEquals(2, frame.getFrameIndex());
        Assertions.assertEquals(0.032f, frame.getFrameTimeSeconds(), 1e-6);
    }

    @Test
    public void testFrameStateScreenClipAndFog() {
        FrameState frame = new FrameState();
        frame.setScreenSize(1280, 720);
        frame.setClipPlanes(0.1f, 512.0f);
        frame.setFog(0.1f, 0.2f, 0.3f, 0.5f);

        Assertions.assertEquals(1280, frame.getScreenWidth());
        Assertions.assertEquals(720, frame.getScreenHeight());
        Assertions.assertEquals(0.1f, frame.getNearPlane(), 1e-6);
        Assertions.assertEquals(0.5f, frame.getFogDensity(), 1e-6);
        Assertions.assertEquals(0.3f, frame.getFogColor().z, 1e-6);
    }

    @Test
    public void testPassStateSnapshotPreservesPreviousMatrices() {
        PassState pass = new PassState("shadow");
        Matrix4f firstView = new Matrix4f().translate(1, 0, 0);
        pass.setView(firstView);
        pass.snapshotPrevious();

        pass.setView(new Matrix4f().translate(2, 0, 0));

        Assertions.assertEquals(firstView, pass.getPrevious().getView());
        Assertions.assertNotEquals(firstView, pass.getCurrent().getView());

        pass.markShadowPass();
        Assertions.assertTrue(pass.isShadowPass());
        pass.setDimensions(2048, 2048);
        Assertions.assertEquals(2048, pass.getWidth());
    }

    @Test
    public void testUniformBlockPackerWritesAtReflectedOffsets() {
        ShaderReflection.UniformBlock block = new ShaderReflection.UniformBlock(
                "VulkairisUniformBlock", 0, 100, 0, 80,
                List.of(
                        new ShaderReflection.UniformInfo("projection", "mat4", 0, 64),
                        new ShaderReflection.UniformInfo("fogColor", "vec4", 64, 16)));

        float[] projection = new float[16];
        for (int i = 0; i < 16; i++) {
            projection[i] = i;
        }
        float[] fog = {0.1f, 0.2f, 0.3f, 0.4f};

        UniformBlockPacker.PackResult result = UniformBlockPacker.pack(
                block, Map.of("projection", projection, "fogColor", fog));

        Assertions.assertTrue(result.isComplete());
        ByteBuffer data = result.data();
        Assertions.assertEquals(0.0f, data.getFloat(0), 1e-6);
        Assertions.assertEquals(15.0f, data.getFloat(60), 1e-6);
        Assertions.assertEquals(0.1f, data.getFloat(64), 1e-6);
        Assertions.assertEquals(0.4f, data.getFloat(76), 1e-6);
    }

    @Test
    public void testUniformBlockPackerReportsMissingMembers() {
        ShaderReflection.UniformBlock block = new ShaderReflection.UniformBlock(
                "VulkairisUniformBlock", 0, 100, 0, 80,
                List.of(
                        new ShaderReflection.UniformInfo("projection", "mat4", 0, 64),
                        new ShaderReflection.UniformInfo("fogColor", "vec4", 64, 16)));

        UniformBlockPacker.PackResult result = UniformBlockPacker.pack(
                block, Map.of("fogColor", new float[] {1, 1, 1, 1}));

        Assertions.assertFalse(result.isComplete());
        Assertions.assertTrue(result.missing().contains("projection"));
        Assertions.assertTrue(result.written().contains("fogColor"));
    }
}
