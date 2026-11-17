package com.vulkairis.rendergraph;

/**
 * Shader stages a render-graph pass can execute. Used only to describe the pass's contract and to
 * validate that, for example, a compute pass declares a compute stage.
 */
public enum ShaderStage {
    VERTEX,
    TESSELLATION_CONTROL,
    TESSELLATION_EVALUATION,
    GEOMETRY,
    FRAGMENT,
    COMPUTE;

    /** @return whether this stage belongs to a raster graphics pipeline */
    public boolean isGraphicsStage() {
        return this != COMPUTE;
    }
}
