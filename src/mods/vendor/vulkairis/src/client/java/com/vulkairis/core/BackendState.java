package com.vulkairis.core;

/**
 * Lifecycle state of the Vulkairis experimental graphics bridge backend.
 */
public enum BackendState {
    /** Vulkairis is disabled via configuration or startup flag. */
    DISABLED,

    /** Vulkairis is uninitialized prior to startup hooks. */
    UNINITIALIZED,

    /** Vulkan instance, device, capabilities, or bridge subsystems are actively initializing. */
    INITIALIZING,

    /** Native Vulkan physical and logical device are initialized and available. */
    DEVICE_READY,

    /** Iris bridge hooks and event listeners are active. */
    BRIDGE_ACTIVE,

    /** Vulkan shader modules, pipeline layouts, and graphics pipelines are ready for execution. */
    SHADER_PIPELINE_READY,

    /** Vulkan backend is fully initialized and operational. */
    READY,

    /** An unsupported shader or GPU feature was encountered; safely falling back to standard rendering. */
    FALLBACK,

    /** Fallback explicitly to OpenGL rendering path. */
    FALLBACK_GL,

    /** Subsystems have completed clean shutdown and released Vulkan handles. */
    SHUTDOWN,

    /** Vulkan initialization or device creation failed completely. */
    FAILED;

    public boolean isOperational() {
        return this == READY || this == BRIDGE_ACTIVE || this == SHADER_PIPELINE_READY;
    }
}
