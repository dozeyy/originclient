package com.origin.flywheelvulkan.backend;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

import com.origin.flywheelvulkan.FlywheelVulkanClient;

import net.fabricmc.loader.api.FabricLoader;
import net.vulkanmod.vulkan.texture.VulkanImage;

/**
 * Origin's VulkaIris fork, reached without a compile dependency: its
 * {@code com.vulkairis.origin.api.ForeignPackPrograms} hands out the active shader pack's
 * gbuffers_block / shadow programs for geometry we draw ourselves. Gated on the mod being loaded
 * and on its {@code API_VERSION}; anything else leaves {@link #AVAILABLE} false and origin:vulkan
 * draws exactly as without a pack (phase 1). {@code -Dflywheelvulkan.packs=false} turns it off.
 *
 * <p>Method handles with exact types: no boxing, no reflection cost per frame.
 */
final class PackBridge {
	private static final String API_CLASS = "com.vulkairis.origin.api.ForeignPackPrograms";
	private static final int API_VERSION = 1;

	static final String ROLE_BLOCK = "block";
	static final String ROLE_SHADOW = "shadow";
	static final int SAMPLER_PACK = 0;
	static final int SAMPLER_ATLAS = 1;
	static final int SAMPLER_LIGHTMAP = 2;

	static final boolean AVAILABLE;
	private static final MethodHandle GENERATION;
	private static final MethodHandle PACK_ACTIVE;
	private static final MethodHandle PREPARE;
	private static final MethodHandle MARK_FAILED;
	private static final MethodHandle VERTEX;
	private static final MethodHandle FRAGMENT;
	private static final MethodHandle NAME;
	private static final MethodHandle UBO_BINDINGS;
	private static final MethodHandle UBO_SIZES;
	private static final MethodHandle SAMPLER_BINDINGS;
	private static final MethodHandle SAMPLER_KIND;
	private static final MethodHandle IMAGE;
	private static final MethodHandle WRITE;
	private static final MethodHandle ADD_CASTER;
	private static final MethodHandle REMOVE_CASTER;

	static {
		MethodHandle[] h = new MethodHandle[15];
		boolean ok = false;
		String why = null;
		if (!Boolean.parseBoolean(System.getProperty("flywheelvulkan.packs", "true"))) {
			why = "turned off (-Dflywheelvulkan.packs=false)";
		} else if (!FabricLoader.getInstance().isModLoaded("vulkairis")) {
			why = null; // no VulkaIris: nothing to say, phase 1 is the whole story
		} else {
			try {
				Class<?> c = Class.forName(API_CLASS, true, PackBridge.class.getClassLoader());
				int version = c.getField("API_VERSION").getInt(null);
				if (version != API_VERSION) {
					throw new IllegalStateException("ForeignPackPrograms API " + version + ", expected " + API_VERSION);
				}
				MethodHandles.Lookup l = MethodHandles.publicLookup();
				Class<?> o = Object.class;
				h[0] = l.findStatic(c, "generation", MethodType.methodType(int.class));
				h[1] = l.findStatic(c, "packActive", MethodType.methodType(boolean.class));
				h[2] = l.findStatic(c, "prepare", MethodType.methodType(o, String.class, int.class));
				h[3] = l.findStatic(c, "markFailed", MethodType.methodType(void.class, String.class, int.class, String.class));
				h[4] = l.findStatic(c, "vertexSource", MethodType.methodType(String.class, o));
				h[5] = l.findStatic(c, "fragmentSource", MethodType.methodType(String.class, o));
				h[6] = l.findStatic(c, "programName", MethodType.methodType(String.class, o));
				h[7] = l.findStatic(c, "uboBindings", MethodType.methodType(int[].class, o));
				h[8] = l.findStatic(c, "uboSizes", MethodType.methodType(int[].class, o));
				h[9] = l.findStatic(c, "samplerBindings", MethodType.methodType(int[].class, o));
				h[10] = l.findStatic(c, "samplerKind", MethodType.methodType(int.class, o, int.class));
				h[11] = l.findStatic(c, "image", MethodType.methodType(VulkanImage.class, o, int.class));
				h[12] = l.findStatic(c, "writeUniforms", MethodType.methodType(void.class, o, int.class, ByteBuffer.class, int.class));
				h[13] = l.findStatic(c, "addShadowCaster", MethodType.methodType(void.class, Runnable.class));
				h[14] = l.findStatic(c, "removeShadowCaster", MethodType.methodType(void.class, Runnable.class));
				ok = true;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				why = "VulkaIris is loaded but its pack API is not usable: " + e;
			}
		}
		AVAILABLE = ok;
		GENERATION = h[0];
		PACK_ACTIVE = h[1];
		PREPARE = h[2];
		MARK_FAILED = h[3];
		VERTEX = h[4];
		FRAGMENT = h[5];
		NAME = h[6];
		UBO_BINDINGS = h[7];
		UBO_SIZES = h[8];
		SAMPLER_BINDINGS = h[9];
		SAMPLER_KIND = h[10];
		IMAGE = h[11];
		WRITE = h[12];
		ADD_CASTER = h[13];
		REMOVE_CASTER = h[14];
		if (ok) {
			FlywheelVulkanClient.LOGGER.info("[flywheel-vulkan] VulkaIris pack API {} found: Flywheel geometry draws through the shader pack", API_VERSION);
		} else if (why != null) {
			FlywheelVulkanClient.LOGGER.warn("[flywheel-vulkan] shader-pack drawing off: {}", why);
		}
	}

	private PackBridge() {
	}

	private static RuntimeException rethrow(Throwable t) {
		return t instanceof RuntimeException r ? r : new IllegalStateException(t);
	}

	static int generation() {
		try {
			return (int) GENERATION.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static boolean packActive() {
		try {
			return (boolean) PACK_ACTIVE.invokeExact();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static Object prepare(String role, int set) {
		try {
			return (Object) PREPARE.invokeExact(role, set);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void markFailed(String role, int set, String reason) {
		try {
			MARK_FAILED.invokeExact(role, set, reason);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static String vertexSource(Object program) {
		try {
			return (String) VERTEX.invokeExact(program);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static String fragmentSource(Object program) {
		try {
			return (String) FRAGMENT.invokeExact(program);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static String programName(Object program) {
		try {
			return (String) NAME.invokeExact(program);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int[] uboBindings(Object program) {
		try {
			return (int[]) UBO_BINDINGS.invokeExact(program);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int[] uboSizes(Object program) {
		try {
			return (int[]) UBO_SIZES.invokeExact(program);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int[] samplerBindings(Object program) {
		try {
			return (int[]) SAMPLER_BINDINGS.invokeExact(program);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static int samplerKind(Object program, int index) {
		try {
			return (int) SAMPLER_KIND.invokeExact(program, index);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static VulkanImage image(Object program, int index) {
		try {
			return (VulkanImage) IMAGE.invokeExact(program, index);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void writeUniforms(Object program, int binding, ByteBuffer buf, int size) {
		try {
			WRITE.invokeExact(program, binding, buf, size);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void addShadowCaster(Runnable caster) {
		try {
			ADD_CASTER.invokeExact(caster);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	static void removeShadowCaster(Runnable caster) {
		try {
			REMOVE_CASTER.invokeExact(caster);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
}
