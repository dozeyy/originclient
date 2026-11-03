package com.origin.client.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

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

	/** A vanilla option Origin keeps overridden for as long as a mod is on (the
	 *  Chat mod's scale and opacities). Every options.txt write -- this autosave,
	 *  vanilla's own settings screens, quitting -- swaps the player's own values in
	 *  for exactly the length of the write (OptionsSaveMixin), so the file only
	 *  ever holds what the player chose and turning the mod off loses nothing. */
	public interface Swap {
		/** Put the player's own values in (just before the write). */
		void playerValues(Options options);

		/** Put Origin's values back (just after the write). */
		void originValues(Options options);
	}

	private static final List<Swap> SWAPS = new CopyOnWriteArrayList<>();
	/** Set while a Swap runs: those set() calls are not changes. */
	private static boolean swapping;

	private OptionsAutosave() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(OptionsAutosave::tick);
	}

	public static void addSwap(Swap swap) {
		SWAPS.add(swap);
	}

	/** Options.save() HEAD (OptionsSaveMixin). */
	public static void beforeSave(Options options) {
		swap(options, true);
	}

	/** Options.save() RETURN (OptionsSaveMixin). */
	public static void afterSave(Options options) {
		swap(options, false);
	}

	private static void swap(Options options, boolean playerValues) {
		swapping = true;
		try {
			for (Swap o : SWAPS) {
				try {
					if (playerValues) {
						o.playerValues(options);
					} else {
						o.originValues(options);
					}
				} catch (Throwable t) {
					// mandate 4: a failed swap costs the file one value, never the save
					com.origin.client.OriginClient.LOGGER.warn("Origin: could not swap an option around the options save", t);
				}
			}
		} finally {
			swapping = false;
		}
	}

	/** A vanilla option changed (OptionsAutosaveMixin). */
	public static void changed() {
		if (swapping) {
			return;
		}
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
