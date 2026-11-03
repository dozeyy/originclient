package com.origin.client.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

// Vanilla game options (options.txt) land on disk the moment the player changes
// one, not only when they leave a settings screen or quit (Will, 2026-10-18:
// "it should save the setting they change the instant they change it"). That
// keeps Settings Sync -- the launcher copies options.txt between versions at
// each launch -- from ever missing a change, even after a crash or a killed
// game. Origin's own mod settings and HUD positions already save on every
// change (Mods / HudPos); quitting (Alt+F4 included) flushes everything once
// more in OriginClientMod.onClientStopping (the JVM shutdown hook).
//
// 1.8.9's GameSettings has no single setter to hook (and OptiFine replaces the
// class), so this watches it instead -- every 5 ticks (a quarter second) it
// fingerprints every GameSettings field (keybinds by their key codes) and
// writes options.txt when the fingerprint moves. Fields the file never
// stores (F5, F3, F1, smooth camera) are left out (transientFields). Called from
// OriginClientMod.onClientTick (Forge's client tick).
public final class OptionsAutosave {
	private static final int EVERY_TICKS = 5;

	/** True while Origin itself has a vanilla option swapped out (Zoom's
	 *  sensitivity, its smooth camera): those are not the player's values, so
	 *  nothing is written until they are put back. OriginClientMod sets it. */
	public static volatile boolean held;

	private static Field[] fields;
	/** Per field: true = not a saved setting, left out of the fingerprint (transientFields). */
	private static boolean[] skip;
	private static boolean primed;
	private static long saved;
	private static int ticks;

	private OptionsAutosave() {
	}

	public static void tick(Minecraft client) {
		if (client.gameSettings == null || ++ticks < EVERY_TICKS) {
			return;
		}
		ticks = 0;
		try {
			long now = fingerprint(client.gameSettings);
			if (!primed) {
				primed = true;     // what was loaded from options.txt
				saved = now;
				return;
			}
			// held: Zoom's values are in there right now; once they are put back
			// the fingerprint is the player's own again (and usually unchanged)
			if (now == saved || held) {
				return;
			}
			saved = now;
			client.gameSettings.saveOptions();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: could not save game options", t);
		}
	}

	private static long fingerprint(GameSettings options) throws IllegalAccessException {
		if (fields == null) {
			List<Field> list = new ArrayList<Field>();
			for (Field f : GameSettings.class.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);
					list.add(f);
				}
			}
			Field[] found = list.toArray(new Field[0]);
			skip = transientFields(options, found);
			int left = 0;
			for (boolean b : skip) {
				left += b ? 1 : 0;
			}
			// once per session: 4 = camera, debug screen, hidden HUD, smooth camera all found
			com.origin.client.OriginClient.LOGGER.info("[OriginAutosave] unsaved options left out: {} of {}", left, found.length);
			fields = found;
		}
		long h = 1;
		for (int i = 0; i < fields.length; i++) {
			if (skip[i]) {
				continue;
			}
			h = h * 1000003L + part(fields[i], options);
		}
		return h;
	}

	// Primitives are read unboxed (same hash as the boxed value): this runs four times
	// a second for the whole session, so it should not make garbage.
	private static int part(Field f, GameSettings options) throws IllegalAccessException {
		Class<?> t = f.getType();
		if (t == int.class) {
			return f.getInt(options);
		}
		if (t == boolean.class) {
			return f.getBoolean(options) ? 1231 : 1237;
		}
		if (t == float.class) {
			return Float.floatToIntBits(f.getFloat(options));
		}
		if (t == double.class) {
			long b = Double.doubleToLongBits(f.getDouble(options));
			return (int) (b ^ (b >>> 32));
		}
		Object v = f.get(options);
		if (v instanceof KeyBinding[]) {
			int part = 1;
			for (KeyBinding k : (KeyBinding[]) v) {
				part = 31 * part + k.getKeyCode();
			}
			return part;
		}
		if (v instanceof Object[]) {
			return java.util.Arrays.hashCode((Object[]) v);
		}
		return v == null ? 0 : v.hashCode();
	}

	// F5's camera, F3's debug screen, F1's hidden HUD and smooth camera (Zoom,
	// Snaplook) are plain GameSettings fields that options.txt never stores. While they
	// counted, each of those presses looked like a settings change and cost a full
	// synchronous options save (and a client-settings packet to the server) on the
	// render thread, mid-fight. Their reflected names are obfuscated in a real game
	// (SRG under Forge), so each one is found by probing: nudge it through the
	// compiled field access, mark the field that moved, put the value straight back.
	// One pass on the client thread; nothing draws in between.
	private static boolean[] transientFields(GameSettings o, Field[] fs) {
		boolean[] out = new boolean[fs.length];
		try {
			int cam = o.thirdPersonView;
			probe(o, fs, out, () -> o.thirdPersonView = (cam + 1) % 3, () -> o.thirdPersonView = cam);
			boolean debug = o.showDebugInfo;
			probe(o, fs, out, () -> o.showDebugInfo = !debug, () -> o.showDebugInfo = debug);
			boolean hide = o.hideGUI;
			probe(o, fs, out, () -> o.hideGUI = !hide, () -> o.hideGUI = hide);
			boolean smooth = o.smoothCamera;
			probe(o, fs, out, () -> o.smoothCamera = !smooth, () -> o.smoothCamera = smooth);
		} catch (Throwable t) {
			// fail soft: a field the probe missed only costs the old extra save
			com.origin.client.OriginClient.LOGGER.warn("Origin: could not sort out the unsaved game options", t);
		}
		return out;
	}

	private static void probe(GameSettings o, Field[] fs, boolean[] out, Runnable nudge, Runnable restore)
			throws IllegalAccessException {
		Object[] before = new Object[fs.length];
		for (int i = 0; i < fs.length; i++) {
			before[i] = fs[i].get(o);
		}
		// restore runs whatever happens after the first change, so a throw can never
		// leave the player's camera, debug screen or HUD switched
		try {
			nudge.run();
			for (int i = 0; i < fs.length; i++) {
				if (!java.util.Objects.equals(before[i], fs[i].get(o))) {
					out[i] = true;
				}
			}
		} finally {
			restore.run();
		}
	}
}
