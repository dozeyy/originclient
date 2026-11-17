package com.vulkairis.translation;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 13: a named translation pipeline for GLSL &rarr; SPIR-V.
 *
 * <p>Replaces treating translation as one opaque regex step. Each stage is named and reports
 * pass/fail, and the original source, transformed source, SPIR-V and reflection are kept together so
 * a failure can be attributed to a specific stage. Shader semantics (framebuffers, shadows,
 * lighting, presentation) deliberately live elsewhere — this pipeline only makes GLSL valid and
 * reflectable.</p>
 */
public final class ShaderTranslationPipeline {

    /**
     * @param passName stage name
     * @param passed   whether the stage succeeded
     * @param detail   human-readable detail
     */
    public record PassResult(String passName, boolean passed, String detail) {
    }

    /**
     * @param name             shader name
     * @param originalSource   the raw source before any transformation
     * @param transformedSource the GLSL after compatibility transformation
     * @param spirv            the produced SPIR-V, or {@code null} on failure
     * @param reflection       the reflection, or {@code null} on failure
     * @param passes           per-stage results in order
     * @param success          whether every stage succeeded
     * @param failure          the first failure detail, or an empty string
     */
    public record TranslationResult(
            String name,
            String originalSource,
            String transformedSource,
            ByteBuffer spirv,
            ShaderReflection reflection,
            List<PassResult> passes,
            boolean success,
            String failure) {

        /** @return the last stage that ran */
        public String lastStage() {
            return passes.isEmpty() ? "<none>" : passes.get(passes.size() - 1).passName();
        }
    }

    private ShaderTranslationPipeline() {
    }

    /**
     * Runs the full pipeline for one stage.
     *
     * @param name        shader name for diagnostics
     * @param rawSource   the raw Iris GLSL
     * @param isVertex    whether this is a vertex stage
     * @param shadercKind the Shaderc stage constant
     * @return the result, successful or not
     */
    public static TranslationResult translate(String name, String rawSource, boolean isVertex, int shadercKind) {
        List<PassResult> passes = new ArrayList<>();

        String transformed;
        try {
            transformed = ShaderTranslator.preprocessGlsl(rawSource, isVertex, name);
            PassResult result = new PassResult("normalize-glsl", true,
                    transformed.length() + " chars");
            passes.add(result);
        } catch (Throwable t) {
            passes.add(new PassResult("normalize-glsl", false, String.valueOf(t.getMessage())));
            return failure(name, rawSource, rawSource, passes);
        }

        ByteBuffer spirv;
        try {
            spirv = SpirvCompiler.compileGlslToSpirv(name, transformed, shadercKind);
        } catch (Throwable t) {
            passes.add(new PassResult("compile-spirv", false, String.valueOf(t.getMessage())));
            return failure(name, rawSource, transformed, passes);
        }
        if (spirv == null || spirv.remaining() <= 0) {
            passes.add(new PassResult("compile-spirv", false, "no bytecode produced"));
            return failure(name, rawSource, transformed, passes);
        }
        passes.add(new PassResult("compile-spirv", true, spirv.remaining() + " bytes"));

        SpirvValidator.ValidationResult validation = SpirvValidator.validate(spirv.duplicate());
        passes.add(new PassResult("validate-spirv", validation.valid(), validation.detail()));
        if (!validation.valid()) {
            return failure(name, rawSource, transformed, passes);
        }

        ShaderReflection reflection;
        try {
            reflection = SpirvReflector.reflect(spirv.duplicate());
        } catch (Throwable t) {
            passes.add(new PassResult("reflect", false, String.valueOf(t.getMessage())));
            return failure(name, rawSource, transformed, passes);
        }
        boolean reflected = reflection != null && reflection.isComplete();
        passes.add(new PassResult("reflect", reflected,
                reflection == null ? "no reflection" : reflection.getSamplers().size() + " sampler(s)"));
        if (!reflected) {
            return failure(name, rawSource, transformed, passes);
        }

        return new TranslationResult(name, rawSource, transformed, spirv, reflection,
                List.copyOf(passes), true, "");
    }

    private static TranslationResult failure(String name, String original, String transformed,
                                             List<PassResult> passes) {
        String detail = passes.isEmpty() ? "unknown failure" : passes.get(passes.size() - 1).detail();
        String stage = passes.isEmpty() ? "<none>" : passes.get(passes.size() - 1).passName();
        return new TranslationResult(name, original, transformed, null, null,
                List.copyOf(passes), false, stage + ": " + detail);
    }
}
