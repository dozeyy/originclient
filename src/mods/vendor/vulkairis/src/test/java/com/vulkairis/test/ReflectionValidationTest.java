package com.vulkairis.test;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvValidator;
import com.vulkairis.translation.VulkairisShader;
import com.vulkairis.vulkan.pipeline.PipelineCompatibilityValidator;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;

/**
 * Device-free coverage for Phase 4: SPIR-V stage-interface reflection (inputs and outputs) and
 * the pure pipeline compatibility validation built on top of it.
 *
 * <p>It compiles a small matching vertex/fragment pair with the in-process Shaderc compiler and
 * asserts that the fragment's {@code out} variable is reflected as a stage output, that vertex
 * outputs are reflected, and that {@link PipelineCompatibilityValidator} accepts a matching pair
 * while rejecting a mismatched one.</p>
 */
public class ReflectionValidationTest {

    private static final String VERT = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 0) out vec2 vUv;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vUv = Position.xy;
            }
            """;

    private static final String FRAG = """
            #version 450 core
            layout (location = 0) in vec2 vUv;
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(vUv, 0.0, 1.0);
            }
            """;

    /** Same as {@link #FRAG} but consumes its input at location 1, so it cannot match {@link #VERT}. */
    private static final String FRAG_MISMATCHED = """
            #version 450 core
            layout (location = 1) in vec2 vUv;
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = vec4(vUv, 0.0, 1.0);
            }
            """;

    @Test
    public void testStageOutputsAreReflected() {
        VulkairisShader vert = SpirvCompiler.compileShader("reflection_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("reflection_frag", FRAG, ShaderType.FRAGMENT);

        Assertions.assertNotNull(vert, "Vertex shader must be registered");
        Assertions.assertNotNull(frag, "Fragment shader must be registered");
        Assertions.assertTrue(vert.getReflection().isComplete(), "Vertex reflection must be complete");
        Assertions.assertTrue(frag.getReflection().isComplete(), "Fragment reflection must be complete");

        // Fragment outputs were the explicitly skipped category before Phase 4.
        List<ShaderReflection.StageOutput> fragOutputs = frag.getReflection().getStageOutputs();
        Assertions.assertFalse(fragOutputs.isEmpty(),
                "Fragment reflection must expose the 'out' variable as a stage output");
        ShaderReflection.StageOutput fragColor = fragOutputs.stream()
                .filter(o -> o.location() == 0)
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(fragColor, "Fragment output at location 0 must be reflected");
        Assertions.assertEquals("fragColor", fragColor.name(),
                "The reflected fragment output should keep its SPIR-V name");
        Assertions.assertEquals(4, fragColor.componentCount(),
                "A vec4 fragment output must report 4 components");

        // Vertex outputs drive the same interface matching.
        List<ShaderReflection.StageOutput> vertOutputs = vert.getReflection().getStageOutputs();
        Assertions.assertTrue(vertOutputs.stream()
                        .anyMatch(o -> o.location() == 0 && o.componentCount() == 2),
                "Vertex reflection must expose the vec2 'vUv' output at location 0");

        // Stage inputs are exposed for both stages; vertex inputs also remain VertexAttributes.
        Assertions.assertTrue(vert.getReflection().getStageInputs().stream()
                        .anyMatch(i -> i.location() == 0 && i.componentCount() == 3),
                "Vertex reflection must expose the vec3 Position input as a stage input");
        Assertions.assertTrue(frag.getReflection().getStageInputs().stream()
                        .anyMatch(i -> i.location() == 0 && i.componentCount() == 2),
                "Fragment reflection must expose the vec2 vUv input as a stage input");
        Assertions.assertFalse(vert.getReflection().getVertexAttributes().isEmpty(),
                "Vertex inputs must still be exposed as VertexAttributes (no regression)");
    }

    @Test
    public void testCompatiblePairValidates() {
        VulkairisShader vert = SpirvCompiler.compileShader("compat_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("compat_frag", FRAG, ShaderType.FRAGMENT);

        List<String> problems = PipelineCompatibilityValidator.validate(
                vert.getReflection(), frag.getReflection(), 1);
        Assertions.assertTrue(problems.isEmpty(),
                "A matching vertex/fragment pair must validate cleanly, got: " + problems);

        List<String> formatProblems = PipelineCompatibilityValidator.validateVertexFormat(
                vert.getReflection(), Set.of(0));
        Assertions.assertTrue(formatProblems.isEmpty(),
                "Vertex format supplying location 0 must cover the reflected attribute, got: "
                        + formatProblems);

        List<String> missingFormatProblems = PipelineCompatibilityValidator.validateVertexFormat(
                vert.getReflection(), Set.of(5));
        Assertions.assertFalse(missingFormatProblems.isEmpty(),
                "A vertex format missing the reflected attribute location must be rejected");
    }

    @Test
    public void testMismatchedPairIsRejected() {
        VulkairisShader vert = SpirvCompiler.compileShader("mismatch_vert", VERT, ShaderType.VERTEX);
        VulkairisShader badFrag = SpirvCompiler.compileShader("mismatch_frag", FRAG_MISMATCHED,
                ShaderType.FRAGMENT);

        List<String> problems = PipelineCompatibilityValidator.validate(
                vert.getReflection(), badFrag.getReflection(), 1);
        Assertions.assertFalse(problems.isEmpty(),
                "A fragment input with no matching vertex output must be rejected");
        Assertions.assertTrue(problems.stream().anyMatch(p -> p.contains("location 1")),
                "The rejection should identify the unmatched location, got: " + problems);
    }

    @Test
    public void testSpirvValidationResultApi() {
        VulkairisShader vert = SpirvCompiler.compileShader("validator_vert", VERT, ShaderType.VERTEX);
        Assertions.assertNotNull(vert.getSpirv(), "Compiled shader must retain its SPIR-V bytecode");
        Assertions.assertTrue(SpirvValidator.isValid(vert.getSpirv()),
                "A Shaderc-produced module must pass structural validation");
        Assertions.assertTrue(SpirvValidator.validate(vert.getSpirv()).valid(),
                "The ValidationResult API must agree with the boolean view");

        Assertions.assertFalse(SpirvValidator.isValid(ByteBuffer.allocate(16)),
                "A buffer too small for the header must be rejected");
    }
}
