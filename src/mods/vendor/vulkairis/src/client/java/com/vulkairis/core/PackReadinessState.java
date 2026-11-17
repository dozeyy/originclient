package com.vulkairis.core;

/**
 * Phase 1: Explicit production readiness states for the Vulkairis shader execution spine.
 * Stops treating discovery or "bridge active" as shader readiness.
 */
public enum PackReadinessState {
    /** No shader pack active; running vanilla / internal renderer. */
    NO_PACK,

    /** Pack program files and groups discovered from Iris ProgramSet. */
    DISCOVERED,

    /** GLSL translation pipeline actively running on pack source. */
    TRANSLATING,

    /** All required program stages compiled to SPIR-V and validated. */
    COMPILED,

    /** Interfaces validated: varying locations, descriptor set layouts, uniforms, attributes. */
    INTERFACE_VALIDATED,

    /** Real GPU resources (framebuffers, attachments, samplers, UBOs) allocated and backed. */
    RESOURCES_READY,

    /** Pipelines linked, descriptor sets updated, draw states configured; ready to execute. */
    EXECUTION_READY,

    /** Render passes actively executing on the GPU frame timeline. */
    RUNNING,

    /** Operational under tested fallback or with optional unsupported extensions disabled. */
    DEGRADED,

    /** Pack rejected before replacing any vanilla draw paths due to missing required capabilities. */
    REJECTED;

    /**
     * @return true if the state represents an active execution path
     */
    public boolean isOperational() {
        return this == EXECUTION_READY || this == RUNNING || this == DEGRADED;
    }
}
