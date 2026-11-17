package com.vulkairis.bridge;

import com.vulkairis.rendering.state.FrameState;
import com.vulkairis.rendering.state.ProgramUniformStore;
import com.vulkairis.rendering.state.UniformFrameStore;
import com.vulkairis.translation.ShaderReflection;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Phase D per-frame uniforms: snapshot once per frame, then write per pass.
 *
 * <p>Semantics reimplemented (not copied): one snapshot per frame captures every engine
 * value packs can observe (standard matrices/size/time/camera from {@link UniformBridge}
 * via {@link StandardUniforms}, plus frame extras: sun/moon direction, fog, clip planes,
 * world time). Each pass then resolves its own reflected uniforms type-from-declaration
 * (reflection carries the GLSL type, which decides how many floats a value must supply),
 * falling back to {@code uniform./variable.} custom expressions evaluated by
 * {@link CustomUniformExpression}. Nothing here invents a general expression language,
 * and nothing touches the GL thread: results land in the existing
 * {@link UniformFrameStore} keyed by program and location.</p>
 */
public final class TerrainUniforms {

    /** One pass worth of resolved values plus names no value could be found for. */
    public record PassValues(Map<String, float[]> values, List<String> unresolved) {
    }

    private TerrainUniforms() {
    }

    /**
     * Snapshot-once entry point: captures the frame's engine values exactly once; every pass
     * of the frame reuses the returned map.
     *
     * @param frame per-frame state (camera, sun/moon, fog, clip, timing)
     * @param bridge standard uniform source (matrices, resolution, frame time)
     * @param camera world-space camera (may be {@code null})
     * @param frameIndex monotonic frame counter
     * @return uniform name to float payload, in stable order
     */
    public static Map<String, float[]> snapshotOnce(FrameState frame, UniformBridge bridge,
                                                    Vector3f camera, long frameIndex) {
        Map<String, float[]> snapshot = new LinkedHashMap<>(
                StandardUniforms.snapshot(bridge, camera, frameIndex));
        if (frame != null) {
            snapshot.put("SunDirection", new float[]{
                    frame.getSunDirection().x, frame.getSunDirection().y, frame.getSunDirection().z});
            snapshot.put("MoonDirection", new float[]{
                    frame.getMoonDirection().x, frame.getMoonDirection().y, frame.getMoonDirection().z});
            snapshot.put("FogColor", new float[]{
                    frame.getFogColor().x, frame.getFogColor().y, frame.getFogColor().z});
            snapshot.put("FogDensity", new float[]{frame.getFogDensity()});
            snapshot.put("NearFar", new float[]{frame.getNearPlane(), frame.getFarPlane()});
            snapshot.put("WorldTime", new float[]{frame.getWorldTimeSeconds()});
            snapshot.put("DeltaTime", new float[]{frame.getDeltaTimeSeconds()});
        } else {
            snapshot.putIfAbsent("SunDirection", new float[]{0.0f, -1.0f, 0.0f});
            snapshot.putIfAbsent("MoonDirection", new float[]{0.0f, 1.0f, 0.0f});
            snapshot.putIfAbsent("FogColor", new float[]{0.0f, 0.0f, 0.0f});
            snapshot.putIfAbsent("FogDensity", new float[]{0.0f});
            snapshot.putIfAbsent("NearFar", new float[]{0.05f, 1000.0f});
            snapshot.putIfAbsent("WorldTime", new float[]{0.0f});
            snapshot.putIfAbsent("DeltaTime", new float[]{0.0f});
        }
        // Iris alias names packs also use.
        snapshot.putIfAbsent("cameraPosition", snapshot.get("CameraPosition"));
        snapshot.putIfAbsent("frameTimeCounter", snapshot.get("FrameTimeCounter"));
        snapshot.putIfAbsent("viewWidth", new float[]{snapshot.get("ViewSize")[0]});
        snapshot.putIfAbsent("viewHeight", new float[]{snapshot.get("ViewSize")[1]});
        return Map.copyOf(snapshot);
    }

    /**
     * Per-pass write: resolves every reflected uniform of the pass from the snapshot,
     * type-from-declaration.
     *
     * <p>Resolution order per uniform: snapshot (engine catalog, including aliases), then the
     * caller-supplied custom expression for that name. A resolved value is checked against the
     * declared type's float count; short values are reported unresolved rather than padded
     * with garbage.</p>
     *
     * @param reflection reflected pass metadata (may be {@code null})
     * @param snapshot the frame snapshot from {@link #snapshotOnce}
     * @param customExpressions custom expression per uniform name (may be {@code null})
     * @return resolved values plus unresolved names
     */
    public static PassValues resolvePassValues(ShaderReflection reflection,
                                               Map<String, float[]> snapshot,
                                               Map<String, String> customExpressions) {
        Map<String, float[]> values = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        if (reflection == null) {
            return new PassValues(values, List.of("no reflection for pass"));
        }
        Map<String, float[]> catalog = snapshot == null ? Map.of() : snapshot;
        for (Map.Entry<String, ShaderReflection.UniformInfo> entry
                : reflection.getUniforms().entrySet()) {
            String name = entry.getKey();
            String type = entry.getValue() == null ? "float" : entry.getValue().type();
            float[] direct = lookup(catalog, name);
            if (direct != null && hasEnoughFloats(direct, type)) {
                values.put(name, copyOf(direct, floatsFor(type)));
                continue;
            }
            String expression = customExpressions == null ? null : customExpressions.get(name);
            if (expression != null) {
                CustomUniformExpression.Result result =
                        CustomUniformExpression.eval(expression, catalog);
                if (result.ok()) {
                    float[] expanded = expandScalar(result.value(), type);
                    if (expanded != null) {
                        values.put(name, expanded);
                        continue;
                    }
                    unresolved.add(name + " (expression result does not fit " + type + ")");
                    continue;
                }
                unresolved.add(name + " (" + result.error() + ")");
                continue;
            }
            if (direct != null) {
                unresolved.add(name + " (needs " + floatsFor(type) + " floats for "
                        + type + ", has " + direct.length + ")");
            } else {
                unresolved.add(name);
            }
        }
        return new PassValues(Map.copyOf(values), List.copyOf(unresolved));
    }

    /**
     * Publishes resolved pass values into the per-frame store.
     *
     * @param programId virtual program id
     * @param passValues resolved values from {@link #resolvePassValues}
     * @param store the current-frame store
     * @param locationOf resolves a uniform name to its location ({@code < 0} skips the write)
     * @return locations written
     */
    public static List<Integer> publishToStore(int programId, PassValues passValues,
                                               UniformFrameStore store,
                                               Function<String, Integer> locationOf) {
        List<Integer> written = new ArrayList<>();
        if (passValues == null || store == null || locationOf == null) {
            return List.copyOf(written);
        }
        for (Map.Entry<String, float[]> entry : passValues.values().entrySet()) {
            int location;
            try {
                location = locationOf.apply(entry.getKey());
            } catch (Throwable t) {
                continue;
            }
            if (location < 0) {
                continue;
            }
            float[] payload = entry.getValue();
            store.write(programId, location,
                    ProgramUniformStore.UniformValue.floats("terrain", payload));
            written.add(location);
        }
        return List.copyOf(written);
    }

    private static float[] lookup(Map<String, float[]> catalog, String name) {
        float[] direct = catalog.get(name);
        if (direct != null) {
            return direct;
        }
        // Case-insensitive alias scan (small catalogs only; keeps pack naming slop working).
        for (Map.Entry<String, float[]> entry : catalog.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** @return floats a declared GLSL type consumes (matrices expanded, arrays use base x 1) */
    public static int floatsFor(String type) {
        if (type == null) {
            return 1;
        }
        String base = type.trim();
        // Strip array suffix: "float[4]" -> base float, count handled by caller length.
        int bracket = base.indexOf('[');
        String element = bracket >= 0 ? base.substring(0, bracket).trim() : base;
        return switch (element) {
            case "float", "int", "bool", "uint" -> 1;
            case "vec2", "ivec2", "bvec2", "uvec2" -> 2;
            case "vec3", "ivec3", "bvec3", "uvec3" -> 3;
            case "vec4", "ivec4", "bvec4", "uvec4", "mat2" -> 4;
            case "mat3" -> 9;
            case "mat4" -> 16;
            default -> 1;
        };
    }

    private static boolean hasEnoughFloats(float[] payload, String type) {
        return payload != null && payload.length >= floatsFor(type);
    }

    private static float[] copyOf(float[] payload, int count) {
        float[] copy = new float[Math.max(1, count)];
        int take = Math.min(copy.length, payload.length);
        System.arraycopy(payload, 0, copy, 0, take);
        return copy;
    }

    private static float[] expandScalar(float scalar, String type) {
        int count = floatsFor(type);
        float[] expanded = new float[Math.max(1, count)];
        expanded[0] = scalar;
        return expanded;
    }
}
