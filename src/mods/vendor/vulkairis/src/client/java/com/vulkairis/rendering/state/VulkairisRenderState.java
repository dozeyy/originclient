package com.vulkairis.rendering.state;

import org.joml.Matrix4f;

/**
 * Aggregate of the device-free runtime state that replaces the previously no-op Iris rendering
 * operations: uploaded uniforms, resource bindings, synchronization/dispatch requests, and the
 * camera/shadow projection.
 *
 * <p>This is the semantic layer the plan calls out as the largest missing component. It is
 * deliberately free of Vulkan object creation so it can be exercised without a device; the
 * descriptor/pipeline/synchronization layers consume it to perform the real GPU work.</p>
 */
public final class VulkairisRenderState {

    private static final VulkairisRenderState INSTANCE = new VulkairisRenderState();

    private final ProgramUniformStore uniforms = new ProgramUniformStore();
    private final ResourceBindings bindings = new ResourceBindings();
    private final SynchronizationState synchronization = new SynchronizationState();

    private final Matrix4f playerProjection = new Matrix4f();
    private final Matrix4f shadowProjection = new Matrix4f();

    private volatile boolean shadowProjectionActive = false;
    private volatile int boundProgram = 0;

    private VulkairisRenderState() {
    }

    /** @return the process-wide render-state aggregate */
    public static VulkairisRenderState get() {
        return INSTANCE;
    }

    public ProgramUniformStore uniforms() {
        return uniforms;
    }

    public ResourceBindings bindings() {
        return bindings;
    }

    public SynchronizationState synchronization() {
        return synchronization;
    }

    /** Records the currently bound program id. */
    public void setBoundProgram(int program) {
        this.boundProgram = program;
    }

    public int getBoundProgram() {
        return boundProgram;
    }

    /** Stores the main camera projection (the one restored after the shadow pass). */
    public void setPlayerProjection(Matrix4f projection) {
        if (projection != null) {
            playerProjection.set(projection);
        }
    }

    public Matrix4f getPlayerProjection() {
        return playerProjection;
    }

    /**
     * Activates the light-space projection for the shadow pass.
     *
     * @param projection the shadow projection matrix
     */
    public void setShadowProjection(Matrix4f projection) {
        if (projection != null) {
            shadowProjection.set(projection);
            shadowProjectionActive = true;
        }
    }

    /** Leaves the shadow pass, restoring the player projection as the active projection. */
    public void restorePlayerProjection() {
        shadowProjectionActive = false;
    }

    /** @return whether the shadow projection is currently active */
    public boolean isShadowProjectionActive() {
        return shadowProjectionActive;
    }

    /** @return the projection a pass should currently use */
    public Matrix4f getActiveProjection() {
        return shadowProjectionActive ? shadowProjection : playerProjection;
    }

    /** Clears per-frame state. Not called automatically; callers own frame boundaries. */
    public void clearFrame() {
        uniforms.clear();
        bindings.clear();
        synchronization.clear();
        shadowProjectionActive = false;
    }
}
