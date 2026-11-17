package com.vulkairis.iris;

import java.util.List;

/**
 * Provenance of one Iris-prepared shader source (Phase 2).
 *
 * <p>A unit is captured at the {@code GlShader.createShader} interception boundary, i.e. after
 * Iris has run its pack loading, option/macro resolution and {@code TransformPatcher} passes and
 * before any raw OpenGL call executes. The original Iris-transformed text is the immutable input
 * (retained by the shader registry); this record carries only its identity and hashes plus the
 * pipeline generation that produced it, so cached output is never reused for different input.
 *
 * @param id               stable identity {@code pack|program|stage|generation}
 * @param packId           Iris pack identity at capture time
 * @param irisProgramName  Iris program/shader name
 * @param stage            normalized stage key ({@code VERTEX}, {@code FRAGMENT}, ... or the raw
 *                         GL stage id when the type is unknown)
 * @param originalHash     hash of the exact Iris-transformed source intercepted
 * @param translatedHash   hash of the translator output, or {@code null} until translated
 * @param origin           {@code iris-transformed} for intercepted sources, {@code unintercepted}
 *                         for direct compiler use (tests, dry-run analysis)
 * @param observedMacros   {@code #define} names observed in the intercepted source (Iris owns
 *                         macro resolution; Vulkairis only records what it saw)
 * @param pipelineGeneration the pipeline generation that produced this unit
 */
public record SourceUnit(
        String id,
        String packId,
        String irisProgramName,
        String stage,
        String originalHash,
        String translatedHash,
        String origin,
        List<String> observedMacros,
        long pipelineGeneration) {

    public SourceUnit {
        observedMacros = observedMacros == null ? List.of() : List.copyOf(observedMacros);
    }

    /** @return this unit with the translation output recorded */
    public SourceUnit withTranslation(String newTranslatedHash) {
        return new SourceUnit(id, packId, irisProgramName, stage, originalHash,
                newTranslatedHash, origin, observedMacros, pipelineGeneration);
    }
}
