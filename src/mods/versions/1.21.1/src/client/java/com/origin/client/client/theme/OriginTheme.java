package com.origin.client.client.theme;

// ============================================================================
// ORIGIN — "AURORA" IN-GAME IDENTITY  (1.21.1 redesign, 2026-09-03)
// ============================================================================
// The single source of colour, spacing, radius and motion for every
// Origin-owned surface (mod menu, HUD editor, shader browser, waypoints, the
// restyled vanilla widgets). No Minecraft imports on purpose: every renderer
// reads its look from here instead of hardcoding a value locally, so the whole
// client re-themes from ONE file.
//
// This REPLACES the old "Deskify-derived monochrome, no hue" system (which was
// exact-matched to the website CSS). Per Will's redesign call, Origin now has
// its OWN identity — a cosmic, premium dark client look inspired by the premium
// clients (Lunar/Feather/Badlion) but deliberately none of them:
//
//   • Base   — deep-space cool near-black (a whisper of blue, not flat void),
//              surfaces are smoked glass, translucent and cool-tinted.
//   • Accent — the AURORA: a periwinkle-indigo hero (#7C83FF) paired with an
//              aurora-teal (#3DE0C0). The indigo is the single brand accent
//              (primary actions, active tabs, brand mark); the teal appears
//              ONLY inside the gradient sweep/glow — never as a competing solid.
//              This two-stop aurora is the signature that separates Origin from
//              Lunar's cyan, Feather's magenta and Badlion's red.
//   • State  — mint success (#2FD08A), coral danger (#FF4D57), amber warn.
//   • Text   — cool near-white, cool-grey dims (reads as "space", not neutral).
//
// Colours are 0xAARRGGBB. Field NAMES are unchanged from the old system so no
// call site breaks; only their values moved, plus a few additions (ACCENT_2,
// ACCENT_SOFT, ACCENT_BORDER, SUCCESS/DANGER/WARNING, aurora()/withAlpha()).
public final class OriginTheme {
	private OriginTheme() {
	}

	// ---- Base surfaces (deep space, cool near-black) ----
	public static final int BG = 0xFF07080E;
	public static final int BG_ALT = 0xFF0A0C14;
	public static final int PANEL = 0xFF0E1018;
	// panel @ ~62% — the coords/ping/cpu HUD panel background. Kept readable
	// over bright gameplay while still reading as glass.
	public static final int PANEL_TRANSLUCENT = 0x9E0E1018;
	public static final int PANEL_ALT = 0xFF151827;

	// ---- Hairlines (cool white at low opacity) ----
	public static final int STROKE = 0x14C8D0FF;
	public static final int STROKE_STRONG = 0x2ED4DBFF;
	// Hover outline: every hovered custom box / button firms up to a bright cool
	// near-white (the redesign keeps Will's "hover reads lit" rule, cooled to the
	// new palette). PRIMARY controls override this with ACCENT_BORDER instead.
	public static final int STROKE_HOVER = 0xFFEAEEFF;

	// ---- Text (cool) ----
	public static final int TEXT = 0xFFF2F4FF;
	public static final int TEXT_DIM = 0xFF98A0B8;
	public static final int MUTED = 0xFF5B6178;

	// ---- The aurora accent ----
	// ACCENT is the ONE brand accent (indigo-periwinkle): active tabs, primary
	// actions, the brand mark, focus. ACCENT_2 (teal) is its gradient partner —
	// use it only via aurora()/gradient sweeps and tiny highlights.
	public static final int ACCENT = 0xFF7C83FF;
	public static final int ACCENT_2 = 0xFF3DE0C0;
	// accent @ 0.35 — glow behind accent text / brand, cursor halo.
	public static final int ACCENT_GLOW = 0x597C83FF;
	// accent @ 0.55 — cursor core glow.
	public static final int ACCENT_DIM = 0x8C7C83FF;
	// accent @ ~0.14 — a faint accent wash for the fill of a selected/primary box.
	public static final int ACCENT_SOFT = 0x247C83FF;
	// accent @ ~0.70 — the border of a selected/primary box (reads clearly accent
	// without shouting). Hover on a primary firms toward full ACCENT.
	public static final int ACCENT_BORDER = 0xB37C83FF;

	// ---- Semantic state ----
	public static final int SUCCESS = 0xFF2FD08A;
	public static final int DANGER = 0xFFFF4D57;
	public static final int WARNING = 0xFFFFB454;

	// ---- Box surface (glassy — every card / row / chip / dropdown / search) ----
	// Cool smoked glass: a see-through cool-dark fill with a near-black cool frame;
	// hover firms both up. One material for the whole menu system by construction.
	public static final int BOX_FILL = 0x66141726;
	public static final int BOX_FILL_HOVER = 0x99222741;
	public static final int BOX_BORDER = 0xF00A0C14;
	// Hover border leans subtly toward the accent so feedback carries the identity.
	public static final int BOX_BORDER_HOVER = 0xFF3A3F66;

	// ---- Mod-menu / iOS toggle ----
	// Brand-cohesive: mint when ON, coral when OFF, a cool-white knob. The actual
	// pill toggle lives in OriginUi (its own tuned geometry); these back any
	// squared switch that reads from the theme.
	public static final int SWITCH_ON = 0xFF2FD08A;   // aurora mint
	public static final int SWITCH_OFF = 0xFFFF4D57;  // coral
	public static final int SWITCH_KNOB = 0xFFF4F6FF;
	public static final int SWITCH_STROKE = 0x40000000;

	// ---- Spacing (8px grid) ----
	public static final int SPACE_1 = 8;
	public static final int SPACE_2 = 16;
	public static final int SPACE_3 = 24;
	public static final int SPACE_4 = 32;
	public static final int SPACE_6 = 48;
	public static final int SPACE_8 = 64;
	public static final int SPACE_10 = 96;

	// ---- Corner radii (softened for the premium glass feel) ----
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
	 * Samples the signature aurora gradient at t (0..1): ACCENT (indigo) at 0,
	 * ACCENT_2 (teal) at 1. Use for the active-tab underline sweep, the primary
	 * button bloom, and the brand mark — the one place ACCENT_2 is allowed to
	 * show. Alpha follows the same lerp so a gradient of translucent stops works.
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
