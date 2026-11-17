package com.vulkairis.iris;

/**
 * Whether a discovered shader-pack program is a raster graphics program or a compute program.
 *
 * <p>This is derived from the actual stages a pack declares (a compute source is a compute
 * program; anything with vertex/fragment/geometry stages is a graphics program), not from an
 * assumption about the program name.</p>
 */
public enum ShaderProgramKind {
    /** A vertex/fragment (optionally geometry/tessellation) raster pipeline. */
    GRAPHICS,

    /** A compute shader dispatched against storage resources. */
    COMPUTE;

    /**
     * Classifies a program from whether it was discovered as a compute source.
     *
     * @param hasComputeSource whether the program came from an Iris {@code ComputeSource}
     * @return {@link #COMPUTE} when {@code hasComputeSource} is true, otherwise {@link #GRAPHICS}
     */
    public static ShaderProgramKind of(boolean hasComputeSource) {
        return hasComputeSource ? COMPUTE : GRAPHICS;
    }
}
