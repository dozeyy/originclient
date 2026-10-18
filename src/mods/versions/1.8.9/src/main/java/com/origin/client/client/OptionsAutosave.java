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
// writes options.txt when the fingerprint moves. A change that isn't saved to
// the file (F3, F5) just costs one harmless extra write. Called from
// OriginClientMod.onClientTick (Forge's client tick).
public final class OptionsAutosave {
	private static final int EVERY_TICKS = 5;

	/** True while Origin itself has a vanilla option swapped out (Zoom's
	 *  sensitivity, its smooth camera): those are not the player's values, so
	 *  nothing is written until they are put back. OriginClientMod sets it. */
	public static volatile boolean held;

	private static Field[] fields;
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
			fields = list.toArray(new Field[0]);
		}
		long h = 1;
		for (Field f : fields) {
			Object v = f.get(options);
			int part;
			if (v instanceof KeyBinding[]) {
				part = 1;
				for (KeyBinding k : (KeyBinding[]) v) {
					part = 31 * part + k.getKeyCode();
				}
			} else if (v instanceof Object[]) {
				part = java.util.Arrays.hashCode((Object[]) v);
			} else {
				part = v == null ? 0 : v.hashCode();
			}
			h = h * 1000003L + part;
		}
		return h;
	}
}
