package com.origin.client.client.hud;

import java.util.HashMap;
import java.util.Map;

/**
 * THE default HUD layout (Will, 2026-11-03: "Yes, no overlaps"): the spot every HUD piece starts in, so
 * that with every piece turned on none of them sit on each other or on the hotbar, the chat, the boss bar
 * or the scoreboard sidebar. One table for every version (mandate 2: the same layout everywhere, 1.21.1
 * is the design truth): shared/ syncs this file into the Fabric modules, and the pre-1.20 modules and
 * 1.8.9 (.no-shared-sync) carry byte-identical copies. Plain Java 8, no Minecraft types, so
 * tools/hud-layout-tests compiles it on its own and checks, at 1366x768 and 1920x1080 with Minecraft's
 * auto GUI scale (455x256 and 480x270 GUI px) and at the bigger GUI sizes, that no two default boxes
 * overlap. Moving a spot here means re-running that test (it holds each piece's planned size).
 *
 * The plan, at the smallest of those screens:
 *   top left      FPS, CPS, Keystrokes, Coordinates down the edge; Armor beside Keystrokes
 *   top centre    Boss Bar, Direction, then a time row (Clock | Day | Stopwatch) and Saturation
 *   top right     Server IP, Potions under it, Item Counter left of them
 *   right middle  the Scoreboard sidebar (vanilla's spot); the Minimap shares it (singleplayer only,
 *                 sidebars are a server thing)
 *   bottom right  Ping, Reach, Combo, Memory; Cooldowns right of the hotbar; Bed Wars resources above
 *   bottom left   Pack Display and the sprint state, under the chat
 *   left, under the corner stack: Party, then Portal Calculator / Bed Wars teammates. At the auto GUI
 *   scale these three reach into the chat area (there is no free space left there); from GUI 640x360 up
 *   they clear it.
 *
 * A player's own placement always wins. originclient-mods.json stores a piece's spot only once it is
 * written (dragged, resized, backing changed, or carried in from the old starter config); a piece with
 * no stored spot reads this table. A stored spot equal to one of the defaults Origin shipped before
 * (PREVIOUS) was never placed by the player, so it reads the new default too: only the read changes,
 * the file (and Settings Sync's copy of it) is never rewritten here.
 */
public final class HudLayout {
	private HudLayout() {
	}

	// id -> {anchor, dx, dy, scale}. anchor = row*3+col: 0..8 (TL, TC, TR, ML, MC, MR, BL, BC, BR); dx/dy are
	// GUI px from that anchor (see left/top). 4 px from the screen edge everywhere.
	private static final Map<String, double[]> SPOTS = new HashMap<String, double[]>();
	// id -> defaults shipped before this table, each {anchor, dx, dy, scale}: older code defaults (every
	// module had the same ones) and the old starter config's "hud" block (default-mods-config.json).
	private static final Map<String, double[][]> PREVIOUS = new HashMap<String, double[][]>();
	// An id this table doesn't know (a piece added without a spot) starts top-left instead of failing.
	private static final double[] UNKNOWN = {0, 4, 4, 1.0};

	static {
		// top left: one column down the edge, Armor beside Keystrokes
		spot("fps", 0, 4, 4, 1.0);
		// CPS's own Scale option ships at 0.5 (the old starter config drew it at 2.5x); 2.0 here makes its
		// text the same size as the readouts around it.
		spot("cps", 0, 4, 16, 2.0);
		spot("keystrokes", 0, 4, 30, 1.0);
		spot("coords", 0, 4, 111, 1.0);
		spot("armorhud", 0, 77, 4, 1.0);
		// under the corner stack (reach into the chat area at the auto GUI scale; see the plan above)
		spot("party", 0, 4, 129, 1.0);
		spot("portalcalc", 0, 4, 182, 1.0);   // shares with bwteammates: no nether portals in a Bed Wars game
		spot("bwteammates", 0, 4, 182, 1.0);

		// top centre: vanilla's boss bar spot, the compass under it, then the time row
		spot("bossbar", 1, 0, 3, 1.0);
		spot("directionhud", 1, 0, 22, 1.0);
		spot("clock", 1, -54, 52, 1.0);
		spot("daycounter", 1, 0, 52, 1.0);
		spot("stopwatch", 1, 54, 52, 1.0);
		spot("saturationhud", 1, 0, 69, 1.0);

		// top right
		spot("serveraddress", 2, -4, 4, 1.0);
		spot("potionhud", 2, -4, 24, 1.0);
		spot("itemcounter", 2, -104, 68, 1.0);

		// centre: vanilla's title spot
		spot("titles", 4, 0, -6, 1.0);

		// right middle: vanilla's sidebar spot; the singleplayer-only Minimap shares it
		spot("scoreboard", 5, -3, 0, 1.0);
		spot("minimap", 5, -4, 0, 1.0);

		// bottom left, under the chat (chat's lines end 40 px above the bottom)
		spot("sprintstate", 6, 4, -4, 1.0);
		spot("packdisplay", 6, 4, -18, 1.0);

		// bottom centre: the hotbar group
		spot("locatorbar", 7, 0, -22, 1.0);
		spot("cooldowns", 7, 119, -4, 1.0);   // 4 px right of the hotbar, where vanilla's attack indicator sits

		// bottom right: a right-aligned column, the two lowest rows narrow enough to clear Cooldowns
		spot("ping", 8, -4, -55, 1.0);
		spot("reach", 8, -4, -38, 1.0);
		spot("combo", 8, -4, -21, 1.0);
		spot("memory", 8, -4, -4, 1.0);
		spot("bwresources", 8, -84, -40, 1.0);

		previous("fps", new double[]{0, 6, 6, 1.0}, new double[]{1, 2.5, 4, 1.0});
		previous("cps", new double[]{0, 6, 22, 1.0}, new double[]{2, -4, 4, 2.5});
		previous("coords", new double[]{0, 6, 38, 1.0}, new double[]{0, 4, 4, 1.0});
		previous("keystrokes", new double[]{3, 6, -40, 1.0}, new double[]{0, 4, 82.56281957633308, 1.0});
		previous("armorhud", new double[]{6, 6, -24, 1.0}, new double[]{3, 4, 3.776479181884554, 1.0});
		previous("serveraddress", new double[]{2, -6, 6, 1.0}, new double[]{0, 4, 24.029218407596787, 1.0});
		previous("potionhud", new double[]{2, -6, 160, 1.0}, new double[]{2, -6, 6, 1.0},
				new double[]{2, -4, 160, 1.0}, new double[]{2, -4, 26.06647187728268, 1.0});
		previous("sprintstate", new double[]{6, 6, -6, 1.0});
		previous("cooldowns", new double[]{7, 0, -62, 1.0});
		previous("party", new double[]{3, 6, 56, 1.0});
		previous("clock", new double[]{0, 6, 100, 1.0});
		previous("stopwatch", new double[]{0, 6, 114, 1.0});
		previous("daycounter", new double[]{0, 6, 128, 1.0});
		previous("ping", new double[]{0, 6, 142, 1.0});
		previous("memory", new double[]{0, 6, 156, 1.0});
		previous("combo", new double[]{3, 6, 20, 1.0});
		previous("reach", new double[]{3, 6, 34, 1.0});
		previous("saturationhud", new double[]{7, 140, -36, 1.0});
		previous("packdisplay", new double[]{8, -6, -6, 1.0});
		previous("itemcounter", new double[]{2, -6, 244, 1.0}, new double[]{2, -6, 60, 1.0});
		previous("directionhud", new double[]{1, 0, 4, 1.0});
		previous("bossbar", new double[]{1, 0, 2, 1.0});
		previous("minimap", new double[]{2, -6, 34, 1.0}, new double[]{2, -6, 6, 1.0});
		previous("bwresources", new double[]{8, -6, -24, 1.0});
		previous("bwteammates", new double[]{3, 6, -100, 1.0}, new double[]{3, 6, -40, 1.0});
		previous("portalcalc", new double[]{0, 6, 170, 1.0});
	}

	private static void spot(String id, int anchor, double dx, double dy, double scale) {
		SPOTS.put(id, new double[]{anchor, dx, dy, scale});
	}

	private static void previous(String id, double[]... spots) {
		PREVIOUS.put(id, spots);
	}

	/** The piece's default {anchor, dx, dy, scale} (a copy; top-left for an id the table doesn't know). */
	public static double[] spot(String id) {
		double[] s = SPOTS.get(id);
		return (s != null ? s : UNKNOWN).clone();
	}

	/** True when the table has a default spot for this piece. */
	public static boolean has(String id) {
		return SPOTS.containsKey(id);
	}

	/**
	 * The spot a piece is drawn at, {anchor, dx, dy, scale, bg}. stored = its entry in originclient-mods.json
	 * (anchor, dx, dy, scale[, bg]) or null when none is stored; d = its default {anchor, dx, dy, scale}
	 * (spot(id) for every registered piece). Placement equal to a previous default reads as d; a scale the
	 * player changed on such an entry (scroll / resize handle) is kept, and so is the backing opacity.
	 * Anything else is the player's own placement and is returned unchanged.
	 */
	public static double[] resolve(String id, double[] stored, double[] d) {
		if (stored == null || stored.length < 4) {
			return new double[]{d[0], d[1], d[2], d[3], 0};
		}
		double bg = stored.length >= 5 ? stored[4] : 0;
		double[][] prev = PREVIOUS.get(id);
		if (prev != null) {
			for (double[] p : prev) {
				if (stored[0] == p[0] && stored[1] == p[1] && stored[2] == p[2]) {
					return new double[]{d[0], d[1], d[2], stored[3] == p[3] ? d[3] : stored[3], bg};
				}
			}
		}
		return new double[]{stored[0], stored[1], stored[2], stored[3], bg};
	}

	/** Left edge in GUI px of a w-wide piece at (anchor, dx) on a screenW-wide screen, kept fully on screen. */
	public static double left(int anchor, double dx, int screenW, double w) {
		int col = anchor % 3;
		double base = col == 0 ? 0 : col == 1 ? (screenW - w) / 2.0 : screenW - w;
		return onScreen(base + dx, screenW - w);
	}

	/** Top edge in GUI px of an h-tall piece at (anchor, dy) on a screenH-tall screen, kept fully on screen. */
	public static double top(int anchor, double dy, int screenH, double h) {
		int row = anchor / 3;
		double base = row == 0 ? 0 : row == 1 ? (screenH - h) / 2.0 : screenH - h;
		return onScreen(base + dy, screenH - h);
	}

	// [0, max]; a piece bigger than the screen pins to 0; NaN (bad config) -> 0
	private static double onScreen(double v, double max) {
		if (Double.isNaN(v)) {
			return 0;
		}
		return Math.max(0, Math.min(v, max));
	}
}
