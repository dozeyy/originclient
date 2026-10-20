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
 * <p>One setting feeds it (SETTINGS > General): <b>Main Menu Style</b>
 * (Origin / Vanilla). Minecraft's own menus always use stock widgets and the
 * stock font ({@link #vanillaButtons}, Will 2026-10-19); Main Menu Style picks
 * whether Origin dresses them:
 * <ul>
 *   <li>Origin: the Origin title layout, this version's blurred panorama behind
 *       every menu, Origin's loading and progress screens.</li>
 *   <li>Vanilla ({@link #vanillaMenus}): "vanilla mode" -- the title, the
 *       backdrop behind every menu, the loading and progress screens, the menu
 *       blur: all of it is stock Minecraft. Origin's HUD, gameplay mods and its
 *       own windows (mod menu, HUD editor, cosmetics, ...) are not menus of
 *       Minecraft's, so they never change.</li>
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
	/** Mods option key (SETTINGS > General). Only this class and Mods.java know it. */
	public static final String MENU_STYLE = "mainMenuStyle";

	private OriginStyle() {
	}

	/**
	 * Minecraft's own menus always draw stock widgets and the stock font (Will,
	 * 2026-10-19: "use normal minecraft font for the menus ... use normal buttons").
	 * The Button Style option and the glass skin for Minecraft's menus are gone;
	 * Origin's own windows keep their look ({@link #vanillaButtonsOn}). Kept as a
	 * predicate so every widget mixin still asks one place.
	 */
	public static boolean vanillaButtons() {
		return true;
	}

	/** Main Menu Style = Vanilla: the title screen is stock (the pre-existing switch). */
	public static boolean vanillaTitle() {
		try {
			return !"Origin".equals(Mods.mode(Mods.GENERAL_ID, MENU_STYLE));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Main Menu Style = Vanilla: every Minecraft menu is plain vanilla (outside Origin's own windows -- use {@link #vanillaMenusOn}). */
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
