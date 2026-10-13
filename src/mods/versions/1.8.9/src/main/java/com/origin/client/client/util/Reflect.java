package com.origin.client.client.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-soft reflection for the members no Forge event or Mixin accessor reaches
 * (mostly OptiFine / other mods' classes, or a quick read where an accessor
 * would be overkill).
 *
 * WHY TWO NAMES: dev (runClient) runs MCP-named Minecraft, but players run
 * Forge's production SRG names (field_175201_x, func_71410_x ...). Every lookup
 * therefore takes the MCP name AND the SRG name and tries them in order; a
 * lookup with only the MCP name works in dev and silently fails for every
 * player. Prefer a Mixin @Accessor where you can -- its refmap does this
 * mapping at build time.
 *
 * Every lookup is cached (hits AND misses, so a missing member costs one scan
 * per session), walks the superclass chain, setAccessible(true)s, and returns
 * null instead of throwing. The get/set/call helpers swallow everything and
 * return the fallback: a missing member = that feature off, never a crash.
 */
public final class Reflect {
	private static final Object MISSING = new Object();
	private static final ConcurrentHashMap<String, Object> CACHE = new ConcurrentHashMap<>();

	private Reflect() {
	}

	// ---- lookups ----

	/** A field of `owner` (or a superclass) by any of `names` (MCP first, then SRG), or null. */
	public static Field field(Class<?> owner, String... names) {
		if (owner == null || names == null) {
			return null;
		}
		String key = "F" + owner.getName() + "#" + Arrays.toString(names);
		Object hit = CACHE.get(key);
		if (hit != null) {
			return hit == MISSING ? null : (Field) hit;
		}
		Field found = null;
		for (Class<?> c = owner; c != null && found == null; c = c.getSuperclass()) {
			for (String n : names) {
				try {
					Field f = c.getDeclaredField(n);
					f.setAccessible(true);
					found = f;
					break;
				} catch (Throwable ignored) {
					// next name
				}
			}
		}
		CACHE.put(key, found == null ? MISSING : found);
		return found;
	}

	/**
	 * A method of `owner` (or a superclass) by any of `names` with exactly these
	 * parameter types, or null. Usage: {@code Reflect.method(Minecraft.class,
	 * new Class<?>[]{}, "rightClickMouse", "func_147121_ag")}.
	 */
	public static Method method(Class<?> owner, Class<?>[] params, String... names) {
		if (owner == null || names == null) {
			return null;
		}
		Class<?>[] p = params == null ? new Class<?>[0] : params;
		String key = "M" + owner.getName() + "#" + Arrays.toString(names) + Arrays.toString(p);
		Object hit = CACHE.get(key);
		if (hit != null) {
			return hit == MISSING ? null : (Method) hit;
		}
		Method found = null;
		for (Class<?> c = owner; c != null && found == null; c = c.getSuperclass()) {
			for (String n : names) {
				try {
					Method m = c.getDeclaredMethod(n, p);
					m.setAccessible(true);
					found = m;
					break;
				} catch (Throwable ignored) {
					// next name
				}
			}
		}
		CACHE.put(key, found == null ? MISSING : found);
		return found;
	}

	/** A constructor with exactly these parameter types, or null. */
	public static Constructor<?> constructor(Class<?> owner, Class<?>... params) {
		if (owner == null) {
			return null;
		}
		String key = "C" + owner.getName() + Arrays.toString(params);
		Object hit = CACHE.get(key);
		if (hit != null) {
			return hit == MISSING ? null : (Constructor<?>) hit;
		}
		Constructor<?> found = null;
		try {
			found = owner.getDeclaredConstructor(params);
			found.setAccessible(true);
		} catch (Throwable ignored) {
			found = null;
		}
		CACHE.put(key, found == null ? MISSING : found);
		return found;
	}

	/** A class by its binary name (other mods: OptiFine, ...), or null. Never initialises it twice. */
	public static Class<?> type(String... names) {
		if (names == null) {
			return null;
		}
		String key = "T" + Arrays.toString(names);
		Object hit = CACHE.get(key);
		if (hit != null) {
			return hit == MISSING ? null : (Class<?>) hit;
		}
		Class<?> found = null;
		for (String n : names) {
			try {
				found = Class.forName(n, false, Reflect.class.getClassLoader());
				break;
			} catch (Throwable ignored) {
				// next name
			}
		}
		CACHE.put(key, found == null ? MISSING : found);
		return found;
	}

	// ---- field access ----

	/** Reads a field (null target for statics); `fallback` on any failure. */
	@SuppressWarnings("unchecked")
	public static <T> T get(Field f, Object target, T fallback) {
		if (f == null) {
			return fallback;
		}
		try {
			Object v = f.get(target);
			return v == null ? fallback : (T) v;
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** One-shot read: looks the field up by its names on target's class. */
	public static <T> T get(Object target, T fallback, String... names) {
		if (target == null) {
			return fallback;
		}
		return get(field(target.getClass(), names), target, fallback);
	}

	/** Reads an int field; `fallback` on any failure. */
	public static int getInt(Field f, Object target, int fallback) {
		if (f == null) {
			return fallback;
		}
		try {
			return f.getInt(target);
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** Reads a float field; `fallback` on any failure. */
	public static float getFloat(Field f, Object target, float fallback) {
		if (f == null) {
			return fallback;
		}
		try {
			return f.getFloat(target);
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** Reads a boolean field; `fallback` on any failure. */
	public static boolean getBoolean(Field f, Object target, boolean fallback) {
		if (f == null) {
			return fallback;
		}
		try {
			return f.getBoolean(target);
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** Writes a field (null target for statics). True if it was written. Final fields are refused. */
	public static boolean set(Field f, Object target, Object value) {
		if (f == null) {
			return false;
		}
		try {
			f.set(target, value);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	// ---- calls ----

	/** Invokes a method (null target for statics); `fallback` on any failure. */
	@SuppressWarnings("unchecked")
	public static <T> T call(Method m, Object target, T fallback, Object... args) {
		if (m == null) {
			return fallback;
		}
		try {
			Object v = m.invoke(target, args);
			return v == null ? fallback : (T) v;
		} catch (Throwable t) {
			return fallback;
		}
	}

	/** Invokes a void method; true if it ran without throwing. */
	public static boolean run(Method m, Object target, Object... args) {
		if (m == null) {
			return false;
		}
		try {
			m.invoke(target, args);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** Constructs via a cached constructor; null on any failure. */
	public static Object create(Constructor<?> c, Object... args) {
		if (c == null) {
			return null;
		}
		try {
			return c.newInstance(args);
		} catch (Throwable t) {
			return null;
		}
	}
}
