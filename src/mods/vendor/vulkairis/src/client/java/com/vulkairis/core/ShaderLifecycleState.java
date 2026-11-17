package com.vulkairis.core;

/**
 * Ordered lifecycle states of a translated shader in the Vulkairis bridge.
 *
 * <p>The declaration order is significant: {@link #ordinal()} increases along the real execution
 * path, so a later state always implies every earlier stage completed. This exists to stop
 * "shader loaded" (source discovered, or SPIR-V compiled) from being confused with "shader
 * executed" (a draw was issued and its output presented).</p>
 */
public enum ShaderLifecycleState {
    /** GLSL source was found for the shader. */
    DISCOVERED,

    /** GLSL source was translated/compiled toward SPIR-V. */
    COMPILED,

    /** Valid SPIR-V bytecode exists for the shader. */
    SPIRV_VALID,

    /** Reflection over the SPIR-V produced usable metadata. */
    REFLECTED,

    /** A native {@code VkShaderModule} was created. */
    MODULE_CREATED,

    /** A {@code VkPipeline}/graphics pipeline was created. */
    PIPELINE_CREATED,

    /** The pipeline was bound for a draw. */
    BOUND,

    /** A draw command was executed. */
    DRAW_EXECUTED,

    /** The draw wrote to its render target. */
    OUTPUT_WRITTEN,

    /** The output was presented to the swapchain. */
    PRESENTED;

    /**
     * Returns whether this state is the same as, or later than, {@code other} along the execution
     * path.
     *
     * @param other the state to compare against; {@code null} yields {@code false}
     * @return {@code true} when {@code this.ordinal() >= other.ordinal()}
     */
    public boolean isAtLeast(ShaderLifecycleState other) {
        return other != null && this.ordinal() >= other.ordinal();
    }

    /**
     * Whether a move from this state to {@code next} is permitted.
     *
     * <p>Only forward moves (to a strictly higher ordinal) and no-op moves to the same state are
     * allowed. Skipping intermediate stages is permitted; moving backwards is never allowed.</p>
     *
     * @param next the candidate target state; {@code null} yields {@code false}
     * @return {@code true} when the transition is forward or a no-op
     */
    public boolean canTransitionTo(ShaderLifecycleState next) {
        return next != null && next.ordinal() >= this.ordinal();
    }

    /**
     * Returns a human-readable, space-separated label for this state.
     *
     * @return the label, e.g. {@code "SPIRV VALID"}
     */
    public String label() {
        return name().replace('_', ' ');
    }

    /**
     * Alias of {@link #label()} for callers that follow getter naming conventions.
     *
     * @return the human-readable label
     */
    public String getLabel() {
        return label();
    }
}
