package com.origin.client.client.render;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Iris's public v0 API, bound once by reflection (the project reaches Iris only this way, so a
 * missing or changed Iris turns the shader-speed features off instead of failing to load).
 * Each handle is null when Iris or that API method is absent. Callers use invokeExact on these
 * static finals, which the JIT folds to a direct call.
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
