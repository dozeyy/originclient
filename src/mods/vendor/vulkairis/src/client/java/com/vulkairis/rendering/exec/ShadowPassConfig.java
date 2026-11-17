package com.vulkairis.rendering.exec;

import com.vulkairis.resource.RenderTargetFormat;

/**
 * Phase 6: configuration for the shadow pass.
 *
 * <p>Shadows are a separate depth-only geometry pass rendered from the light's point of view. This
 * carries the resolution, depth format, polygon-offset (depth bias) values that prevent shadow acne,
 * cascade count, and cull convention. It is device-free so the configuration and bias policy can be
 * unit-tested.</p>
 *
 * @param resolution        shadow map resolution (square)
 * @param depthFormat       depth format of the shadow map
 * @param depthBiasConstant constant depth bias (Vulkan {@code depthBiasConstantFactor})
 * @param depthBiasSlope    slope-scaled depth bias
 * @param depthBiasClamp    maximum depth bias
 * @param cascadeCount      number of shadow cascades
 * @param shadowDistance    distance the shadow volume covers
 * @param sunPathRotation   sun path rotation in degrees (matches Iris's value)
 * @param cullFrontFaces    whether to cull front faces (common for shadow acne avoidance)
 */
public record ShadowPassConfig(
        int resolution,
        RenderTargetFormat depthFormat,
        float depthBiasConstant,
        float depthBiasSlope,
        float depthBiasClamp,
        int cascadeCount,
        float shadowDistance,
        float sunPathRotation,
        boolean cullFrontFaces) {

    public ShadowPassConfig {
        if (resolution < 1 || (resolution & (resolution - 1)) != 0) {
            throw new IllegalArgumentException("shadow resolution should be a power of two, got " + resolution);
        }
        if (depthFormat == null || !depthFormat.isDepth()) {
            throw new IllegalArgumentException("shadow map requires a depth format");
        }
        if (cascadeCount < 1) {
            throw new IllegalArgumentException("cascadeCount must be >= 1");
        }
        if (shadowDistance <= 0.0f) {
            throw new IllegalArgumentException("shadowDistance must be positive");
        }
    }

    /**
     * Sensible defaults for a single-cascade pack, using slope-scaled bias to suppress acne.
     *
     * @param resolution shadow map resolution (power of two)
     * @return the default configuration
     */
    public static ShadowPassConfig irisDefault(int resolution) {
        return new ShadowPassConfig(
                resolution, RenderTargetFormat.DEPTH32F,
                1.0f, 1.5f, 0.0f,
                1, 160.0f, 0.0f, true);
    }

    /** @return whether polygon offset (depth bias) is enabled */
    public boolean depthBiasEnabled() {
        return depthBiasConstant != 0.0f || depthBiasSlope != 0.0f;
    }
}
