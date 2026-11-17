package com.vulkairis.test;

import com.vulkairis.bridge.UniformBridge;
import com.vulkairis.plan.SamplerPlan;
import com.vulkairis.plan.TargetPlan;
import com.vulkairis.plan.UniformBlockPacker;
import com.vulkairis.plan.UniformPlan;
import com.vulkairis.plan.VulkanDescriptorManager;
import com.vulkairis.translation.ShaderReflection;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 test suite: verifies typed SamplerPlan, TargetPlan/Schedule, UniformPlan,
 * UniformBlockPacker, and VulkanDescriptorManager.
 */
public class TypedPlansAndDescriptorsTest {

    @BeforeEach
    public void setUp() {
        VulkanDescriptorManager.reset();
    }

    @Test
    public void testSamplerPlanTotalBindingAndRejection() {
        // Reflected samplers: 2D color, shadow, and unsupported 3D noise
        Map<String, ShaderReflection.SamplerBinding> reflected = Map.of(
                "colortex0", new ShaderReflection.SamplerBinding("colortex0", 0, 1, 1),
                "shadowtex0", new ShaderReflection.SamplerBinding("shadowtex0", 1, 1, 1),
                "noise3d", new ShaderReflection.SamplerBinding("noise3d", 2, 1, 1)
        );

        TargetPlan targetPlan = new TargetPlan(1920, 1080, 1024, 100);
        targetPlan.registerTarget(new TargetPlan.TargetEntry(
                "colortex0", 0, "RGBA16F", 1920, 1080, true, true, "0.0,0.0,0.0,0.0", false, 0x1001L, 0x2001L));
        targetPlan.registerTarget(new TargetPlan.TargetEntry(
                "shadowtex0", 0, "DEPTH32F", 1024, 1024, false, true, "1.0", false, 0x1002L, 0x2002L));

        SamplerPlan plan = SamplerPlan.build("composite", reflected, targetPlan, 100);
        Assertions.assertEquals(3, plan.samplers().size());

        SamplerPlan.SamplerDescriptor colorDesc = plan.samplers().get("colortex0");
        Assertions.assertNotNull(colorDesc);
        Assertions.assertTrue(colorDesc.supported());
        Assertions.assertEquals(SamplerPlan.SamplerType.SAMPLER_2D, colorDesc.type());
        Assertions.assertEquals(0x1001L, colorDesc.imageView());
        Assertions.assertEquals(0x2001L, colorDesc.samplerHandle());

        SamplerPlan.SamplerDescriptor shadowDesc = plan.samplers().get("shadowtex0");
        Assertions.assertNotNull(shadowDesc);
        Assertions.assertTrue(shadowDesc.supported());
        Assertions.assertEquals(SamplerPlan.SamplerType.SAMPLER_2D_SHADOW, shadowDesc.type());
        Assertions.assertEquals(SamplerPlan.NumericClass.DEPTH, shadowDesc.numericClass());

        // Total binding verification: 3D texture is explicitly flagged unsupported with reason
        SamplerPlan.SamplerDescriptor noiseDesc = plan.samplers().get("noise3d");
        Assertions.assertNotNull(noiseDesc);
        Assertions.assertFalse(noiseDesc.supported(), "3D sampler must not silently fallback to sampler2D");
        Assertions.assertTrue(noiseDesc.rejectionReason().contains("3D"));

        Assertions.assertFalse(plan.isFullySupported());
        Assertions.assertFalse(plan.getRejectionReasons().isEmpty());
    }

    @Test
    public void testTargetPlanAndPingPongSchedule() {
        TargetPlan plan = new TargetPlan(1920, 1080, 2048, 200);

        plan.registerTarget(new TargetPlan.TargetEntry(
                "colortex0", 0, "RGBA16F", 1920, 1080, true, true, "0,0,0,0", false, 0x11L, 0x22L));
        plan.registerTarget(new TargetPlan.TargetEntry(
                "colortex4", 4, "R11G11B10F", 1920, 1080, false, false, "0,0,0,0", false, 0x33L, 0x44L));

        // Sparse target index verification
        Assertions.assertNotNull(plan.getTarget("colortex0"));
        Assertions.assertNull(plan.getTarget("colortex1"), "Unregistered sparse index must be null");
        Assertions.assertNotNull(plan.getTarget("colortex4"));

        // Ping-pong schedule
        Assertions.assertEquals(0, plan.getActiveWriteSide("colortex0"));
        Assertions.assertEquals(1, plan.getActiveReadSide("colortex0"));

        plan.flipPingPong("colortex0");
        Assertions.assertEquals(1, plan.getActiveWriteSide("colortex0"));
        Assertions.assertEquals(0, plan.getActiveReadSide("colortex0"));

        // Non-pingpong target always reads side 0
        Assertions.assertEquals(0, plan.getActiveReadSide("colortex4"));
    }

    @Test
    public void testUniformPlanStd140Layout() {
        List<ShaderReflection.UniformInfo> members = List.of(
                new ShaderReflection.UniformInfo("projection", "mat4", 0, 64),
                new ShaderReflection.UniformInfo("cameraPosition", "vec3", 64, 16),
                new ShaderReflection.UniformInfo("rainStrength", "float", 80, 4)
        );
        ShaderReflection.UniformBlock block = new ShaderReflection.UniformBlock(
                "VulkairisUniformBlock", 0, 1, 0, 96, members);

        UniformPlan plan = UniformPlan.build("terrain", block, 300);
        Assertions.assertEquals(3, plan.members().size());
        Assertions.assertEquals(96, plan.totalSizeBytes());

        UniformPlan.MemberSpec proj = plan.getMember("projection");
        Assertions.assertNotNull(proj);
        Assertions.assertEquals(0, proj.offsetBytes());
        Assertions.assertEquals(64, proj.sizeBytes());

        UniformPlan.MemberSpec cam = plan.getMember("cameraPosition");
        Assertions.assertNotNull(cam);
        Assertions.assertEquals(64, cam.offsetBytes());
    }

    @Test
    public void testUniformBlockPackerWritesValidStd140Buffer() {
        List<ShaderReflection.UniformInfo> members = List.of(
                new ShaderReflection.UniformInfo("ModelViewMat", "mat4", 0, 64),
                new ShaderReflection.UniformInfo("ProjMat", "mat4", 64, 64),
                new ShaderReflection.UniformInfo("CameraPosition", "vec3", 128, 16),
                new ShaderReflection.UniformInfo("FrameTimeCounter", "float", 144, 4)
        );
        ShaderReflection.UniformBlock block = new ShaderReflection.UniformBlock(
                "VulkairisUniformBlock", 0, 1, 0, 160, members);
        UniformPlan plan = UniformPlan.build("testProg", block, 400);

        UniformBridge bridge = UniformBridge.getInstance();
        Matrix4f mv = new Matrix4f().identity().translate(1.0f, 2.0f, 3.0f);
        Matrix4f proj = new Matrix4f().identity().scale(0.5f);
        bridge.updateMatrices(mv, proj);
        bridge.updateCamera(10.5f, 20.5f, 30.5f, 42);
        bridge.updateTime(0.016f, 1000L);

        ByteBuffer buf = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        UniformBlockPacker.pack(plan, bridge, Map.of(), buf);

        // Verify camera position at offset 128
        float camX = buf.getFloat(128);
        float camY = buf.getFloat(132);
        float camZ = buf.getFloat(136);
        Assertions.assertEquals(10.5f, camX, 1e-4f);
        Assertions.assertEquals(20.5f, camY, 1e-4f);
        Assertions.assertEquals(30.5f, camZ, 1e-4f);

        // Verify frame time at offset 144
        float frameTime = buf.getFloat(144);
        Assertions.assertEquals(0.016f, frameTime, 1e-4f);
    }

    @Test
    public void testVulkanDescriptorManagerPassValidation() {
        ShaderReflection reflection = new ShaderReflection();
        reflection.setUboBlockSize(128);
        reflection.addSampler("colortex0", 0, 1);

        TargetPlan targetPlan = new TargetPlan(1920, 1080, 1024, 500);
        targetPlan.registerTarget(new TargetPlan.TargetEntry(
                "colortex0", 0, "RGBA16F", 1920, 1080, true, true, "0,0,0,0", false, 0x11L, 0x22L));

        SamplerPlan samplerPlan = SamplerPlan.build("compPass", reflection.getSamplers(), targetPlan, 500);
        UniformPlan uniformPlan = new UniformPlan("compPass", 128, Map.of(), 500);

        VulkanDescriptorManager.PassDescriptorReport report =
                VulkanDescriptorManager.preparePassDescriptors("compPass", reflection, samplerPlan, uniformPlan, targetPlan, 500);

        Assertions.assertTrue(report.valid(), "Pass descriptor preparation must be valid when all resources are backed");
        Assertions.assertNotEquals(0L, report.descriptorSetHandle(), "Descriptor set handle must be non-zero");
        Assertions.assertTrue(report.missingResources().isEmpty());

        // Invalidate generation
        VulkanDescriptorManager.invalidateGeneration(501);
        Assertions.assertNull(VulkanDescriptorManager.getReport("compPass"));
    }
}
