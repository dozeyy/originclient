package com.vulkairis.iris;

import java.util.Collections;
import java.util.List;

/**
 * Isolated Vulkairis internal representation of Iris shader pack requirements and metadata.
 */
public record IrisShaderMetadata(
        String packName,
        String programName,
        boolean hasVertex,
        boolean hasFragment,
        boolean hasCompute,
        List<String> samplerNames,
        List<String> uniformNames
) {
    public static IrisShaderMetadata of(String packName, String programName, boolean hasVert, boolean hasFrag) {
        return new IrisShaderMetadata(packName, programName, hasVert, hasFrag, false, Collections.emptyList(), Collections.emptyList());
    }
}
