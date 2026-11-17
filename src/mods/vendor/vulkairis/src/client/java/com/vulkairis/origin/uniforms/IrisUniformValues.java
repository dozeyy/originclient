package com.vulkairis.origin.uniforms;

import com.vulkairis.origin.composite.IrisPassSource;
import kroppeb.stareval.expression.Expression;
import kroppeb.stareval.function.FunctionReturn;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.BooleanCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.FloatCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.IntCachedUniform;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Origin (step 3): Iris's real uniform values for the active pack, refreshed once per frame.
 *
 * <p>On a pack change it registers Iris's {@code CommonUniforms} (everything a pack can read without
 * a GL program: camera, time, matrices, view size, weather, fog, held item, ...) into an
 * {@link IrisUniformCapture}, and builds the pack's own custom uniforms ({@code uniform.*} lines in
 * shaders.properties) on top of the same inputs, as Iris's pipeline does. {@link #beginFrame()} then
 * evaluates them; {@link #lookup(String)} hands a value to the uniform upload by member name.</p>
 *
 * <p>Render thread only. Never throws: a failure logs once and leaves that value at zero.</p>
 */
public final class IrisUniformValues {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Uniforms");
    private static final double[] MISSING = new double[0];

    private static ProgramSet cachedSet;
    private static IrisUniformCapture capture;
    private static CustomUniforms custom;
    private static FrameUpdateNotifier notifier;
    private static final FunctionReturn RET = new FunctionReturn();
    private static final Map<String, double[]> CUSTOM_CACHE = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static boolean announced;

    private IrisUniformValues() {
    }

    /** Once per frame, before the world draws: rebuilds on a pack change, then evaluates every value. */
    public static void beginFrame() {
        try {
            ProgramSet set = IrisPassSource.currentSet();
            if (set != cachedSet) {
                rebuild(set);
            }
            if (capture == null) {
                return;
            }
            notifier.onNewFrame();
            CUSTOM_CACHE.clear();
            if (custom != null) {
                try {
                    custom.update();
                } catch (Throwable t) {
                    warnOnce("custom-update", "Pack custom uniforms stopped updating: " + t);
                    custom = null;
                }
            }
            for (String failed : capture.evaluate()) {
                warnOnce("value:" + failed, "Uniform switched off under Vulkan: " + failed);
            }
        } catch (Throwable t) {
            warnOnce("beginFrame", "Uniform refresh failed: " + t);
        }
    }

    private static void rebuild(ProgramSet set) {
        cachedSet = set;
        capture = null;
        custom = null;
        CUSTOM_CACHE.clear();
        if (set == null) {
            return;
        }
        FrameUpdateNotifier frameNotifier = new FrameUpdateNotifier();
        IrisUniformCapture holder = new IrisUniformCapture();
        var idMap = set.getPack().getIdMap();
        var directives = set.getPackDirectives();
        CommonUniforms.addCommonUniforms(holder, idMap, directives, frameNotifier, FogMode.PER_VERTEX);
        notifier = frameNotifier;
        capture = holder;
        try {
            custom = set.getPack().customUniforms.build(
                    h -> CommonUniforms.addNonDynamicUniforms(h, idMap, directives, frameNotifier));
        } catch (Throwable t) {
            warnOnce("custom-build", "Pack custom uniforms unavailable: " + t);
        }
        if (!announced) {
            announced = true;
            LOGGER.info("[Vulkairis/Uniforms] {} Iris uniforms captured, custom uniforms {}",
                    holder.entries.size(), custom != null ? "on" : "off");
        }
    }

    /** The current value for {@code name}, or {@code null} when neither Iris nor the pack defines it. */
    public static double[] lookup(String name) {
        // Origin (step 6): Iris fills shadowModelView/shadowProjection from its ShadowRenderer, which never
        // runs under VulkanMod; Vulkairis's own shadow pass computes them with Iris's math instead.
        double[] shadow = com.vulkairis.origin.shadow.ShadowPass.uniform(name);
        if (shadow != null) {
            return shadow;
        }
        IrisUniformCapture c = capture;
        if (c != null) {
            IrisUniformCapture.Entry entry = c.entries.get(name);
            if (entry != null) {
                return entry.value;
            }
        }
        if (custom == null) {
            return null;
        }
        double[] cached = CUSTOM_CACHE.get(name);
        if (cached == null) {
            cached = readCustom(name);
            CUSTOM_CACHE.put(name, cached);
        }
        return cached == MISSING ? null : cached;
    }

    private static double[] readCustom(String name) {
        try {
            if (!custom.hasVariable(name)) {
                return MISSING;
            }
            Expression expression = custom.getVariable(name);
            if (!(expression instanceof CachedUniform uniform)) {
                return MISSING;
            }
            uniform.writeTo(RET);
            if (uniform instanceof FloatCachedUniform) {
                return new double[]{RET.floatReturn};
            }
            if (uniform instanceof IntCachedUniform) {
                return new double[]{RET.intReturn};
            }
            if (uniform instanceof BooleanCachedUniform) {
                return new double[]{RET.booleanReturn ? 1 : 0};
            }
            Object o = RET.objectReturn;
            if (o instanceof Vector2f v) {
                return new double[]{v.x, v.y};
            }
            if (o instanceof Vector3f v) {
                return new double[]{v.x, v.y, v.z};
            }
            if (o instanceof Vector4f v) {
                return new double[]{v.x, v.y, v.z, v.w};
            }
            if (o instanceof Vector2i v) {
                return new double[]{v.x, v.y};
            }
            if (o instanceof Vector3i v) {
                return new double[]{v.x, v.y, v.z};
            }
            if (o instanceof Matrix4fc m) {
                float[] f = m.get(new float[16]);
                double[] d = new double[16];
                for (int i = 0; i < 16; i++) {
                    d[i] = f[i];
                }
                return d;
            }
        } catch (Throwable t) {
            warnOnce("custom:" + name, "Custom uniform " + name + " unreadable: " + t);
        }
        return MISSING;
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) {
            LOGGER.warn("[Vulkairis/Uniforms] {}", message);
        }
    }
}
