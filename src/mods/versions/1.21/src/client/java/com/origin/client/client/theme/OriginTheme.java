package com.origin.client.client.theme;

// ============================================================================
// ORIGIN WORKBENCH — shared 1.21.x / 26.x visual contract
// ============================================================================
// Minecraft-grounded semantic tokens for every Origin-owned screen, HUD editor,
// overlay and restyled vanilla control. Keep this class import-free so each
// rendering generation can consume the same identity without adapter code.
//
// Colors are 0xAARRGGBB. Emerald is the sole general interaction accent; lapis,
// redstone and gold are reserved for information, danger and warning.
public final class OriginTheme {
	private OriginTheme() {
	}

	// ---- Primitive surfaces: voidstone -> polished deepslate ----
	public static final int BG = 0xFF0E1110;
	public static final int BG_ALT = 0xFF121614;
	public static final int PANEL = 0xFF171C19;
	public static final int PANEL_TRANSLUCENT = 0xA6171C19;
	public static final int PANEL_ALT = 0xFF202720;

	// ---- Semantic edges ----
	public static final int STROKE = 0x246F796F;
	public static final int STROKE_STRONG = 0x527D897E;
	public static final int STROKE_HOVER = 0xFF4E705C;

	// ---- Type: warm parchment over cool mineral surfaces ----
	public static final int TEXT = 0xFFF1E9D2;
	public static final int TEXT_DIM = 0xFFB8B5A6;
	public static final int MUTED = 0xFF7D8279;

	// ---- Interaction and semantic ores ----
	public static final int ACCENT = 0xFF5FD09B;
	public static final int ACCENT_2 = 0xFF83E2B5;
	public static final int ACCENT_GLOW = 0x595FD09B;
	public static final int ACCENT_DIM = 0x8C5FD09B;
	public static final int ACCENT_SOFT = 0x245FD09B;
	public static final int ACCENT_BORDER = 0x995FD09B;
	public static final int SUCCESS = 0xFF68C490;
	public static final int INFO = 0xFF6FAEE8;
	public static final int DANGER = 0xFFE06B5B;
	public static final int WARNING = 0xFFD8B45B;

	// ---- Unified card/control material ----
	public static final int BOX_FILL = 0xB31D2420;
	public static final int BOX_FILL_HOVER = 0xD127302A;
	public static final int BOX_BORDER = 0xF0080B09;
	public static final int BOX_BORDER_HOVER = 0xFF4E705C;

	// ---- Toggle ----
	public static final int SWITCH_ON = SUCCESS;
	public static final int SWITCH_OFF = 0xFF303732;
	public static final int SWITCH_KNOB = TEXT;
	public static final int SWITCH_STROKE = 0x52080B09;

	// ---- Spacing: Minecraft's compact 4/8 rhythm ----
	public static final int SPACE_1 = 8;
	public static final int SPACE_2 = 16;
	public static final int SPACE_3 = 24;
	public static final int SPACE_4 = 32;
	public static final int SPACE_6 = 48;
	public static final int SPACE_8 = 64;
	public static final int SPACE_10 = 96;

	// ---- Shape: crafted, not pill-heavy ----
	public static final int RADIUS_SM = 1;
	public static final int RADIUS_MD = 2;
	public static final int RADIUS_LG = 3;

	// ---- Motion: immediate response with a short, readable settle ----
	public static final double HOVER_IN_MS = 48.0;
	public static final double HOVER_OUT_MS = 64.0;
	public static final double PRESS_MS = 42.0;
	public static final double FOCUS_MS = 80.0;
	public static final double DURATION_FAST_MS = 120.0;
	public static final double DURATION_MED_MS = 180.0;
	public static final double HALO_LERP_FACTOR = 0.18;

	private static final double[] EASE_OUT = {0.16, 1.0, 0.3, 1.0};
	private static final double[] SPRING = {0.2, 0.9, 0.25, 1.05};

	public static double easeOut(double t) {
		return cubicBezier(EASE_OUT[0], EASE_OUT[1], EASE_OUT[2], EASE_OUT[3], t);
	}

	/**
	 * Compatibility curve for existing screen transitions. The small overshoot
	 * keeps it tactile without the floaty bounce of the former spring.
	 */
	public static double spring(double t) {
		return cubicBezier(SPRING[0], SPRING[1], SPRING[2], SPRING[3], t);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public static int lerpColor(int a, int b, double t) {
		int aa = (a >>> 24) & 0xFF, ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
		int ba = (b >>> 24) & 0xFF, br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
		int ra = (int) Math.round(lerp(aa, ba, t));
		int rr = (int) Math.round(lerp(ar, br, t));
		int rg = (int) Math.round(lerp(ag, bg, t));
		int rb = (int) Math.round(lerp(ab, bb, t));
		return (ra << 24) | (rr << 16) | (rg << 8) | rb;
	}

	public static int aurora(double t) {
		return lerpColor(ACCENT, ACCENT_2, Math.max(0.0, Math.min(1.0, t)));
	}

	public static int withAlpha(int argb, int alpha) {
		return ((Math.max(0, Math.min(255, alpha)) & 0xFF) << 24) | (argb & 0xFFFFFF);
	}

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
