package com.vulkairis.bridge;

import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The standard Iris uniform mapping (Phase 10): which engine value backs each well-known
 * uniform, and how often it changes.
 *
 * <ul>
 *   <li>{@code ModelViewMat}/{@code ProjMat} — camera matrices from Iris, per frame.</li>
 *   <li>{@code ViewSize} — framebuffer size in pixels, on resize.</li>
 *   <li>{@code FrameTimeCounter} — seconds since first frame, per frame.</li>
 *   <li>{@code CameraPosition} — world-space camera, per frame.</li>
 *   <li>{@code Frame} — monotonically increasing frame index, per frame.</li>
 * </ul>
 *
 * <p>Per-program values (fog color, blindness, custom uniforms) are <em>not</em> standard:
 * they travel through the isolated frame store keyed by program and location.</p>
 */
public final class StandardUniforms {

    /** Update cadence of a standard uniform. */
    public enum Cadence {
        PER_FRAME,
        ON_RESIZE,
        PER_PROGRAM
    }

    /** Standard uniform identities with their source of truth and cadence. */
    public enum Standard {
        MODEL_VIEW("ModelViewMat", "Iris camera model-view", Cadence.PER_FRAME, 16),
        PROJECTION("ProjMat", "Iris camera projection (Vulkan depth-corrected on upload)",
                Cadence.PER_FRAME, 16),
        VIEW_SIZE("ViewSize", "framebuffer size in pixels", Cadence.ON_RESIZE, 2),
        FRAME_TIME("FrameTimeCounter", "seconds since first frame", Cadence.PER_FRAME, 1),
        CAMERA_POSITION("CameraPosition", "world-space camera position", Cadence.PER_FRAME, 3),
        FRAME_INDEX("Frame", "monotonic frame counter", Cadence.PER_FRAME, 1);

        private final String uniformName;
        private final String source;
        private final Cadence cadence;
        private final int floats;

        Standard(String uniformName, String source, Cadence cadence, int floats) {
            this.uniformName = uniformName;
            this.source = source;
            this.cadence = cadence;
            this.floats = floats;
        }

        public String uniformName() {
            return uniformName;
        }

        public String source() {
            return source;
        }

        public Cadence cadence() {
            return cadence;
        }

        public int floats() {
            return floats;
        }
    }

    private StandardUniforms() {
    }

    /**
     * Snapshots the current standard values from the bridge.
     *
     * @param bridge   the uniform bridge (matrices, resolution, time)
     * @param camera   world-space camera position
     * @param frame    monotonic frame counter
     * @return uniform name to float payload, in standard order
     */
    public static Map<String, float[]> snapshot(UniformBridge bridge, Vector3f camera, long frame) {
        Map<String, float[]> values = new LinkedHashMap<>();
        float[] modelView = new float[16];
        float[] projection = new float[16];
        if (bridge != null) {
            bridge.getModelViewMatrix().get(modelView);
            bridge.getProjectionMatrix().get(projection);
        }
        values.put(Standard.MODEL_VIEW.uniformName(), modelView);
        values.put(Standard.PROJECTION.uniformName(), projection);
        values.put(Standard.VIEW_SIZE.uniformName(), new float[]{
                bridge != null ? bridge.getScreenWidth() : 0,
                bridge != null ? bridge.getScreenHeight() : 0});
        values.put(Standard.FRAME_TIME.uniformName(),
                new float[]{bridge != null ? bridge.getFrameTime() : 0.0f});
        values.put(Standard.CAMERA_POSITION.uniformName(), camera != null
                ? new float[]{camera.x, camera.y, camera.z} : new float[3]);
        values.put(Standard.FRAME_INDEX.uniformName(), new float[]{frame});
        return values;
    }
}
