package com.origin.client.client;

import com.origin.client.client.gui.OriginWidgetOwnership;
import com.origin.client.client.mods.Mods;

/**
 * THE one place that answers "should Origin restyle this, or is the player
 * playing plain Minecraft?" Every Origin restyle of a vanilla surface (button
 * skin, menu backdrop, title screen, loading screens, menu blur, ...) asks
 * this class FIRST and does nothing when it says vanilla -- no mixin compares
 * the option strings itself, so a port is "call the predicate", never "learn
 * the option names".
 *
 * <p>Two settings feed it (SETTINGS > General):
 * <ul>
 *   <li><b>Button Style</b> (Origin / Minecraft) -- the widget skin.</li>
 *   <li><b>Main Menu Style</b> (Origin / Vanilla) -- the title screen.</li>
 * </ul>
 *
 * <p>What each combination means (Will, 2026-10-19):
 * <ul>
 *   <li>Button Style = Minecraft alone: every vanilla widget draws as vanilla
 *       ({@link #vanillaButtons}); the other Origin surfaces stay.</li>
 *   <li>Main Menu Style = Vanilla alone: only the title screen is stock
 *       ({@link #vanillaTitle}), exactly as before.</li>
 *   <li>BOTH off ({@link #vanillaMenus}): "vanilla mode" -- the title, the
 *       backdrop behind every menu, the loading and progress screens, the menu
 *       blur, the buttons: all of it is stock Minecraft. Origin's HUD, gameplay
 *       mods and its own windows (mod menu, HUD editor, cosmetics, ...) are not
 *       menus of Minecraft's, so they never change.</li>
 * </ul>
 *
 * <p>An Origin window (any {@code com.origin.*} screen) always keeps the Origin
 * look -- that is the "only inside the actual mod menu" part -- which is what
 * the {@code ...On(screen)} forms are for. The screen is passed as a plain
 * Object so this class touches no Minecraft API and is identical on every
 * version. Reads the live setting each call (a handful of map lookups), so a
 * change in the mod menu applies on the very next frame or screen open.
 *
 * <p>Fail-soft: if the setting can't be read, answer "Origin" (the unchanged
 * default look) instead of throwing into a render path.
 */
public final class OriginStyle {
	/** Mods option keys (SETTINGS > General). Only this class and Mods.java know them. */
	public static final String BUTTON_STYLE = "buttonStyle";
	public static final String MENU_STYLE = "mainMenuStyle";

	private OriginStyle() {
	}

	/** Button Style = Minecraft: widgets draw as vanilla (outside Origin's own windows -- use {@link #vanillaButtonsOn}). */
	public static boolean vanillaButtons() {
		try {
			return "Minecraft".equals(Mods.mode(Mods.GENERAL_ID, BUTTON_STYLE));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Main Menu Style = Vanilla: the title screen is stock (the pre-existing switch). */
	public static boolean vanillaTitle() {
		try {
			return !"Origin".equals(Mods.mode(Mods.GENERAL_ID, MENU_STYLE));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Both styles off: every Minecraft menu is plain vanilla (outside Origin's own windows -- use {@link #vanillaMenusOn}). */
	public static boolean vanillaMenus() {
		return vanillaButtons() && vanillaTitle();
	}

	/** Vanilla buttons for a widget drawn on {@code screen}; false inside an Origin window. */
	public static boolean vanillaButtonsOn(Object screen) {
		return vanillaButtons() && !OriginWidgetOwnership.isOriginOwnClass(screen);
	}

	/** Vanilla menu chrome (backdrop, loading, blur) for {@code screen}; false inside an Origin window. */
	public static boolean vanillaMenusOn(Object screen) {
		return vanillaMenus() && !OriginWidgetOwnership.isOriginOwnClass(screen);
	}
}
