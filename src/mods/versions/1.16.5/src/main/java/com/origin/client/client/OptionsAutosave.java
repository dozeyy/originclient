package com.origin.client.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

// Vanilla game options (options.txt) land on disk the moment the player changes
// one, not only when they leave a settings screen or quit (Will, 2026-10-18:
// "it should save the setting they change the instant they change it"). That
// keeps Settings Sync -- the launcher copies options.txt between versions at
// each launch -- from ever missing a change, even after a crash or a killed
// game. Origin's own mod settings and HUD positions already save on every
// change (Mods / HudPos); quitting (Alt+F4 included) flushes everything once
// more in OriginClientMod.onClientStopping.
//
// Before 1.19 there is no single setter to hook (1.19+ has OptionInstance.set,
// see the shared OptionsAutosaveMixin): options are plain fields on Options,
// written from dozens of places. So this watches them instead -- every 5 ticks
// (a quarter second) it fingerprints every Options field (keybinds by their
// saved form) and writes options.txt when the fingerprint moves. Fields the file
// never stores (F5, F3, F1, smooth camera) are left out (transientFields).
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

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(OptionsAutosave::tick);
	}

	private static void tick(Minecraft client) {
		if (client.options == null || ++ticks < EVERY_TICKS) {
			return;
		}
		ticks = 0;
		try {
			long now = fingerprint(client.options);
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
			client.options.save();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: could not save game options", t);
		}
	}

	private static long fingerprint(Options options) throws IllegalAccessException {
		if (fields == null) {
			List<Field> list = new ArrayList<>();
			for (Field f : Options.class.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers())) {
					f.setAccessible(true);
					list.add(f);
				}
			}
			Field[] found = list.toArray(new Field[0]);
			skip = transientFields(options, found);
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
	private static int part(Field f, Options options) throws IllegalAccessException {
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
		if (v instanceof KeyMapping[]) {
			int part = 1;
			for (KeyMapping k : (KeyMapping[]) v) {
				part = 31 * part + k.saveString().hashCode();
			}
			return part;
		}
		if (v instanceof Object[]) {
			return java.util.Arrays.hashCode((Object[]) v);
		}
		return v == null ? 0 : v.hashCode();
	}

	// F5's camera, F3's debug screen, F1's hidden HUD and smooth camera (Zoom,
	// Snaplook) are plain Options fields that options.txt never stores. While they
	// counted, each of those presses looked like a settings change and cost a full
	// synchronous options save (and a client-settings packet to the server) on the
	// render thread, mid-fight. Their reflected names are obfuscated in a real game
	// (intermediary under Fabric), so each one is found by probing: nudge it through the
	// compiled field access, mark the field that moved, put the value straight back.
	// One pass on the client thread; nothing draws in between.
	private static boolean[] transientFields(Options o, Field[] fs) {
		boolean[] out = new boolean[fs.length];
		try {
			CameraType cam = o.getCameraType();
			CameraType[] cams = CameraType.values();
			probe(o, fs, out, () -> o.setCameraType(cams[(cam.ordinal() + 1) % cams.length]), () -> o.setCameraType(cam));
			boolean debug = o.renderDebug;
			probe(o, fs, out, () -> o.renderDebug = !debug, () -> o.renderDebug = debug);
			boolean hide = o.hideGui;
			probe(o, fs, out, () -> o.hideGui = !hide, () -> o.hideGui = hide);
			boolean smooth = o.smoothCamera;
			probe(o, fs, out, () -> o.smoothCamera = !smooth, () -> o.smoothCamera = smooth);
		} catch (Throwable t) {
			// fail soft: a field the probe missed only costs the old extra save
			com.origin.client.OriginClient.LOGGER.warn("Origin: could not sort out the unsaved game options", t);
		}
		return out;
	}

	private static void probe(Options o, Field[] fs, boolean[] out, Runnable nudge, Runnable restore)
			throws IllegalAccessException {
		Object[] before = new Object[fs.length];
		for (int i = 0; i < fs.length; i++) {
			before[i] = fs[i].get(o);
		}
		nudge.run();
		try {
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
