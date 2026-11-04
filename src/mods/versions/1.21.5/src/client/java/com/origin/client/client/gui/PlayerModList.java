package com.origin.client.client.gui;

import com.origin.client.OriginClient;
import net.minecraft.client.gui.screens.Screen;

/**
 * The player's full mod list: what the title screen's and the pause menu's "Mods" buttons open
 * (Will, 2026-11-04: "the mods button on the main menu and mods button in pause menu should be the
 * Mods List mod that shows all the person's mods, all those mods and the settings for them").
 * On Fabric that is Mod Menu's list (every loaded mod, each with its own settings button). Origin's
 * own menu sits beside it as the "Origin" button, so each button has one job.
 *
 * Mod Menu is not a compile dependency on most modules (the launcher installs it next to the game;
 * PerformanceModCatalog pins one build per version), so its screen is opened by name: package
 * com.terraformersmc since Mod Menu 2 (1.17+), io.github.prospector on the 1.16 line. Plain Java 8
 * so one text compiles on every Fabric module: shared/ syncs it into 1.20+ and 26.x, and the
 * pre-1.20 modules (.no-shared-sync) carry the same file.
 */
public final class PlayerModList {
	private static final String[] LIST_SCREENS = {
			"com.terraformersmc.modmenu.gui.ModsScreen",
			"io.github.prospector.modmenu.gui.ModsScreen"};
	private static final String[] PACKAGES = {"com.terraformersmc.modmenu.", "io.github.prospector.modmenu."};
	private static boolean warned;

	private PlayerModList() {
	}

	/**
	 * Mod Menu's list with {@code parent} as its back screen. When Mod Menu is missing (the launcher
	 * always installs it, but a player can remove it) the button still works: it opens Origin's menu,
	 * logged once, rather than doing nothing.
	 */
	public static Screen screen(Screen parent) {
		for (String name : LIST_SCREENS) {
			try {
				return (Screen) Class.forName(name).getConstructor(Screen.class).newInstance(parent);
			} catch (Throwable ignored) {
				// not this Mod Menu line (or not installed): try the next
			}
		}
		if (!warned) {
			warned = true;
			OriginClient.LOGGER.warn("Origin: Mod Menu's mod list is not available, so Mods opens Origin's menu");
		}
		return new OriginModMenuScreen();
	}

	/**
	 * Mod Menu's own "Mods" button on a vanilla screen: its widget class, or (older Mod Menu lines
	 * that add a plain button) a button worded "Mods" or "Mods (n)". Origin's own buttons are told
	 * apart by the caller, never by this.
	 */
	public static boolean isModMenuButton(Object widget, String label) {
		String cls = widget.getClass().getName();
		for (String p : PACKAGES) {
			if (cls.startsWith(p)) {
				return true;
			}
		}
		String l = label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT);
		return l.equals("mods") || l.startsWith("mods (");
	}
}
