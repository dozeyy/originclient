package com.vulkairis.rendering.exec;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Builds the light-space view/projection for the shadow pass from the sun direction.
 *
 * <p>Iris calls {@code setShadowProjection} with a light-space matrix; previously that call was
 * discarded, so shadow passes had no camera. This computes a stable orthographic projection looking
 * from the sun toward the camera with a ground-plane-aware up vector to avoid gimbal artifacts when
 * the sun is near vertical.</p>
 */
public final class ShadowProjection {

    /**
     * @param view           world-to-light-space matrix
     * @param projection     light-space orthographic projection
     * @param viewProjection projection * view
     */
    public record Result(Matrix4f view, Matrix4f projection, Matrix4f viewProjection) {
    }

    private ShadowProjection() {
    }

    /**
     * Builds the light-space matrices.
     *
     * @param sunDirection direction from the scene toward the sun (need not be normalized)
     * @param center       the point the shadow volume is centered on (usually the camera)
     * @param radius       half-extent of the shadow volume
     * @param distance     distance from {@code center} to place the light eye
     * @return the light-space matrices
     */
    public static Result build(Vector3f sunDirection, Vector3f center, float radius, float distance) {
        if (sunDirection == null || center == null) {
            throw new IllegalArgumentException("sunDirection and center are required");
        }
        if (radius <= 0.0f || distance <= 0.0f) {
            throw new IllegalArgumentException("radius and distance must be positive");
        }

        Vector3f direction = new Vector3f(sunDirection).normalize();
        Vector3f eye = new Vector3f(center).fma(distance, direction);

        // Choose an up vector that is never parallel to the view direction, so the look-at does not
        // degenerate when the sun is directly overhead.
        Vector3f up = Math.abs(direction.y) > 0.99f
                ? new Vector3f(0.0f, 0.0f, 1.0f)
                : new Vector3f(0.0f, 1.0f, 0.0f);

        Matrix4f view = new Matrix4f().lookAt(eye, center, up);
        Matrix4f projection = new Matrix4f().ortho(
                -radius, radius, -radius, radius, 0.05f, distance + radius, false);
        Matrix4f viewProjection = new Matrix4f(projection).mul(view);
        return new Result(view, projection, viewProjection);
    }
}
