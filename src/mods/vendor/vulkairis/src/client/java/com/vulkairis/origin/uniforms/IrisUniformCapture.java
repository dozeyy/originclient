package com.vulkairis.origin.uniforms;

import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.uniform.FloatSupplier;
import net.irisshaders.iris.gl.uniform.UniformType;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.joml.Vector4i;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Origin (step 3): a uniform holder that records every uniform Iris declares (name + value source)
 * instead of binding it to a GL program location. Iris's own {@code CommonUniforms} registers into it,
 * so values (camera, time, matrices, weather, view size, ...) are exactly Iris's.
 *
 * <p>{@link #evaluate()} reads every source once per frame into a {@code double[]} per name; the
 * uniform upload then copies those values into each pipeline's std140 block by member name. A source
 * that throws (for example one that queries GL state) is switched off after its first failure.</p>
 */
final class IrisUniformCapture implements DynamicUniformHolder {
    interface Filler {
        void fill(double[] out);
    }

    static final class Entry {
        final String name;
        final double[] value;
        final Filler filler;
        boolean dead;

        Entry(String name, int size, Filler filler) {
            this.name = name;
            this.value = new double[size];
            this.filler = filler;
        }
    }

    final Map<String, Entry> entries = new LinkedHashMap<>();

    private IrisUniformCapture put(String name, int size, Filler filler) {
        if (name != null && filler != null) {
            entries.putIfAbsent(name, new Entry(name, size, filler));
        }
        return this;
    }

    /** Evaluates every live source; returns the names that failed this call (logged by the caller). */
    java.util.List<String> evaluate() {
        java.util.List<String> failed = null;
        for (Entry entry : entries.values()) {
            if (entry.dead) {
                continue;
            }
            try {
                entry.filler.fill(entry.value);
            } catch (Throwable t) {
                entry.dead = true;
                java.util.Arrays.fill(entry.value, 0);
                if (failed == null) {
                    failed = new java.util.ArrayList<>();
                }
                failed.add(entry.name + " (" + t + ")");
            }
        }
        return failed == null ? java.util.List.of() : failed;
    }

    // ---- value adapters ----

    private static Filler of(FloatSupplier s) {
        return out -> out[0] = s.getAsFloat();
    }

    private static Filler of(IntSupplier s) {
        return out -> out[0] = s.getAsInt();
    }

    private static Filler of(DoubleSupplier s) {
        return out -> out[0] = s.getAsDouble();
    }

    private static Filler bool(BooleanSupplier s) {
        return out -> out[0] = s.getAsBoolean() ? 1 : 0;
    }

    private static Filler v2f(Supplier<Vector2f> s) {
        return out -> {
            Vector2f v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
            }
        };
    }

    private static Filler v2i(Supplier<Vector2i> s) {
        return out -> {
            Vector2i v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
            }
        };
    }

    private static Filler v3f(Supplier<Vector3f> s) {
        return out -> {
            Vector3f v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
                out[2] = v.z;
            }
        };
    }

    private static Filler v3i(Supplier<Vector3i> s) {
        return out -> {
            Vector3i v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
                out[2] = v.z;
            }
        };
    }

    private static Filler v3d(Supplier<Vector3d> s) {
        return out -> {
            Vector3d v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
                out[2] = v.z;
            }
        };
    }

    private static Filler v4f(Supplier<Vector4f> s, int components) {
        return out -> {
            Vector4f v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
                out[2] = v.z;
                if (components > 3) {
                    out[3] = v.w;
                }
            }
        };
    }

    private static Filler v4i(Supplier<Vector4i> s) {
        return out -> {
            Vector4i v = s.get();
            if (v != null) {
                out[0] = v.x;
                out[1] = v.y;
                out[2] = v.z;
                out[3] = v.w;
            }
        };
    }

    private static Filler floats(Supplier<float[]> s) {
        return out -> {
            float[] v = s.get();
            if (v != null) {
                int n = Math.min(v.length, out.length);
                for (int i = 0; i < n; i++) {
                    out[i] = v[i];
                }
            }
        };
    }

    private static Filler matrix(Supplier<Matrix4fc> s) {
        float[] scratch = new float[16];
        return out -> {
            Matrix4fc m = s.get();
            if (m != null) {
                m.get(scratch);
                for (int i = 0; i < 16; i++) {
                    out[i] = scratch[i];
                }
            }
        };
    }

    // ---- UniformHolder ----

    @Override
    public IrisUniformCapture uniform1f(UniformUpdateFrequency f, String name, FloatSupplier s) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1f(UniformUpdateFrequency f, String name, IntSupplier s) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1f(UniformUpdateFrequency f, String name, DoubleSupplier s) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1i(UniformUpdateFrequency f, String name, IntSupplier s) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1b(UniformUpdateFrequency f, String name, BooleanSupplier s) {
        return put(name, 1, bool(s));
    }

    @Override
    public IrisUniformCapture uniform2f(UniformUpdateFrequency f, String name, Supplier<Vector2f> s) {
        return put(name, 2, v2f(s));
    }

    @Override
    public IrisUniformCapture uniform2i(UniformUpdateFrequency f, String name, Supplier<Vector2i> s) {
        return put(name, 2, v2i(s));
    }

    @Override
    public IrisUniformCapture uniform3f(UniformUpdateFrequency f, String name, Supplier<Vector3f> s) {
        return put(name, 3, v3f(s));
    }

    @Override
    public IrisUniformCapture uniform3i(UniformUpdateFrequency f, String name, Supplier<Vector3i> s) {
        return put(name, 3, v3i(s));
    }

    @Override
    public IrisUniformCapture uniformTruncated3f(UniformUpdateFrequency f, String name, Supplier<Vector4f> s) {
        return put(name, 3, v4f(s, 3));
    }

    @Override
    public IrisUniformCapture uniform3d(UniformUpdateFrequency f, String name, Supplier<Vector3d> s) {
        return put(name, 3, v3d(s));
    }

    @Override
    public IrisUniformCapture uniform4f(UniformUpdateFrequency f, String name, Supplier<Vector4f> s) {
        return put(name, 4, v4f(s, 4));
    }

    @Override
    public IrisUniformCapture uniform4fArray(UniformUpdateFrequency f, String name, Supplier<float[]> s) {
        return put(name, 64, floats(s));
    }

    @Override
    public IrisUniformCapture uniformMatrix(UniformUpdateFrequency f, String name, Supplier<Matrix4fc> s) {
        return put(name, 16, matrix(s));
    }

    @Override
    public IrisUniformCapture uniformMatrixFromArray(UniformUpdateFrequency f, String name, Supplier<float[]> s) {
        return put(name, 16, floats(s));
    }

    @Override
    public IrisUniformCapture externallyManagedUniform(String name, UniformType type) {
        return this; // set by Iris's own GL code paths, which do not run under Vulkan
    }

    // ---- DynamicUniformHolder (per-draw values in Iris; evaluated per frame here) ----

    @Override
    public IrisUniformCapture uniform1f(String name, FloatSupplier s, ValueUpdateNotifier n) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1f(String name, IntSupplier s, ValueUpdateNotifier n) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1f(String name, DoubleSupplier s, ValueUpdateNotifier n) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform1i(String name, IntSupplier s, ValueUpdateNotifier n) {
        return put(name, 1, of(s));
    }

    @Override
    public IrisUniformCapture uniform2f(String name, Supplier<Vector2f> s, ValueUpdateNotifier n) {
        return put(name, 2, v2f(s));
    }

    @Override
    public IrisUniformCapture uniform2i(String name, Supplier<Vector2i> s, ValueUpdateNotifier n) {
        return put(name, 2, v2i(s));
    }

    @Override
    public IrisUniformCapture uniform3f(String name, Supplier<Vector3f> s, ValueUpdateNotifier n) {
        return put(name, 3, v3f(s));
    }

    @Override
    public IrisUniformCapture uniform4f(String name, Supplier<Vector4f> s, ValueUpdateNotifier n) {
        return put(name, 4, v4f(s, 4));
    }

    @Override
    public IrisUniformCapture uniform4fArray(String name, Supplier<float[]> s, ValueUpdateNotifier n) {
        return put(name, 64, floats(s));
    }

    @Override
    public IrisUniformCapture uniform4i(String name, Supplier<Vector4i> s, ValueUpdateNotifier n) {
        return put(name, 4, v4i(s));
    }

    @Override
    public IrisUniformCapture uniformMatrix(String name, Supplier<Matrix4fc> s, ValueUpdateNotifier n) {
        return put(name, 16, matrix(s));
    }
}
