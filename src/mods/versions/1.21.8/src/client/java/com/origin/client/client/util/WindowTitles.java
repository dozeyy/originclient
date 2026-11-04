package com.origin.client.client.util;

/**
 * The game window's title (G121-12): Origin and the signed-in player instead of vanilla's
 * "Minecraft* 1.21.1 - Singleplayer", so with one game window per account (several at once)
 * the taskbar and Alt+Tab tell them apart. Pure string work (WindowTitleMixin feeds it), so it
 * lives in shared/ and is unit-tested (tools/qol-tests).
 *
 * <p>"Minecraft* 1.21.1 - Singleplayer" + "Steve" -> "Origin 1.21.1 - Steve - Singleplayer".
 * A title that doesn't start with "Minecraft" (another mod changed it) is returned unchanged.
 */
public final class WindowTitles {
	private WindowTitles() {
	}

	public static String origin(String vanilla, String player) {
		if (vanilla == null || !vanilla.startsWith("Minecraft")) {
			return vanilla;
		}
		String rest = vanilla.substring("Minecraft".length());
		if (rest.startsWith("*")) {
			rest = rest.substring(1);   // vanilla's "modded" mark: every Origin game is
		}
		rest = rest.trim();             // "1.21.1" or "1.21.1 - Singleplayer"
		if (player == null || player.isBlank()) {
			return rest.isEmpty() ? "Origin" : "Origin " + rest;
		}
		int dash = rest.indexOf(" - ");
		String version = dash < 0 ? rest : rest.substring(0, dash);
		String where = dash < 0 ? "" : rest.substring(dash);
		return (version.isEmpty() ? "Origin" : "Origin " + version) + " - " + player.trim() + where;
	}
}
