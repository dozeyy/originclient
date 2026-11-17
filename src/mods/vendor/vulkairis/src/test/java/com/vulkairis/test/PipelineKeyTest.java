package com.vulkairis.test;

import com.vulkairis.rendering.RenderStateTracker;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.vulkan.pipeline.PipelineKey;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

/**
 * Phase 6: the pipeline cache key covers modules, interface, layout, pass, raster state,
 * samples and flags; creation guards fail before touching the driver.
 */
public class PipelineKeyTest {

    private static final String VERT = """
            #version 450 core
            layout(location = 0) in vec3 Position;
            layout(location = 0) out vec4 vColor;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vColor = vec4(1.0);
            }
            """;

    private static final String FRAG = """
            #version 450 core
            layout(location = 0) in vec4 vColor;
            layout(location = 0) out vec4 fragColor;
            void main() {
                fragColor = vColor;
            }
            """;

    private VulkanPipelineFactory.PipelineParams params(
            long vert, long frag, long layout, long pass, int samples, boolean bias) {
        return new VulkanPipelineFactory.PipelineParams(
                vert, frag, layout, pass, null,
                true, true, false, false, 0L,
                VulkanPipelineFactory.DEFAULT_TOPOLOGY,
                VulkanPipelineFactory.DEFAULT_DEPTH_COMPARE_OP,
                VulkanPipelineFactory.DEFAULT_FRONT_FACE,
                samples,
                bias, 0.0f, 0.0f, 0.0f);
    }

    @Test
    public void testIdenticalParamsShareKey() {
        PipelineKey first = VulkanPipelineFactory.buildPipelineKey(params(1L, 2L, 3L, 4L, 1, false));
        PipelineKey second = VulkanPipelineFactory.buildPipelineKey(params(1L, 2L, 3L, 4L, 1, false));
        Assertions.assertEquals(first, second);
        Assertions.assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void testSamplesLayoutAndFlagsDistinguishKeys() {
        PipelineKey base = VulkanPipelineFactory.buildPipelineKey(params(1L, 2L, 3L, 4L, 1, false));
        Assertions.assertNotEquals(base,
                VulkanPipelineFactory.buildPipelineKey(params(1L, 2L, 3L, 4L, 4, false)),
                "sample count must be part of the key");
        Assertions.assertNotEquals(base,
                VulkanPipelineFactory.buildPipelineKey(params(1L, 2L, 9L, 4L, 1, false)),
                "pipeline layout must be part of the key");
        Assertions.assertNotEquals(base,
                VulkanPipelineFactory.buildPipelineKey(params(1L, 2L, 3L, 4L, 1, true)),
                "depth-bias flag must be part of the key");
        Assertions.assertEquals(1, base.rasterizationSamples());
        Assertions.assertEquals(3L, base.pipelineLayout());
    }

    @Test
    public void testInterfaceFingerprintTracksReflection() {
        VulkairisShader vert = SpirvCompiler.compileShader("key_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("key_frag", FRAG, ShaderType.FRAGMENT);
        Assertions.assertTrue(vert.getReflection().isComplete());
        Assertions.assertTrue(frag.getReflection().isComplete());

        String fingerprint = VulkanPipelineFactory.stageInterfaceFingerprint(vert, frag);
        Assertions.assertEquals("v[0:vec4,]|f[0:vec4,]", fingerprint,
                "fingerprint must list sorted location:type pairs, got: " + fingerprint);

        Assertions.assertEquals("", VulkanPipelineFactory.stageInterfaceFingerprint(111L, 222L),
                "unknown modules fingerprint empty, never fabricated");
        Assertions.assertEquals("", VulkanPipelineFactory.stageInterfaceFingerprint(null, frag));
    }

    @Test
    public void testStateTrackerKeyCarriesNewFields() {
        RenderStateTracker tracker = new RenderStateTracker();
        PipelineKey key = tracker.createPipelineKey(100L, 200L, "POSITION_COLOR");
        Assertions.assertEquals(VulkanPipelineFactory.DEFAULT_SAMPLE_COUNT,
                key.rasterizationSamples());
        Assertions.assertEquals(0L, key.renderPass(), "state-only keys leave the pass unspecified");
        PipelineKey key2 = tracker.createPipelineKey(100L, 200L, "POSITION_COLOR");
        Assertions.assertEquals(key, key2, "same state must produce the same key");
    }

    @Test
    public void testCreationGuardsFireBeforeNativeCalls() {
        VulkanPipelineFactory.PipelineParams ok =
                params(1L, 2L, 3L, 4L, 1, false);
        Assertions.assertThrows(IllegalStateException.class,
                () -> VulkanPipelineFactory.createGraphicsPipeline(null, ok),
                "dead device must fail before any native call");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> VulkanPipelineFactory.createGraphicsPipeline(null,
                        params(0L, 2L, 3L, 4L, 1, false)),
                "zero modules must fail before any native call");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> VulkanPipelineFactory.createGraphicsPipeline(null,
                        params(1L, 2L, 0L, 4L, 1, false)),
                "zero layout must fail before any native call");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> VulkanPipelineFactory.createGraphicsPipeline(null,
                        params(1L, 2L, 3L, 0L, 1, false)),
                "zero render pass must fail before any native call");
        Assertions.assertEquals(VK10.VK_SAMPLE_COUNT_1_BIT,
                VulkanPipelineFactory.DEFAULT_SAMPLE_COUNT);
    }
}
