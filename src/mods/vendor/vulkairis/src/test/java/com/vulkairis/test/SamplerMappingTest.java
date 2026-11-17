package com.vulkairis.test;

import com.vulkairis.iris.PbrTextureState;
import com.vulkairis.resource.SamplerPolicy;
import com.vulkairis.rendering.state.SamplerBindingTracker;
import com.vulkairis.rendering.state.TextureUnitPolicy;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkanProgramLinker;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 10: texture units map through one validated policy, over-budget programs are refused
 * with the exact limit, sampler identity survives texture reuse, and compare samplers exist.
 */
public class SamplerMappingTest {

    @Test
    public void testUnitPolicy() {
        Assertions.assertEquals(5,
                TextureUnitPolicy.toUnit(TextureUnitPolicy.GL_TEXTURE0 + 5).orElseThrow());
        Assertions.assertEquals(11,
                TextureUnitPolicy.toUnit(TextureUnitPolicy.GL_TEXTURE0 + 11).orElseThrow());
        Assertions.assertTrue(
                TextureUnitPolicy.toUnit(TextureUnitPolicy.GL_TEXTURE0 + 12).isEmpty(),
                "unit 12 exceeds the bridge range and must be refused");
        Assertions.assertTrue(
                TextureUnitPolicy.toUnit(TextureUnitPolicy.GL_TEXTURE0 - 1).isEmpty());
        Assertions.assertTrue(TextureUnitPolicy.isSupported(0));
        Assertions.assertFalse(TextureUnitPolicy.isSupported(12));
        Assertions.assertEquals(12, TextureUnitPolicy.SUPPORTED_UNIT_COUNT);
    }

    @Test
    public void testOverBudgetProgramRefusedWithLimit() {
        StringBuilder samplers = new StringBuilder();
        StringBuilder sum = new StringBuilder();
        for (int i = 0; i < 13; i++) {
            samplers.append("uniform sampler2D s").append(i).append(";\n");
            if (i > 0) {
                sum.append(" + ");
            }
            sum.append("texture(s").append(i).append(", vUv).r");
        }
        String vert = """
                #version 150 core
                in vec3 iris_Position;
                in vec2 iris_UV;
                out vec2 vUv;
                void main() {
                    vUv = iris_UV;
                    gl_Position = vec4(iris_Position, 1.0);
                }
                """;
        String frag = """
                #version 150 core
                in vec2 vUv;
                out vec4 fragColor;
                void main() {
                    float x = %s;
                    fragColor = vec4(vec3(x), 1.0);
                }
                """.formatted(sum);

        VulkairisShader vertShader =
                SpirvCompiler.compileShader("budget_vert", vert, ShaderType.VERTEX);
        // Samplers declared up front through the same translator path real packs take.
        String fragWithSamplers = frag.replace("in vec2 vUv;", samplers + "in vec2 vUv;");
        VulkairisShader fragShader =
                SpirvCompiler.compileShader("budget_frag", fragWithSamplers, ShaderType.FRAGMENT);
        int progId = VulkanShaderRegistry.registerProgram(
                "budget_prog", List.of(vertShader.virtualId(), fragShader.virtualId()));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);

        VulkanProgramLinker.LinkResult result = VulkanProgramLinker.validate(program);
        Assertions.assertFalse(result.valid(), "13 samplers must not link against 12 units");
        Assertions.assertTrue(result.errors().stream().anyMatch(e -> e.contains("12")),
                "the refusal must name the exact limit: " + result.errors());
    }

    @Test
    public void testBindingIdentityAcrossReuse() {
        SamplerBindingTracker tracker = new SamplerBindingTracker();
        Assertions.assertEquals(SamplerBindingTracker.BindOutcome.ASSIGNED,
                tracker.bind(5001, "colortex0", 77));
        Assertions.assertEquals(SamplerBindingTracker.BindOutcome.SAME,
                tracker.bind(5001, "colortex0", 77));
        Assertions.assertEquals(SamplerBindingTracker.BindOutcome.REBOUND,
                tracker.bind(5001, "colortex0", 78),
                "texture reuse under a live sampler must be explicit, not aliased");
        Assertions.assertEquals(78, tracker.boundTexture(5001, "colortex0").orElseThrow());
        Assertions.assertEquals(1, tracker.size());

        tracker.invalidate();
        Assertions.assertTrue(tracker.boundTexture(5001, "colortex0").isEmpty(),
                "reload invalidation must drop stale texture references");
        Assertions.assertEquals(0, tracker.size());
    }

    @Test
    public void testCompareSamplersExist() {
        Assertions.assertTrue(SamplerPolicy.SHADOW_COMPARE.isCompareEnabled());
        Assertions.assertFalse(SamplerPolicy.LINEAR_CLAMP.isCompareEnabled());
        Assertions.assertTrue(SamplerPolicy.SHADOW_COMPARE.isLinear());
        com.vulkairis.framebuffer.VulkanSampler.SamplerKey plain =
                new com.vulkairis.framebuffer.VulkanSampler.SamplerKey(1, 1, 0);
        com.vulkairis.framebuffer.VulkanSampler.SamplerKey compare =
                new com.vulkairis.framebuffer.VulkanSampler.SamplerKey(1, 1, 0, true);
        Assertions.assertNotEquals(plain, compare,
                "compare samplers must cache separately from regular ones");
    }

    @Test
    public void testPbrInvalidation() {
        long before = PbrTextureState.currentGeneration();
        long next = PbrTextureState.invalidate("test reload");
        Assertions.assertEquals(before + 1, next);
        Assertions.assertTrue(PbrTextureState.isStale(before));
        Assertions.assertFalse(PbrTextureState.isStale(next));
    }
}
