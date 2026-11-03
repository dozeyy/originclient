import com.origin.client.client.hud.HudLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default HUD layout (hud/HudLayout) keeps every HUD piece in its own spot (Will, 2026-11-03: "Yes, no
 * overlaps"). Run by run.py, which passes every element id the live modules register.
 *
 * The game can't run here, so each piece has a PLANNED size: what it measures in game with Origin's starter
 * settings (default-mods-config.json) and its widest everyday content, in GUI px at scale 1, plus the 3 px
 * backing panel its renderer draws around the box when Show Background is on. Text widths use Minecraft's
 * default font advances (most glyphs 6, "i.:,;|!" 2, "l" 3, "It[] " 4, "fk<>()" 5). When a piece's measure()
 * or starter settings change, change its size here. The real frames are the dev-shots `hud_readouts` shot.
 *
 * Checked at 1366x768 and 1920x1080 with Minecraft's auto GUI scale (455x256, 480x270 GUI px) and at GUI
 * 640x360, 683x384, 960x540: no two pieces overlap, and none sits on the chat (320 px wide, its 90 px of
 * lines ending 40 px above the bottom) or the hotbar group (hotbar, offhand slot, hearts/food rows).
 * Allowed: pieces that are never on screen together (CONTEXTS), and the few listed in AUTO_SCALE_CHAT that
 * reach into the chat area at the auto GUI scale only (no free space left there; see HudLayout's plan).
 */
public final class HudLayoutTests {
	private static int failures;

	private static void fail(String what) {
		System.out.println("FAIL " + what);
		failures++;
	}

	// ---- planned sizes -------------------------------------------------------------------------------------

	static int fw(String s) {
		int w = 0;
		for (char c : s.toCharArray()) {
			if ("!,.:;|'i".indexOf(c) >= 0) {
				w += 2;
			} else if ("l`".indexOf(c) >= 0) {
				w += 3;
			} else if ("It[] ".indexOf(c) >= 0) {
				w += 4;
			} else if ("fk<>(){}\"*".indexOf(c) >= 0) {
				w += 5;
			} else {
				w += 6;
			}
		}
		return w;
	}

	/** One piece: planned box w x h, backing margin m, and the context it shows in. */
	static final class Plan {
		final String id;
		final double w, h, m;
		final char context;

		Plan(String id, double w, double h, double m, char context) {
			this.id = id;
			this.w = w;
			this.h = h;
			this.m = m;
			this.context = context;
		}
	}

	// Contexts: E everyday (any world), V a scoreboard sidebar (servers), S singleplayer only (the Minimap
	// measures 0x0 on servers), B a Hypixel Bed Wars game only, P near a nether portal (none in Bed Wars),
	// X situational but in any world (party, item counter), T vanilla's own title slot (a few seconds when a
	// server sends a title; hidden in Edit HUD by default): not checked, nothing can avoid the screen centre.
	// H the separate Locator Bar: it is part of the hotbar group by design (only checked against pieces).
	static final String[] NEVER_TOGETHER = {"SV", "SB", "PB"};

	static final Map<String, Plan> PLAN = new LinkedHashMap<String, Plan>();

	static void plan(String id, double w, double h, double m, char context) {
		PLAN.put(id, new Plan(id, w, h, m, context));
	}

	/** Readouts.textSize: text + 2 wide, 11 tall, backing on by default (textReadout's Show Background). */
	static void text(String id, String widest) {
		plan(id, fw(widest) + 2, 11, 3, 'E');
	}

	static {
		plan("fps", fw("999 FPS") + 2, 11, 0, 'E');                 // starter config: no background
		plan("cps", (fw("12 | 10") + 2) * 0.5, 11 * 0.5, 0, 'E');    // its own Scale 0.5 (x HudLayout scale 2)
		plan("coords", fw("XYZ: -1234 / 64 / -1234") + 2, 12, 0, 'E'); // starter: one Horizontal line
		plan("keystrokes", 70 * 0.99, 78 * 0.99, 0, 'E');           // 70 x (64 + spacebar 10 + 4) x scale 1.1 x box 0.9
		plan("armorhud", 19 + 34, 5 * 19, 0, 'E');                  // Vertical, durability Right: 4 armour + held item
		plan("serveraddress", 15 + fw("Singleplayer") + 2, 13, 3, 'E');
		plan("potionhud", 22 + fw("Fire Resistance 9:28"), 2 * 20, 0, 'E'); // two effects (the Edit HUD sample)
		text("clock", "12:59 PM");
		text("stopwatch", "12:34.56");
		text("daycounter", "Day 1234");
		text("ping", "Ping: 142ms");
		text("memory", "Mem: 100%");
		text("combo", "Combo: 12");
		text("reach", "Reach: 3.00");
		text("saturationhud", "Saturation: 5.0");
		plan("packdisplay", 20 + fw("Faithful 32x") + 2, 18, 3, 'E'); // icon row 18 tall; a much longer name reaches the offhand slot
		plan("sprintstate", fw("Sprint (Toggled)"), 10, 0, 'E');
		plan("bossbar", 182, 14, 0, 'E');                            // BossBarHud W x H, one bar
		plan("directionhud", 160, 25, 0, 'E');                       // Width option 160, DIR_H 25
		plan("cooldowns", 2 * 24, 32, 0, 'E');                       // two dials (the Edit HUD sample)
		plan("scoreboard", fw("Origin Network") + 6, 6 * 10 + 3, 0, 'V'); // the Edit HUD sample, 5 lines + title
		plan("minimap", 8 * 12, 8 * 12 + 3 + 10, 0, 'S');            // 8 x 12 px map + the coordinates line
		plan("bwresources", 18 + fw("64") + 1, 4 * 18 - 2, 3, 'B');
		plan("bwteammates", 8 + 3 + fw("Teammate12") + 30, 3 * 11 - 2, 3, 'B');
		plan("party", 130, 11 + 3 * 11 - 2, 3, 'X');
		plan("portalcalc", fw("Links to: your portal at 123 64 -456") + 2, 2 * 10 + 1, 3, 'P');
		plan("itemcounter", 40, 16 + 10 + 16, 0, 'X');               // two counted items, vertical
		plan("locatorbar", 182, 5, 0, 'H');
		plan("titles", 0, 0, 0, 'T');
	}

	// pieces allowed into the chat / hotbar area at the auto GUI scale only, and why: see HudLayout's plan
	static final String[] AUTO_SCALE_CHAT = {"party", "portalcalc", "bwteammates"};

	// ---- geometry ------------------------------------------------------------------------------------------

	static double[] rect(Plan p, int sw, int sh) {
		double[] s = HudLayout.spot(p.id);
		double w = p.w * s[3], h = p.h * s[3];
		double x = HudLayout.left((int) s[0], s[1], sw, w), y = HudLayout.top((int) s[0], s[2], sh, h);
		return new double[]{x - p.m, y - p.m, x + w + p.m, y + h + p.m};
	}

	static boolean hit(double[] a, double[] b) {
		return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
	}

	static boolean neverTogether(char a, char b) {
		for (String pair : NEVER_TOGETHER) {
			if (pair.indexOf(a) >= 0 && pair.indexOf(b) >= 0 && a != b) {
				return true;
			}
		}
		return false;
	}

	static boolean autoScaleChat(String id) {
		for (String s : AUTO_SCALE_CHAT) {
			if (s.equals(id)) {
				return true;
			}
		}
		return false;
	}

	static String box(double[] r) {
		return String.format("[%.1f,%.1f %.1fx%.1f]", r[0], r[1], r[2] - r[0], r[3] - r[1]);
	}

	static void layout(int sw, int sh, boolean autoScale) {
		Map<String, double[]> zones = new LinkedHashMap<String, double[]>();
		zones.put("chat", new double[]{0, sh - 130, 328, sh - 40});
		zones.put("hotbar", new double[]{sw / 2.0 - 91 - 29, sh - 59, sw / 2.0 + 91 + 2, sh});
		List<Plan> ps = new ArrayList<Plan>();
		for (Plan p : PLAN.values()) {
			if (p.context != 'T') {
				ps.add(p);
			}
		}
		String at = sw + "x" + sh;
		for (int i = 0; i < ps.size(); i++) {
			Plan a = ps.get(i);
			double[] ra = rect(a, sw, sh);   // left/top keep every box on screen, so only overlaps can fail
			for (int j = i + 1; j < ps.size(); j++) {
				Plan b = ps.get(j);
				if (neverTogether(a.context, b.context)) {
					continue;
				}
				double[] rb = rect(b, sw, sh);
				if (hit(ra, rb)) {
					fail(at + " " + a.id + " " + box(ra) + " overlaps " + b.id + " " + box(rb));
				}
			}
			if (a.context == 'H' || (autoScale && autoScaleChat(a.id))) {
				continue;
			}
			for (Map.Entry<String, double[]> z : zones.entrySet()) {
				if (hit(ra, z.getValue())) {
					fail(at + " " + a.id + " " + box(ra) + " sits on the " + z.getKey());
				}
			}
		}
	}

	// ---- resolve: a player's spot is kept, an untouched old default moves -----------------------------------

	static void eq(String what, double[] want, double[] got) {
		if (want.length != got.length) {
			fail(what + ": length " + got.length);
			return;
		}
		for (int i = 0; i < want.length; i++) {
			if (want[i] != got[i]) {
				fail(what + ": want " + java.util.Arrays.toString(want) + ", got " + java.util.Arrays.toString(got));
				return;
			}
		}
	}

	static void resolveCases() {
		double[] clock = HudLayout.spot("clock");
		double[] d = {clock[0], clock[1], clock[2], clock[3], 0};
		eq("nothing stored -> default", d, HudLayout.resolve("clock", null, clock));
		eq("old code default -> new default", d, HudLayout.resolve("clock", new double[]{0, 6, 100, 1.0, 0}, clock));
		eq("old default, 4-value entry -> new default", d, HudLayout.resolve("clock", new double[]{0, 6, 100, 1.0}, clock));
		double[] moved = {2, -40.5, 77.25, 1.0, 0};
		eq("player's own spot kept", moved, HudLayout.resolve("clock", moved, clock));
		eq("old default + own scale and backing: placement moves, scale and backing kept",
				new double[]{clock[0], clock[1], clock[2], 1.35, 0.4},
				HudLayout.resolve("clock", new double[]{0, 6, 100, 1.35, 0.4}, clock));
		// the old starter config's entries (carried into every player's file on their first save)
		double[] ks = HudLayout.spot("keystrokes");
		eq("starter-config keystrokes -> new default", new double[]{ks[0], ks[1], ks[2], ks[3], 0},
				HudLayout.resolve("keystrokes", new double[]{0, 4, 82.56281957633308, 1.0, 0}, ks));
		double[] cps = HudLayout.spot("cps");
		eq("starter-config CPS (2.5x) -> new default incl. its scale", new double[]{cps[0], cps[1], cps[2], cps[3], 0},
				HudLayout.resolve("cps", new double[]{2, -4, 4, 2.5, 0}, cps));
		// another piece's old default is not this piece's
		double[] fps = HudLayout.spot("fps");
		eq("clock's old spot stored for fps is fps's own placement", new double[]{0, 6, 100, 1.0, 0},
				HudLayout.resolve("fps", new double[]{0, 6, 100, 1.0, 0}, fps));
		eq("unknown id keeps its stored spot", new double[]{1, 2, 3, 1.0, 0},
				HudLayout.resolve("nosuchpiece", new double[]{1, 2, 3, 1.0}, new double[]{0, 4, 4, 1.0}));
		// on-screen clamp and a damaged value
		if (HudLayout.left(2, 50, 400, 100) != 300 || HudLayout.top(0, Double.NaN, 300, 10) != 0) {
			fail("left/top clamp");
		}
	}

	public static void main(String[] args) {
		// every element id a live module registers has a spot and a planned size
		for (String id : args) {
			if (!HudLayout.has(id)) {
				fail("no HudLayout spot for '" + id + "'");
			}
			if (!PLAN.containsKey(id)) {
				fail("no planned size for '" + id + "' in HudLayoutTests.PLAN");
			}
		}
		for (String id : PLAN.keySet()) {
			if (!HudLayout.has(id)) {
				fail("planned piece '" + id + "' has no HudLayout spot");
			}
		}
		layout(455, 256, true);    // 1366x768, auto GUI scale (3)
		layout(480, 270, true);    // 1920x1080, auto GUI scale (4)
		layout(640, 360, false);   // 1920x1080 at GUI 3
		layout(683, 384, false);   // 1366x768 at GUI 2
		layout(960, 540, false);   // 1920x1080 at GUI 2
		resolveCases();
		System.out.println(failures == 0 ? "HudLayoutTests: OK (" + PLAN.size() + " pieces, 5 screen sizes)"
				: "HudLayoutTests: " + failures + " failure(s)");
		System.exit(failures == 0 ? 0 : 1);
	}
}
