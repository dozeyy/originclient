package com.vulkairis.rendering.state;

import com.vulkairis.core.RenderMatrices;
import org.joml.Matrix4f;

/**
 * Per-pass transform state: the model/view/projection (and derived inverses) for the current pass,
 * plus the previous frame's copies for reprojection/temporal effects.
 *
 * <p>Live packs use per-pass matrices (shadow, gbuffers, composite) and need previous-frame matrices
 * for effects such as TAA or motion blur, so this cannot be a single global matrix stack.</p>
 */
public final class PassState {

    private final String passName;
    private final RenderMatrices current = new RenderMatrices();
    private final RenderMatrices previous = new RenderMatrices();

    private int width;
    private int height;
    private long passIndex;
    private boolean shadowPass;

    public PassState(String passName) {
        this.passName = passName;
    }

    public String getPassName() {
        return passName;
    }

    public void begin(long index) {
        this.passIndex = index;
    }

    public long getPassIndex() {
        return passIndex;
    }

    public void setModel(Matrix4f model) {
        current.setModel(model);
    }

    public void setView(Matrix4f view) {
        current.setView(view);
    }

    public void setProjection(Matrix4f projection) {
        current.setProjection(projection);
    }

    /** Copies the current matrices into the previous-frame slot (call at the start of a frame). */
    public void snapshotPrevious() {
        previous.setModel(new Matrix4f(current.getModel()));
        previous.setView(new Matrix4f(current.getView()));
        previous.setProjection(new Matrix4f(current.getProjection()));
    }

    public RenderMatrices getCurrent() {
        return current;
    }

    public RenderMatrices getPrevious() {
        return previous;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public boolean isShadowPass() {
        return shadowPass;
    }

    /** Marks this pass as a shadow (light-space) pass. */
    public void markShadowPass() {
        this.shadowPass = true;
    }
}
