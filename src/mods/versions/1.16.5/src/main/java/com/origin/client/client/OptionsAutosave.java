package com.origin.client.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
// saved form) and writes options.txt when the fingerprint moves. A change
// that isn't saved to the file (F3, F5) just costs one harmless extra write.
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
			fields = list.toArray(new Field[0]);
		}
		long h = 1;
		for (Field f : fields) {
			Object v = f.get(options);
			int part;
			if (v instanceof KeyMapping[]) {
				part = 1;
				for (KeyMapping k : (KeyMapping[]) v) {
					part = 31 * part + k.saveString().hashCode();
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
