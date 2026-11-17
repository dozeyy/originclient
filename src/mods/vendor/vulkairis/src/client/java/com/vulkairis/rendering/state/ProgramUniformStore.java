package com.vulkairis.rendering.state;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-shader uniform upload state.
 *
 * <p>Iris drives uniforms through {@code glUniform*} calls whose values were previously discarded by
 * no-op overwrites. This store records the uploaded values so a later frame/pass state layer can
 * push them into the shader's reflected uniform block. Values are keyed by the uniform location the
 * caller passed, exactly as Iris does.</p>
 */
public final class ProgramUniformStore {

    /**
     * One uploaded uniform value.
     *
     * @param kind   a short type tag such as {@code "float"}, {@code "vec4"}, {@code "mat4"}
     * @param floats the float payload (empty for integer uniforms)
     * @param ints   the integer payload (empty for float uniforms)
     */
    public record UniformValue(String kind, float[] floats, int[] ints) {

        public static UniformValue floats(String kind, float... values) {
            return new UniformValue(kind, values.clone(), new int[0]);
        }

        public static UniformValue ints(String kind, int... values) {
            return new UniformValue(kind, new float[0], values.clone());
        }
    }

    private final Map<Integer, UniformValue> values = new ConcurrentHashMap<>();

    public void setFloat(int location, float v0) {
        values.put(location, UniformValue.floats("float", v0));
    }

    public void setVec2(int location, float v0, float v1) {
        values.put(location, UniformValue.floats("vec2", v0, v1));
    }

    public void setVec3(int location, float v0, float v1, float v2) {
        values.put(location, UniformValue.floats("vec3", v0, v1, v2));
    }

    public void setVec4(int location, float v0, float v1, float v2, float v3) {
        values.put(location, UniformValue.floats("vec4", v0, v1, v2, v3));
    }

    public void setInt(int location, int v0) {
        values.put(location, UniformValue.ints("int", v0));
    }

    public void setIVec2(int location, int v0, int v1) {
        values.put(location, UniformValue.ints("ivec2", v0, v1));
    }

    public void setIVec3(int location, int v0, int v1, int v2) {
        values.put(location, UniformValue.ints("ivec3", v0, v1, v2));
    }

    public void setIVec4(int location, int v0, int v1, int v2, int v3) {
        values.put(location, UniformValue.ints("ivec4", v0, v1, v2, v3));
    }

    /**
     * Stores a 4x4 matrix from the first 16 floats of {@code matrix}. Reads from an absolute index so
     * the caller's buffer position is not relied upon.
     *
     * @param location the uniform location
     * @param matrix   a buffer containing at least 16 floats
     */
    public void setMatrix4(int location, FloatBuffer matrix) {
        if (matrix == null) {
            return;
        }
        float[] copy = new float[16];
        int base = matrix.position();
        for (int i = 0; i < 16; i++) {
            copy[i] = matrix.get(base + i);
        }
        values.put(location, UniformValue.floats("mat4", copy));
    }

    /** @return the uploaded value at {@code location}, if any */
    public Optional<UniformValue> get(int location) {
        return Optional.ofNullable(values.get(location));
    }

    /** @return whether a value has been uploaded at {@code location} */
    public boolean hasUniform(int location) {
        return values.containsKey(location);
    }

    /** @return the number of tracked uniforms */
    public int size() {
        return values.size();
    }

    /** Clears all uploaded uniform values (e.g. between programs). */
    public void clear() {
        values.clear();
    }
}
