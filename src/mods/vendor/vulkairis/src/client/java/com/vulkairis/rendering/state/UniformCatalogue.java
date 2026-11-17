package com.vulkairis.rendering.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 10: catalogue of the standard uniforms Vulkairis transports to shader programs.
 *
 * <p>Each entry records the uniform name, GLSL type, update cadence, coordinate space and a
 * short description. The catalogue is the single source of truth for which uniforms are
 * supported; unsupported names are reported explicitly rather than silently bound.</p>
 */
public final class UniformCatalogue {

    public enum Cadence {
        PER_FRAME,
        PER_PASS,
        PER_DRAW
    }

    public record Entry(String name, String glslType, Cadence cadence, String space, String description) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    static {
        add("projectionMatrix", "mat4", Cadence.PER_PASS, "clip", "Projection matrix for the current pass");
        add("viewMatrix", "mat4", Cadence.PER_PASS, "view", "View matrix for the current pass");
        add("modelViewMatrix", "mat4", Cadence.PER_DRAW, "view", "Model-view matrix");
        add("normalMatrix", "mat3", Cadence.PER_DRAW, "view", "Normal matrix");
        add("cameraPosition", "vec3", Cadence.PER_FRAME, "world", "Camera world position");
        add("previousCameraPosition", "vec3", Cadence.PER_FRAME, "world", "Previous frame camera position");
        add("frameTimeCounter", "float", Cadence.PER_FRAME, "none", "Frame time counter in seconds");
        add("dayTime", "float", Cadence.PER_FRAME, "none", "World day time (0..1)");
        add("rainStrength", "float", Cadence.PER_FRAME, "none", "Rain strength (0..1)");
        add("sunPosition", "vec3", Cadence.PER_FRAME, "world", "Sun position in world space");
        add("moonPosition", "vec3", Cadence.PER_FRAME, "world", "Moon position in world space");
        add("shadowLightPosition", "vec3", Cadence.PER_FRAME, "world", "Shadow light position");
        add("near", "float", Cadence.PER_PASS, "view", "Near plane distance");
        add("far", "float", Cadence.PER_PASS, "view", "Far plane distance");
        add("screenSize", "vec2", Cadence.PER_PASS, "pixels", "Current render target size");
        add("viewWidth", "float", Cadence.PER_PASS, "pixels", "View width in pixels");
        add("viewHeight", "float", Cadence.PER_PASS, "pixels", "View height in pixels");
        add("eyeBrightness", "vec2", Cadence.PER_FRAME, "none", "Eye brightness smooth value");
        add("fogStart", "float", Cadence.PER_PASS, "view", "Fog start distance");
        add("fogEnd", "float", Cadence.PER_PASS, "view", "Fog end distance");
    }

    private UniformCatalogue() {
    }

    private static void add(String name, String type, Cadence cadence, String space, String desc) {
        ENTRIES.add(new Entry(name, type, cadence, space, desc));
    }

    /** @return an unmodifiable view of all catalogued uniforms */
    public static List<Entry> all() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** @return the entry for {@code name}, or {@code null} if not catalogued */
    public static Entry find(String name) {
        for (Entry e : ENTRIES) {
            if (e.name().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** @return whether {@code name} is a supported uniform */
    public static boolean isSupported(String name) {
        return find(name) != null;
    }
}