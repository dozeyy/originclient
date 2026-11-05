package com.origin.client.client.hud;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.BitSet;

/**
 * Keeps the Potion HUD out from under Minecraft's toasts (JG-9 / JP-7). Toasts (advancement, recipe,
 * tutorial, Origin's own Social and FPS Coach pop-ups) stack down the top-right corner in 32 px slots,
 * and HudLayout can only reserve ONE slot for them: at 1366x768 the right column has no room left
 * for more (the scoreboard sidebar and the minimap sit right under the potions). So while toasts
 * cover more than that, the potions step down below the lowest one, and slide back when it leaves.
 * Wherever the player put the piece, it dodges the same way; the Edit HUD screen draws no toasts
 * and shows the piece at its own spot. Pure Java 8, no Minecraft types: shared/ syncs it into the
 * Fabric modules, the pre-1.20 modules carry byte-identical copies, and tools/hud-layout-tests runs
 * it. 1.8.9 has no copy: its one achievement pop-up fits the slot HudLayout reserves.
 *
 * How many slots are taken is read from the toast manager the module passes in
 * (Minecraft.getToasts() / getToastManager() / gui.toastManager()) by field TYPE, not name: the
 * names are obfuscated on most versions and the type is the same on all of them. 1.17+ keeps a
 * BitSet of occupied slots (highest set bit + 1 = slots taken); 1.16.5 keeps a fixed array of
 * visible toasts, one per slot. A manager with neither reads as 0 slots: the potions stay put.
 */
public final class ToastBand {
	private ToastBand() {
	}

	/** Height of one toast slot in GUI px (vanilla's Toast.SLOT_HEIGHT on every version). */
	static final int SLOT = 32;
	/** Width of the toast column (vanilla toasts are 160 px wide; wider ones are rare and brief). */
	static final int WIDTH = 160;
	/** Space between the lowest toast and the potions. */
	static final int GAP = 4;

	private static Class<?> cachedClass;
	private static Field cachedField;   // null with cachedClass set: the manager has no slot field

	/**
	 * The top a box at (x, y), w wide, is drawn at on an sw-wide screen with `slots` toast slots
	 * taken: below the toasts when it reaches into their column and starts higher than their bottom,
	 * else y unchanged.
	 */
	public static double below(double x, double y, double w, int sw, int slots) {
		double bottom = slots * SLOT;
		if (slots <= 0 || x + w <= sw - WIDTH || y >= bottom) {
			return y;
		}
		return bottom + GAP;
	}

	/** Toast slots taken right now (counted from the top), 0 when unknown. Never throws. */
	public static int slots(Object toastManager) {
		if (toastManager == null) {
			return 0;
		}
		try {
			Field f = field(toastManager.getClass());
			if (f == null) {
				return 0;
			}
			Object v = f.get(toastManager);
			if (v instanceof BitSet) {
				return ((BitSet) v).length();
			}
			if (v != null && v.getClass().isArray()) {
				for (int i = Array.getLength(v) - 1; i >= 0; i--) {
					if (Array.get(v, i) != null) {
						return i + 1;
					}
				}
			}
			return 0;
		} catch (Throwable t) {
			return 0;
		}
	}

	// The manager's own BitSet field, else its first object-array field; looked up once per class.
	private static synchronized Field field(Class<?> c) {
		if (c == cachedClass) {
			return cachedField;
		}
		Field bits = null, array = null;
		for (Field f : c.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			if (f.getType() == BitSet.class && bits == null) {
				bits = f;
			} else if (f.getType().isArray() && !f.getType().getComponentType().isPrimitive() && array == null) {
				array = f;
			}
		}
		Field pick = bits != null ? bits : array;
		if (pick != null) {
			try {
				pick.setAccessible(true);
			} catch (Throwable t) {
				pick = null;
			}
		}
		cachedClass = c;
		cachedField = pick;
		return pick;
	}
}
