package com.vulkairis.test;

import com.vulkairis.bridge.UniformBridge;
import com.vulkairis.bridge.VulkanModProgramBridge;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class UniformTransportTest {
    @Test
    public void testUploadUniformsNullSafe() {
        Assertions.assertDoesNotThrow(() -> VulkanModProgramBridge.uploadUniforms(null));
    }

    @Test
    public void testWriteOfGlobalsStd140() {
        UniformBridge bridge = new UniformBridge();
        bridge.updateMatrices(new org.joml.Matrix4f(), new org.joml.Matrix4f());
        ByteBuffer buf = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        bridge.writeOfGlobals(buf);
        Assertions.assertTrue(buf.position() > 0, "OfGlobals payload must be written");
    }
}