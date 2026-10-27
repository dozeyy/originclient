package com.origin.client.client.theme;

// ============================================================================
// ORIGIN WORKBENCH — shared 1.21.x / 26.x visual contract
// ============================================================================
// Minecraft-grounded semantic tokens for every Origin-owned screen, HUD editor,
// overlay and restyled vanilla control. Keep this class import-free so each
// rendering generation can consume the same identity without adapter code.
//
// The surface, edge, type and accent tokens are plain statics, not constants: an
// equipped theme item (cosmetics, FORMAT.md "Themes") repaints them through
// applyTheme / resetTheme (theme/ThemeManager, render thread). The DEF_ values
// are today's colours. Read them fresh each frame; never copy one into a
// static final. SUCCESS / INFO / DANGER / WARNING are not themed.
//
// Colors are 0xAARRGGBB. Emerald is the sole general interaction accent; lapis,
// redstone and gold are reserved for information, danger and warning.
public final class OriginTheme {
	private OriginTheme() {
	}

	// ---- Primitive surfaces: voidstone -> polished deepslate ----
	private static final int DEF_BG = 0xFF0E1110;
	public static int BG = DEF_BG;
	private static final int DEF_BG_ALT = 0xFF121614;
	public static int BG_ALT = DEF_BG_ALT;
	private static final int DEF_PANEL = 0xFF171C19;
	public static int PANEL = DEF_PANEL;
	private static final int DEF_PANEL_TRANSLUCENT = 0xA6171C19;
	public static int PANEL_TRANSLUCENT = DEF_PANEL_TRANSLUCENT;
	private static final int DEF_PANEL_ALT = 0xFF202720;
	public static int PANEL_ALT = DEF_PANEL_ALT;

	// ---- Semantic edges ----
	private static final int DEF_STROKE = 0x246F796F;
	public static int STROKE = DEF_STROKE;
	private static final int DEF_STROKE_STRONG = 0x527D897E;
	public static int STROKE_STRONG = DEF_STROKE_STRONG;
	private static final int DEF_STROKE_HOVER = 0xFF4E705C;
	public static int STROKE_HOVER = DEF_STROKE_HOVER;

	// ---- Type: warm parchment over cool mineral surfaces ----
	private static final int DEF_TEXT = 0xFFF1E9D2;
	public static int TEXT = DEF_TEXT;
	private static final int DEF_TEXT_DIM = 0xFFB8B5A6;
	public static int TEXT_DIM = DEF_TEXT_DIM;
	private static final int DEF_MUTED = 0xFF7D8279;
	public static int MUTED = DEF_MUTED;

	// ---- Interaction and semantic ores ----
	private static final int DEF_ACCENT = 0xFF5FD09B;
	public static int ACCENT = DEF_ACCENT;
	private static final int DEF_ACCENT_2 = 0xFF83E2B5;
	public static int ACCENT_2 = DEF_ACCENT_2;
	private static final int DEF_ACCENT_GLOW = 0x595FD09B;
	public static int ACCENT_GLOW = DEF_ACCENT_GLOW;
	private static final int DEF_ACCENT_DIM = 0x8C5FD09B;
	public static int ACCENT_DIM = DEF_ACCENT_DIM;
	private static final int DEF_ACCENT_SOFT = 0x245FD09B;
	public static int ACCENT_SOFT = DEF_ACCENT_SOFT;
	private static final int DEF_ACCENT_BORDER = 0x995FD09B;
	public static int ACCENT_BORDER = DEF_ACCENT_BORDER;
	public static final int SUCCESS = 0xFF68C490;
	public static final int INFO = 0xFF6FAEE8;
	public static final int DANGER = 0xFFE06B5B;
	public static final int WARNING = 0xFFD8B45B;

	// ---- Unified card/control material ----
	private static final int DEF_BOX_FILL = 0xB31D2420;
	public static int BOX_FILL = DEF_BOX_FILL;
	private static final int DEF_BOX_FILL_HOVER = 0xD127302A;
	public static int BOX_FILL_HOVER = DEF_BOX_FILL_HOVER;
	private static final int DEF_BOX_BORDER = 0xF0080B09;
	public static int BOX_BORDER = DEF_BOX_BORDER;
	private static final int DEF_BOX_BORDER_HOVER = 0xFF4E705C;
	public static int BOX_BORDER_HOVER = DEF_BOX_BORDER_HOVER;

	// ---- Toggle ----
	private static final int DEF_SWITCH_ON = SUCCESS;
	public static int SWITCH_ON = DEF_SWITCH_ON;
	private static final int DEF_SWITCH_OFF = 0xFF303732;
	public static int SWITCH_OFF = DEF_SWITCH_OFF;
	private static final int DEF_SWITCH_KNOB = DEF_TEXT;
	public static int SWITCH_KNOB = DEF_SWITCH_KNOB;
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

	// ---- Clean in-game surface (Will's approved mockup, 2026-10-19) ----
	// What Origin adds around Minecraft's own screens (title, pause, options,
	// world/server lists, loading): the dim over this version's blurred panorama,
	// the list panels, the loading scene's text and bar. The controls (and the
	// title's player chip) are stock Minecraft widgets (Will, 2026-10-19).
	// Fixed (not theme-repainted) so the vanilla menus always match.
	public static final int CLEAN_PANEL = 0x800C0D10;          // list / settings panels, rgba(12,13,16,.50)
	public static final int CLEAN_TEXT = 0xFFF3F4F6;
	public static final int CLEAN_TEXT_DIM = 0xA8F3F4F6;       // 66%
	public static final int CLEAN_TEXT_FAINT = 0x70F3F4F6;     // 44%
	public static final int CLEAN_BG = 0xFF0E1110;             // color.bg: the loading picture's backdrop (as 1.21.1)
	public static final int CLEAN_DIM_MENU = 0x8C0B0C0F;       // dim over the blurred panorama, menus (~55%)
	public static final int CLEAN_DIM_TITLE = 0x590E1110;      // title (~35%), the Workbench bg hue (Phase 4)
	public static final int CLEAN_DIM_LOADING = 0x990B0C0F;    // loading scenes (~60%)
	// The loading picture (render/LoadingLook, as 1.21.1): every loading, connecting
	// and world-load screen reads its colours here.
	public static final int CLEAN_BAR_TRACK = 0x14FFFFFF;      // progress bar track, white 8 %
	public static final int CLEAN_BAR_FILL = 0xFF5FD09B;       // progress bar fill, accent
	public static final int CLEAN_CAPTION = 0xADF1E9D2;        // the stage caption, color.textDim (68 %)
	public static final float CLEAN_CAPTION_EM = 8.0f;         // caption size, GUI px (1.21.1's TITLE_SMALL_EM)

	// ---- Title screen, Workbench skin (Phase 4, docs/overhaul/DESIGN-SYSTEM.md 15.2) ----
	// ONLY the title's controls read these (OriginButtonRenderer.titleControl, the chip in
	// OriginScreenRenderer). They are separate from BOX_* / PANEL on purpose: those feed the
	// Mod Menu family, which must not change by a pixel. Fixed (not theme-repainted), like the
	// CLEAN_* set above. Colours are the spec's tokens as ARGB; radii and edges are GUI px.
	public static final int TITLE_RADIUS = 4;                  // buttons 20 tall: the launcher's 40/8 ratio
	// Dark-button match (Will, 2026-10-26: "make the main menu ui ones be a better match for them keeping
	// there custom look"): the cards keep their own shape (3 px corners, Inter 600, parchment, see-through
	// over the panorama) but take the vanilla dark buttons' charcoal (tools/dark-widgets: face #303033) and
	// their near-black ring; hover lifts to the dark buttons' hover face and the ring turns white like theirs.
	public static final int TITLE_BTN_FILL = 0xDB303033;       // dark-button face at 86 %
	public static final int TITLE_BTN_FILL_HOVER = 0xF23E3E42; // dark-button hover face at 95 %
	public static final int TITLE_BTN_FILL_PRESS = 0xF51C1C1E; // disabled face, near-black: the press reads as sinking
	public static final int TITLE_BTN_EDGE = 0xE60B0B0C;       // the dark buttons' outline ring, 1 GUI px
	public static final int TITLE_BTN_EDGE_HOVER = 0xD9FFFFFF; // white ring on hover, like the dark buttons
	public static final int TITLE_FOCUS = 0x995FD09B;          // accentLine, 1 GUI px ring
	public static final int TITLE_TOOLTIP_FILL = 0xF5303033;   // dark-button face at 96 %: tooltip over the panorama (11.8)
	public static final int TITLE_TEXT_MUTED =0x85F1E9D2;     // textMuted: disabled labels
	// Workbench cards (A-Workbench-3, approved 2026-10-25): the worded title buttons (and Connecting's
	// Cancel) are 3 GUI px corners with Inter 600 labels in parchment. TITLE_RADIUS above stays 4 for the
	// focus ring's old users, tooltips and anything else that reads it.
	public static final int TITLE_BTN_RADIUS = 3;
	public static final int TITLE_TEXT = 0xFFF1E9D2;           // parchment, fixed (never theme-repainted)
	public static final float TITLE_LABEL_EM = 9.0f;           // Inter 600 em, GUI px
	// The title's quiet text (footer line, copyright, dock tooltips): Inter regular at 8 GUI px em, textMuted
	// (A-Workbench-3; DECISIONS 2026-10-25 allows Inter on Origin's own title text). The player name is Inter 600
	// at the label em in parchment. Every one falls back to Minecraft's font when the SDF face is not live.
	public static final float TITLE_SMALL_EM = 8.0f;
	// No ring/orbit tokens: Origin's UI draws no rings anywhere; the only ring shapes are inside
	// origin_logo.png (Will, 2026-10-25: drawn look-alikes read as a fake logo).

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

	/**
	 * Recolours the tokens from an equipped theme (cosmetics/ThemeColors): token name ->
	 * 0xAARRGGBB, named exactly like the fields above. The build works every value out
	 * from these DEF_ colours (cosmetics/lib/theme.mjs), so the game only assigns; a token
	 * the theme leaves out keeps today's colour.
	 */
	public static void applyTheme(java.util.Map<String, Integer> t) {
		resetTheme();
		BG = t.getOrDefault("BG", BG);
		BG_ALT = t.getOrDefault("BG_ALT", BG_ALT);
		PANEL = t.getOrDefault("PANEL", PANEL);
		PANEL_TRANSLUCENT = t.getOrDefault("PANEL_TRANSLUCENT", PANEL_TRANSLUCENT);
		PANEL_ALT = t.getOrDefault("PANEL_ALT", PANEL_ALT);
		STROKE = t.getOrDefault("STROKE", STROKE);
		STROKE_STRONG = t.getOrDefault("STROKE_STRONG", STROKE_STRONG);
		STROKE_HOVER = t.getOrDefault("STROKE_HOVER", STROKE_HOVER);
		TEXT = t.getOrDefault("TEXT", TEXT);
		TEXT_DIM = t.getOrDefault("TEXT_DIM", TEXT_DIM);
		MUTED = t.getOrDefault("MUTED", MUTED);
		ACCENT = t.getOrDefault("ACCENT", ACCENT);
		ACCENT_2 = t.getOrDefault("ACCENT_2", ACCENT_2);
		ACCENT_GLOW = t.getOrDefault("ACCENT_GLOW", ACCENT_GLOW);
		ACCENT_DIM = t.getOrDefault("ACCENT_DIM", ACCENT_DIM);
		ACCENT_SOFT = t.getOrDefault("ACCENT_SOFT", ACCENT_SOFT);
		ACCENT_BORDER = t.getOrDefault("ACCENT_BORDER", ACCENT_BORDER);
		BOX_FILL = t.getOrDefault("BOX_FILL", BOX_FILL);
		BOX_FILL_HOVER = t.getOrDefault("BOX_FILL_HOVER", BOX_FILL_HOVER);
		BOX_BORDER = t.getOrDefault("BOX_BORDER", BOX_BORDER);
		BOX_BORDER_HOVER = t.getOrDefault("BOX_BORDER_HOVER", BOX_BORDER_HOVER);
		SWITCH_ON = t.getOrDefault("SWITCH_ON", SWITCH_ON);
		SWITCH_OFF = t.getOrDefault("SWITCH_OFF", SWITCH_OFF);
		SWITCH_KNOB = t.getOrDefault("SWITCH_KNOB", SWITCH_KNOB);
	}

	/** Back to today's colours. */
	public static void resetTheme() {
		BG = DEF_BG;
		BG_ALT = DEF_BG_ALT;
		PANEL = DEF_PANEL;
		PANEL_TRANSLUCENT = DEF_PANEL_TRANSLUCENT;
		PANEL_ALT = DEF_PANEL_ALT;
		STROKE = DEF_STROKE;
		STROKE_STRONG = DEF_STROKE_STRONG;
		STROKE_HOVER = DEF_STROKE_HOVER;
		TEXT = DEF_TEXT;
		TEXT_DIM = DEF_TEXT_DIM;
		MUTED = DEF_MUTED;
		ACCENT = DEF_ACCENT;
		ACCENT_2 = DEF_ACCENT_2;
		ACCENT_GLOW = DEF_ACCENT_GLOW;
		ACCENT_DIM = DEF_ACCENT_DIM;
		ACCENT_SOFT = DEF_ACCENT_SOFT;
		ACCENT_BORDER = DEF_ACCENT_BORDER;
		BOX_FILL = DEF_BOX_FILL;
		BOX_FILL_HOVER = DEF_BOX_FILL_HOVER;
		BOX_BORDER = DEF_BOX_BORDER;
		BOX_BORDER_HOVER = DEF_BOX_BORDER_HOVER;
		SWITCH_ON = DEF_SWITCH_ON;
		SWITCH_OFF = DEF_SWITCH_OFF;
		SWITCH_KNOB = DEF_SWITCH_KNOB;
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
