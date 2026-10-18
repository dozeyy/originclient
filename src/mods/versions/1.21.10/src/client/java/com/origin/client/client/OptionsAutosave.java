package com.origin.client.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

// Vanilla game options (options.txt) land on disk the moment the player changes
// one, not only when they leave a settings screen or quit (Will, 2026-10-18:
// "it should save the setting they change the instant they change it"). That
// keeps Settings Sync -- the launcher copies options.txt between versions at
// each launch -- from ever missing a change, even after a crash or a killed
// game. OptionsAutosaveMixin marks a change; the next client tick writes it,
// at most every 250 ms so dragging a slider isn't a file write every frame.
// Origin's own mod settings and HUD positions already save on every change
// (Mods / HudPos); quitting (Alt+F4 included) flushes everything once more in
// OriginClientMod.onClientStopping.
public final class OptionsAutosave {
	private static final long MIN_GAP_MS = 250;

	private static volatile boolean dirty;
	private static long lastWrite;

	/** True while Origin itself has a vanilla option swapped out (Zoom's
	 *  sensitivity, its smooth camera): those are not the player's values, so
	 *  nothing is written until they are put back. OriginClientMod sets it. */
	public static volatile boolean held;

	private OptionsAutosave() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(OptionsAutosave::tick);
	}

	/** A vanilla option changed (OptionsAutosaveMixin). */
	public static void changed() {
		// Options.load() sets every option while Minecraft builds its Options,
		// before mc.options exists: reading the file is not a change.
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && mc.options != null) {
			dirty = true;
		}
	}

	private static void tick(Minecraft client) {
		if (!dirty || held || client.options == null) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastWrite < MIN_GAP_MS) {
			return;
		}
		lastWrite = now;
		dirty = false;
		try {
			client.options.save();
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: could not save game options", t);
		}
	}
}
