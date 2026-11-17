package com.vulkairis.vulkan.pipeline;

/**
 * Phase D shadow culling: pure, device-free keep/discard decisions for the shadow pass.
 *
 * <p>Semantics reimplemented (not copied): the shadow pass renders casters inside the
 * shadow volume only. Anything beyond the configured shadow distance cannot affect the
 * shadow map and is culled; translucent receivers that neither cast nor receive are
 * skipped. One-frame-late sampling (see {@code ShadowFrameLag}) does not change culling:
 * cull against the current frame's volume, sample the previous frame's map.</p>
 */
public final class ShadowCulling {

    private ShadowCulling() {
    }

    /**
     * Distance-based caster cull.
     *
     * @param distanceSquared squared distance from the shadow volume center
     * @param shadowDistance configured shadow radius (must be positive)
     * @return {@code true} when the object is outside the volume and must be culled
     * @throws IllegalArgumentException when {@code shadowDistance} is not positive
     */
    public static boolean cullByDistance(double distanceSquared, float shadowDistance) {
        if (!(shadowDistance > 0.0f) || !Float.isFinite(shadowDistance)) {
            throw new IllegalArgumentException(
                    "shadowDistance must be positive, got " + shadowDistance);
        }
        if (!(distanceSquared >= 0.0)) {
            return true;
        }
        double radius = shadowDistance;
        return distanceSquared > radius * radius;
    }

    /**
     * Whether a chunk section participates in the shadow pass.
     *
     * @param cullByDistance result of {@link #cullByDistance(double, float)}
     * @param castsShadow whether the section contains shadow casters
     * @param shadowPassEnabled whether the pack enables the shadow pass at all
     * @return {@code true} when the section must be drawn into the shadow map
     */
    public static boolean shouldRenderInShadowPass(boolean cullByDistance, boolean castsShadow,
                                                   boolean shadowPassEnabled) {
        return shadowPassEnabled && castsShadow && !cullByDistance;
    }

    /**
     * Validates a shadow configuration without a GPU.
     *
     * @param resolution shadow map resolution (square, power of two, positive)
     * @param shadowDistance shadow radius (positive, finite)
     * @return problems found; empty when the configuration is usable
     */
    public static java.util.List<String> validate(int resolution, float shadowDistance) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (resolution < 1 || (resolution & (resolution - 1)) != 0) {
            problems.add("shadow resolution " + resolution + " must be a positive power of two");
        }
        if (!(shadowDistance > 0.0f) || !Float.isFinite(shadowDistance)) {
            problems.add("shadowDistance must be positive, got " + shadowDistance);
        }
        return java.util.List.copyOf(problems);
    }
}
