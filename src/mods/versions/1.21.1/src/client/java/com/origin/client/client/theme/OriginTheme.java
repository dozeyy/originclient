package com.origin.client.client.theme;

// ============================================================================
// ORIGIN — IN-GAME IDENTITY "SLATE"  (1.21.1 redesign, 2026-09-03)
// ============================================================================
// The single source of colour, spacing, radius and motion for every
// Origin-owned surface (mod menu, HUD editor, shader browser, waypoints, the
// restyled vanilla widgets). No Minecraft imports on purpose: every renderer
// reads its look from here, so the whole client re-themes from ONE file.
//
// COLOUR SYSTEM — built on colour theory, not mood (Will: "real colour theory,
// everything working together, simpler colours"):
//
//   1. ONE ACCENT HUE. A clean blue at ~225°. It is used ONLY for state —
//      active / selected / focus / primary. No second hue, no gradient. That is
//      what makes the palette read as one system instead of a theme.
//   2. HARMONISED NEUTRALS. Every grey carries ~3% of the accent hue, so
//      surfaces, hairlines and text are visibly related to the blue rather
//      than a foreign neutral sitting next to it.
//   3. 60 / 30 / 10. Neutral surfaces dominate; dim text + borders are the
//      secondary weight; the accent is the last 10%.
//   4. A VALUE LADDER for depth. bg → panel → card → hover step up in even
//      ~4% lightness increments; depth comes from lightness, never from a
//      second colour. Borders sit DARKER than the see-through card fill so an
//      edge always reads against a bright panorama (Will's rule).
//   5. SEMANTIC COLOURS AT MATCHED LIGHTNESS, DESATURATED. Success (green)
//      and danger (red) share the accent's perceived lightness and are pulled
//      back in chroma so they carry meaning without out-shouting the accent.
//   6. CONTRAST. Primary text ≥ 12:1, dim text ≥ 7:1, accent-on-panel ≥ 5:1.
//
// Colours are 0xAARRGGBB. Field NAMES are unchanged from earlier systems so no
// call site breaks; ACCENT_2 / aurora() are kept as API but are now a TINT of
// the same hue (monochromatic), not a second colour.
public final class OriginTheme {
	private OriginTheme() {
	}

	// ---- Value ladder: base surfaces (blue-tinted neutrals, ~3% chroma) ----
	public static final int BG = 0xFF0B0D12;
	public static final int BG_ALT = 0xFF0E1117;
	public static final int PANEL = 0xFF12151C;
	// panel @ ~65% — the coords/ping/cpu HUD panel background over gameplay.
	public static final int PANEL_TRANSLUCENT = 0xA612151C;
	public static final int PANEL_ALT = 0xFF181C25;

	// ---- Hairlines (white at low alpha inherits the surface tint beneath) ----
	public static final int STROKE = 0x14FFFFFF;
	public static final int STROKE_STRONG = 0x24FFFFFF;
	// Hover outline: a hovered custom box firms up to a soft near-white.
	public static final int STROKE_HOVER = 0xFFE6EAF2;

	// ---- Text (softened off-white, cool greys on the same hue) ----
	public static final int TEXT = 0xFFEEF1F6;
	public static final int TEXT_DIM = 0xFF9AA3B2;
	public static final int MUTED = 0xFF6B7484;

	// ---- The one accent (blue, ~225°) ----
	public static final int ACCENT = 0xFF4F8DFF;
	// A lighter TINT of the same hue — the top of the accent's own value ramp.
	// Not a second colour: used only where a highlight of the accent is needed.
	public static final int ACCENT_2 = 0xFF8AB4FF;
	// accent @ 0.35 — glow behind accent text / brand, cursor halo.
	public static final int ACCENT_GLOW = 0x594F8DFF;
	// accent @ 0.55 — cursor core glow.
	public static final int ACCENT_DIM = 0x8C4F8DFF;
	// accent @ ~0.14 — a faint wash for the fill of a selected/primary box.
	public static final int ACCENT_SOFT = 0x244F8DFF;
	// accent @ ~0.60 — the border of a selected/primary box; hover → full ACCENT.
	public static final int ACCENT_BORDER = 0x994F8DFF;

	// ---- Semantic state (matched lightness to the accent, desaturated) ----
	public static final int SUCCESS = 0xFF3DBE7A;
	public static final int DANGER = 0xFFE5535B;
	public static final int WARNING = 0xFFE0A63A;

	// ---- Box surface (every card / row / chip / dropdown / search) ----
	// See-through tinted card fill; the frame is DARKER than the fill (Will) so
	// the edge reads on any backdrop; hover lifts both one rung up the ladder.
	public static final int BOX_FILL = 0x8C181C25;
	public static final int BOX_FILL_HOVER = 0xB3222732;
	public static final int BOX_BORDER = 0xF00C0E13;
	public static final int BOX_BORDER_HOVER = 0xFF3A4252;

	// ---- Toggle ----
	// The iOS pill in OriginUi reads its own tuned copies of these two.
	public static final int SWITCH_ON = SUCCESS;
	public static final int SWITCH_OFF = DANGER;
	public static final int SWITCH_KNOB = 0xFFF2F4F8;
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
	public static final int RADIUS_MD = 12;
	public static final int RADIUS_LG = 16;

	// ---- Motion ----
	public static final double DURATION_FAST_MS = 150.0;
	public static final double DURATION_MED_MS = 300.0;
	// Cursor-glow halo per-frame lag factor: haloX += (targetX - haloX) * 0.12.
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
	 * The accent's own value ramp at t (0..1): ACCENT at 0 → its lighter tint
	 * ACCENT_2 at 1. Monochromatic on purpose — an active-tab underline or a
	 * rail bar drawn through this reads as one blue with a soft highlight, not
	 * as a gradient between two colours. (Name kept for existing call sites.)
	 */
	public static int aurora(double t) {
		return lerpColor(ACCENT, ACCENT_2, Math.max(0.0, Math.min(1.0, t)));
	}

	/** Same ARGB colour at a new alpha (0..255) — for glows, washes, fades. */
	public static int withAlpha(int argb, int alpha) {
		return ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24) | (argb & 0xFFFFFF);
	}

	/**
	 * Evaluates a CSS-style cubic-bezier(x1,y1,x2,y2) timing function at
	 * time t (0..1), implied endpoints (0,0) and (1,1) — same definition
	 * CSS/browsers use. Solves for the bezier parameter u where the curve's
	 * x-component equals t (Newton-Raphson), then returns the y-component at u.
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
