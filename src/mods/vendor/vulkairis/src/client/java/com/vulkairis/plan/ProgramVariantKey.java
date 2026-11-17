package com.vulkairis.plan;

import java.util.Objects;

/**
 * Phase 3: Immutable key identifying a specific program variant under active dimension and options.
 */
public record ProgramVariantKey(
        String programName,
        String dimension,
        String optionsDigest,
        long generation
) {
    public ProgramVariantKey {
        Objects.requireNonNull(programName, "programName");
        dimension = (dimension != null && !dimension.isBlank()) ? dimension : "overworld";
        optionsDigest = (optionsDigest != null) ? optionsDigest : "default";
    }

    public String compositeKey() {
        return programName + "@" + dimension + "#" + optionsDigest + "[gen=" + generation + "]";
    }
}
