package com.vulkairis.rendering.exec;

import com.vulkairis.resource.TextureResource;
import com.vulkairis.rendering.state.VulkairisRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Phase 6: coordinates the shadow pass.
 *
 * <p>Owns the shadow depth target, builds the light-space projection from the sun direction, exposes
 * the shadow sampler for later gbuffer/composite passes, and applies the depth-bias policy so acne is
 * suppressed. The depth-only render-pass recording itself is performed by the executor against the
 * shadow target; this class is the state/resource owner for that pass.</p>
 */
public final class ShadowRenderer {

    private static volatile boolean debugVisualization = false;

    private final TextureResource shadowDepth;
    private final ShadowPassConfig config;

    public ShadowRenderer(TextureResource shadowDepth, ShadowPassConfig config) {
        if (shadowDepth == null || config == null) {
            throw new IllegalArgumentException("shadowDepth and config are required");
        }
        this.shadowDepth = shadowDepth;
        this.config = config;
    }

    /** Allocates the shadow depth image if a device is live. */
    public boolean ensureResources() {
        return shadowDepth.ensureBacking();
    }

    /**
     * Activates the light-space projection for this frame's shadow pass.
     *
     * @param sunDirection    direction from the scene toward the sun
     * @param cameraPosition  the point the shadow volume is centered on
     * @param playerProjection the main camera projection to restore afterwards
     * @return the light-space matrices
     */
    public ShadowProjection.Result beginShadowFrame(
            Vector3f sunDirection, Vector3f cameraPosition, Matrix4f playerProjection) {
        if (playerProjection != null) {
            VulkairisRenderState.get().setPlayerProjection(playerProjection);
        }
        ShadowProjection.Result result = ShadowProjection.build(
                sunDirection, cameraPosition, config.shadowDistance(), config.shadowDistance() * 2.0f);
        VulkairisRenderState.get().setShadowProjection(result.viewProjection());
        return result;
    }

    /** Restores the player projection after the shadow pass. */
    public void endShadowFrame() {
        VulkairisRenderState.get().restorePlayerProjection();
    }

    public ShadowPassConfig getConfig() {
        return config;
    }

    public TextureResource getShadowDepth() {
        return shadowDepth;
    }

    public long getShadowImageView() {
        return shadowDepth.getViewHandle();
    }

    public long getShadowSampler() {
        return shadowDepth.getSamplerHandle();
    }

    /** @return whether the shadow map should be drawn on screen for debugging */
    public static boolean isDebugVisualization() {
        return debugVisualization;
    }

    public static void setDebugVisualization(boolean enabled) {
        debugVisualization = enabled;
    }
}
