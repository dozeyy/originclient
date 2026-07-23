package com.origin.client.client.theme;

// Design tokens exact-matched to website/css/styles.css's :root custom
// properties (see DESIGN_SYSTEM.md §1) — colors as 0xAARRGGBB, an 8px
// spacing scale, corner radii, and the site's two named easing curves.
// No Minecraft imports on purpose: every Origin-owned renderer should read
// its look from here instead of hardcoding a value locally.
public final class OriginTheme {
	private OriginTheme() {
	}

	// ---- Colors ----
	public static final int BG = 0xFF050505;
	public static final int BG_ALT = 0xFF0A0A0A;
	public static final int PANEL = 0xFF101010;
	// rgba(16,16,16,0.55) — the coords/ping/cpu HUD panel background
	public static final int PANEL_TRANSLUCENT = 0x8C101010;
	public static final int PANEL_ALT = 0xFF161616;
	// rgba(255,255,255,0.08)
	public static final int STROKE = 0x14FFFFFF;
	// rgba(255,255,255,0.18)
	public static final int STROKE_STRONG = 0x2EFFFFFF;
	// Hover outline — BRIGHT WHITE (Will 2026-07-21): every hovered custom box /
	// button (mod menu, HUD editor MODS button, tabs, chips) highlights to full
	// white, not the old light-gray. One shared value drives them all.
	public static final int STROKE_HOVER = 0xFFFFFFFF;
	public static final int TEXT = 0xFFFFFFFF;
	public static final int TEXT_DIM = 0xFF9A9A9A;
	public static final int MUTED = 0xFF616161;
	// The one accent — stays white/mono everywhere, no new hue (confirmed
	// with Will; see root CLAUDE.md -> Brand).
	public static final int ACCENT = 0xFFFFFFFF;
	// rgba(255,255,255,0.35) — glow behind accent text, cursor halo
	public static final int ACCENT_GLOW = 0x59FFFFFF;
	// rgba(255,255,255,0.55) — cursor core glow
	public static final int ACCENT_DIM = 0x8CFFFFFF;

	// ---- Box surface (matches the main-menu button skin, OriginButtonRenderer) ----
	// Every box INSIDE the mod menu (cards, setting rows, chips, dropdowns, the
	// search field) draws with these so it reads as the exact same material as the
	// Frost-style buttons on the main menu: a see-through dark fill with a darker
	// hairline frame; hover firms both up. Same values Will tuned on the buttons
	// (2026-07-21). Sizes are unchanged — only the fill opacity + border colour.
	public static final int BOX_FILL = 0x59161616;
	public static final int BOX_FILL_HOVER = 0x99303030;
	public static final int BOX_BORDER = 0xF00A0A0A;
	public static final int BOX_BORDER_HOVER = 0xFF1A1A1A;

	// ---- Mod-menu toggle (C4) ----
	// The rounded box switch: knob slides left = off, right = on. On/off are the
	// theme's only two non-gray tones (muted sage / muted clay), kept solid so
	// the state reads instantly; the knob is a near-white rounded square.
	public static final int SWITCH_ON = 0xFF2F7D53;   // muted sage
	public static final int SWITCH_OFF = 0xFFA33A33;  // muted clay
	public static final int SWITCH_KNOB = 0xFFF0F0F0;
	// A hairline that darkens the track edge so the box reads crisp on any bg.
	public static final int SWITCH_STROKE = 0x40000000;

	// ---- Spacing (8px grid) ----
	public static final int SPACE_1 = 8;
	public static final int SPACE_2 = 16;
	public static final int SPACE_3 = 24;
	public static final int SPACE_4 = 32;
	public static final int SPACE_6 = 48;
	public static final int SPACE_8 = 64;
	public static final int SPACE_10 = 96;

	// ---- Corner radii ----
	public static final int RADIUS_SM = 6;
	public static final int RADIUS_MD = 10;
	public static final int RADIUS_LG = 14;

	// ---- Motion ----
	public static final double DURATION_FAST_MS = 150.0;
	public static final double DURATION_MED_MS = 300.0;
	// The cursor-glow halo's per-frame lag factor from website/js/main.js's
	// tick(): haloX += (targetX - haloX) * 0.12. Reuse this exact constant,
	// don't re-derive an approximation.
	public static final double HALO_LERP_FACTOR = 0.12;

	private static final double[] EASE_OUT = {0.16, 1.0, 0.3, 1.0};
	private static final double[] SPRING = {0.34, 1.56, 0.64, 1.0};

	/** cubic-bezier(0.16, 1, 0.3, 1) — css var(--ease-out). */
	public static double easeOut(double t) {
		return cubicBezier(EASE_OUT[0], EASE_OUT[1], EASE_OUT[2], EASE_OUT[3], t);
	}

	/** cubic-bezier(0.34, 1.56, 0.64, 1) — css var(--ease-spring). */
	public static double spring(double t) {
		return cubicBezier(SPRING[0], SPRING[1], SPRING[2], SPRING[3], t);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Component-wise ARGB lerp, for button hover/press color fades. */
	public static int lerpColor(int a, int b, double t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int ra = (int) Math.round(lerp(aa, ba, t));
		int rr = (int) Math.round(lerp(ar, br, t));
		int rg = (int) Math.round(lerp(ag, bg, t));
		int rb = (int) Math.round(lerp(ab, bb, t));
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}

	/**
	 * Evaluates a CSS-style cubic-bezier(x1,y1,x2,y2) timing function at
	 * time t (0..1), implied endpoints (0,0) and (1,1) — same definition
	 * CSS/browsers use. Solves for the bezier parameter u where the curve's
	 * x-component equals t (Newton-Raphson, a handful of iterations is
	 * plenty for UI-scale precision), then returns the y-component at u.
	 */
	public static double cubicBezier(double x1, double y1, double x2, double y2, double t) {
		double u = clamp01(t);
		for (int i = 0; i < 8; i++) {
			double x = bezierComponent(u, x1, x2);
			double dx = bezierDerivative(u, x1, x2);
			if (Math.abs(dx) < 1e-6) {
				break;
			}
			u = clamp01(u - (x - t) / dx);
		}
		return bezierComponent(u, y1, y2);
	}

	private static double bezierComponent(double u, double p1, double p2) {
		double v = 1 - u;
		return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
	}

	private static double bezierDerivative(double u, double p1, double p2) {
		double v = 1 - u;
		return 3 * v * v * p1 + 6 * v * u * (p2 - p1) + 3 * u * u * (1 - p2);
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
