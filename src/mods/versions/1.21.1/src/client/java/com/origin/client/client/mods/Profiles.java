package com.origin.client.client.mods;

import java.util.List;

/**
 * Named config profiles — save the whole current loadout (every mod's enabled
 * flag + option values + HUD layout) under a name, then switch between them
 * instantly. Backed by ModsConfig (the snapshots persist in
 * originclient-mods.json under a "profiles" block). The mod menu's Profiles tab
 * is the UI; this is the thin, testable API behind it.
 */
public final class Profiles {
	private Profiles() {
	}

	public static List<String> names() {
		return ModsConfig.profileNames();
	}

	/** Freeze the current settings under `name` (creates or overwrites). */
	public static void save(String name) {
		if (name != null && !name.trim().isEmpty()) {
			ModsConfig.saveProfile(name.trim());
		}
	}

	/** Swap the live settings to the named profile. False if it doesn't exist. */
	public static boolean apply(String name) {
		return ModsConfig.applyProfile(name);
	}

	public static void delete(String name) {
		ModsConfig.deleteProfile(name);
	}

	public static boolean exists(String name) {
		return name != null && names().contains(name.trim());
	}
}
