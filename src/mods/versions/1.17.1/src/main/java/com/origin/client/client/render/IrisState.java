package com.origin.client.client.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Iris's public v0 API, bound once by reflection (the project reaches Iris only this way, so a
 * missing or changed Iris turns the shader-speed features off instead of failing to load).
 * Each handle is null when Iris or that API method is absent. Callers use invokeExact on these
 * static finals, which the JIT folds to a direct call.
 *
 * <p>PER-VERSION (pre-1.20): Iris 1.2.7+mc1.17.1 still lives in net.coderbot.iris, but its public API
 * class is already net.irisshaders.iris.api.v0.IrisApi with isRenderingShadowPass()Z and
 * isShaderPackInUse()Z (javap of the jar, 2026-10-20).
 */
public final class IrisState {
	private IrisState() {
	}

	/** IrisApi.getInstance().isRenderingShadowPass() -> boolean (true only inside Iris's shadow pass). */
	public static final MethodHandle SHADOW_PASS = bind("isRenderingShadowPass");

	/** IrisApi.getInstance().isShaderPackInUse() -> boolean. */
	public static final MethodHandle PACK_IN_USE = bind("isShaderPackInUse");

	private static MethodHandle bind(String method) {
		try {
			Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object instance = api.getMethod("getInstance").invoke(null);
			if (instance == null) {
				return null;
			}
			return MethodHandles.publicLookup()
					.findVirtual(api, method, MethodType.methodType(boolean.class))
					.bindTo(instance);
		} catch (Throwable t) {
			return null;
		}
	}
}
