package com.vulkairis.testpack;

import com.vulkairis.core.ShaderLifecycleState;
import com.vulkairis.diagnostics.StageDiagnostics;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import com.vulkairis.translation.SpirvValidator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 0 test harness. Drives every stage of a {@link ShaderTestPack} through the real bridge
 * pipeline — GLSL translation, in-process SPIR-V compilation, structural validation, and reflection
 * — and reports the exact lifecycle stage each stage reached.
 *
 * <p>Failures are explicit (never "shader loaded"): a stage is only marked passed once it reaches
 * {@link ShaderLifecycleState#REFLECTED} with the expected samplers and uniform block members
 * present. Resource binding, pipeline creation and execution are later lifecycle stages that need a
 * live Vulkan device; the harness deliberately stops short of claiming them.</p>
 */
public final class ShaderPackTestHarness {

    private ShaderPackTestHarness() {
    }

    /**
     * @param stage       stage name
     * @param reached     the furthest lifecycle state reached
     * @param passed      whether the stage reached REFLECTED with expectations satisfied
     * @param detail      human-readable outcome
     * @param spirvBytes  size of the produced SPIR-V, or 0 on failure
     * @param samplers    reflected sampler count
     * @param stageOutputs reflected stage-output count
     */
    public record StageResult(
            String stage,
            ShaderLifecycleState reached,
            boolean passed,
            String detail,
            int spirvBytes,
            int samplers,
            int stageOutputs) {
    }

    /**
     * @param pack   pack name
     * @param stages per-stage results in fixture order
     * @param passed whether every stage passed
     */
    public record PackReport(String pack, List<StageResult> stages, boolean passed) {
        public String summary() {
            StringBuilder builder = new StringBuilder();
            builder.append(pack).append(passed ? " PASS" : " FAIL");
            for (StageResult stage : stages) {
                builder.append("\n    ").append(stage.stage()).append(": ")
                        .append(stage.reached().label())
                        .append(stage.passed() ? " PASS" : " FAIL: " + stage.detail());
            }
            return builder.toString();
        }
    }

    /**
     * Runs every stage of {@code pack}.
     *
     * @param pack the fixture pack to run
     * @return the aggregated report
     */
    public static PackReport run(ShaderTestPack pack) {
        List<StageResult> results = new ArrayList<>();
        boolean passed = true;
        for (ShaderStageFixture stage : pack.stages()) {
            StageResult result = runStage(stage);
            results.add(result);
            passed &= result.passed();
        }
        return new PackReport(pack.name(), results, passed);
    }

    private static StageResult runStage(ShaderStageFixture stage) {
        ShaderLifecycleState reached = ShaderLifecycleState.DISCOVERED;
        try {
            String translated = ShaderTranslator.preprocessGlsl(stage.glsl(), stage.vertex());
            reached = ShaderLifecycleState.COMPILED;

            ByteBuffer spv = SpirvCompiler.compileGlslToSpirv(stage.name(), translated, stage.shadercKind());
            if (spv == null || spv.remaining() <= 0) {
                return fail(stage, reached, "SPIR-V compilation produced no bytecode");
            }
            int bytes = spv.remaining();

            SpirvValidator.ValidationResult validation = SpirvValidator.validate(spv.duplicate());
            if (!validation.valid()) {
                return fail(stage, reached, "SPIR-V validation failed: " + validation.detail());
            }
            reached = ShaderLifecycleState.SPIRV_VALID;

            ShaderReflection reflection = SpirvReflector.reflect(spv.duplicate());
            if (!reflection.isComplete()) {
                return fail(stage, reached, "reflection is incomplete");
            }
            reached = ShaderLifecycleState.REFLECTED;

            for (String sampler : stage.expectedSamplers()) {
                if (!hasSampler(reflection, sampler)) {
                    return fail(stage, reached, "expected sampler '" + sampler
                            + "' missing; got " + reflection.getSamplers().keySet());
                }
            }
            for (String member : stage.expectedBlockMembers()) {
                if (!hasMember(reflection, member)) {
                    return fail(stage, reached, "expected uniform block member '" + member
                            + "' missing; got " + reflection.getUniforms().keySet());
                }
            }

            StageDiagnostics.pass("HARNESS/" + stage.name(),
                    reached.label() + ", " + bytes + " bytes, samplers="
                            + reflection.getSamplers().values().stream()
                                    .map(binding -> binding.name() + "@" + binding.binding())
                                    .toList()
                            + ", outputs=" + reflection.getStageOutputs().size());
            return new StageResult(stage.name(), reached, true,
                    bytes + " bytes", bytes,
                    reflection.getSamplers().size(), reflection.getStageOutputs().size());
        } catch (Throwable t) {
            return fail(stage, reached, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static StageResult fail(ShaderStageFixture stage, ShaderLifecycleState reached, String detail) {
        StageDiagnostics.fail("HARNESS/" + stage.name(), detail, null);
        return new StageResult(stage.name(), reached, false, detail, 0, 0, 0);
    }

    private static boolean hasSampler(ShaderReflection reflection, String name) {
        return reflection.getSamplers().keySet().stream()
                .anyMatch(key -> key.equals(name) || key.endsWith("." + name));
    }

    private static boolean hasMember(ShaderReflection reflection, String name) {
        if (reflection.getUniforms().keySet().stream()
                .anyMatch(key -> key.equals(name) || key.endsWith("." + name))) {
            return true;
        }
        return reflection.getUniformBlocks().stream()
                .anyMatch(block -> block.members().stream().anyMatch(member -> member.name().equals(name)));
    }
}
