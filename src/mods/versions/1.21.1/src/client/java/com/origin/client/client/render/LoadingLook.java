package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.origin.client.client.theme.OriginTheme;

/**
 * The one loading picture (docs/overhaul/DESIGN-SYSTEM.md section 15.3, Phase 4 U4.2):
 * the startup resource load, the world-loading screens (chunk preparation, "Loading
 * terrain", the progress stages, "Preparing for world creation") and "Connecting to the
 * server" all draw THIS layout and only the caption changes.
 *
 * <pre>
 *   flat color.bg
 *   the official logo     72 design px box, centred at 44 % of the height on EVERY loading
 *                         screen (Connecting included: its Cancel button moves, the logo
 *                         never does, so the logo carries across the screens)
 *   the orbit             the title's three faint pills around it (OrbitHairlines); the 120 deg
 *                         one carries the progress: an emerald stroke fills along its path with
 *                         the real fraction (OrbitProgress), or a segment travels it with no measure
 *   14 below the orbit    the caption: Inter at TITLE_SMALL_EM (the same size as Connecting's
 *                         Cancel label), textMuted, the real stage name (+ a percentage only
 *                         where Minecraft reports one)
 * </pre>
 *
 * <p>The straight rail of the first Workbench pass is only a fallback now: it is drawn under the
 * orbit when the ring has latched off (OrbitProgress.failed).
 *
 * "Design px" are physical pixels at 1080p, scaled by window height / 1080 (never below
 * 0.75), so the picture looks the same at any resolution or GUI scale. Everything is drawn
 * in whole physical pixels (the pose is scaled back to 1 GUI unit = 1 pixel), so the rail
 * is crisp at every GUI scale.
 *
 * <p>Cost: one fill, one cached orbit-buffer draw, one logo blit, one progress-stroke batch (cached
 * geometry, quads only), one caption line. No shader, no blur,
 * no texture beyond the logo (baked once per size by OriginScreenRenderer.drawLogo) and, for
 * the startup caption only, the boot font. Nothing is allocated per frame: the geometry lives
 * in static fields (render thread only) and the caption strings are cached by their callers.
 *
 * <p>Text: the startup load draws before Inter exists (the MSDF atlas and shader arrive late
 * in the reload), so its caption uses the boot font (Minecraft's own ascii.png) for the whole
 * load rather than switching typeface halfway; every later screen uses Inter (OriginSdfFont)
 * and falls back to Minecraft's font if that is not ready.
 */
final class LoadingLook {
	// Colours: OriginTheme's loading tokens (DESIGN-SYSTEM 17.3), never copied here:
	// CLEAN_BG (color.bg), LOADING_ORBIT (the pills / the ring's track), CLEAN_BAR_FILL (the
	// emerald progress, #5FD09B), CLEAN_BAR_TRACK (fallback rail track), TITLE_TEXT_MUTED (caption).

	/** Logo box, design px. */
	private static final double LOGO = 72.0;
	/** Logo centre, share of the window height: the same on every loading screen. */
	static final double LOGO_AT = 0.44;
	private static final double RAIL_W = 200.0;
	/** Fallback rail only: 2 GUI px tall over a 1 GUI px hairline track. */
	private static final int RAIL_GUI = 2, TRACK_GUI = 1;
	// The title's three-pill orbit (0 / 60 / 120 deg), scaled around the logo: long half-extent ORBIT_A x the
	// logo box, short half-extent (= the pill ends' radius) in the title's 0.171 / 0.476 proportion.
	private static final double ORBIT_A = 1.5, ORBIT_SHORT = 0.171 / 0.476;
	/** How far the pills reach below their centre, per unit of long half-extent: the 60 / 120 deg pills reach
	 *  furthest, (a - b) sin 60 + b = a ((1 - s) sin 60 + s). */
	private static final double ORBIT_REACH = (1.0 - ORBIT_SHORT) * Math.sin(Math.toRadians(60.0)) + ORBIT_SHORT;
	private static final OrbitHairlines ORBIT = new OrbitHairlines(3, 0.0);
	/** The progress ring: the 120 deg pill (lower left to upper right, A-Workbench-3), its stroke 2.5 design px. */
	private static final double PROGRESS_TILT = 120.0, PROGRESS_W = 2.5;
	private static final OrbitProgress PROGRESS = new OrbitProgress();
	/** Orbit bottom to the caption's line box, design px. */
	private static final double GAP_CAPTION = 14.0;
	/** The caption's line box, GUI px (an 8 GUI px em in a 10 px line, like a vanilla text line). */
	private static final double CAPTION_LINE_GUI = 10.0;
	/** Inter's cap height per em (what the boot-font caption matches before Inter exists). */
	private static final double INTER_CAP_PER_EM = 0.727;
	/** No measure: a 26 % segment travels the ring, one lap per 1.6 s. */
	private static final double SWEEP_SHARE = 0.26, SWEEP_MS = 1600.0;

	// The frame's geometry, in physical pixels (computed by layout()).
	private static int gs, physW, physH;
	private static double k, logoBox, logoCy, orbitA, orbitBottom, captionMid;

	private LoadingLook() {
	}

	private static double scale() {
		return Math.max(0.75, Minecraft.getInstance().getWindow().getHeight() / 1080.0);
	}

	/**
	 * GUI px from the logo's centre down to the caption's bottom edge at this window's scale: what a
	 * screen with a button below the picture (Connecting's Cancel) needs to keep the button clear of it.
	 */
	static double belowCentreGui() {
		Minecraft mc = Minecraft.getInstance();
		double g = Math.max(1, Math.round(mc.getWindow().getGuiScale()));
		double box = Math.round(LOGO * scale());
		return (box * ORBIT_A * ORBIT_REACH + GAP_CAPTION * scale()) / g + CAPTION_LINE_GUI;
	}

	/** The logo centre's height, GUI px, on every loading screen at this window size (what draw() uses). */
	static double logoCentreGui() {
		Minecraft mc = Minecraft.getInstance();
		return Math.round(mc.getWindow().getHeight() * LOGO_AT) / (double) Math.max(1, Math.round(mc.getWindow().getGuiScale()));
	}

	private static void layout(double cyPhys) {
		Minecraft mc = Minecraft.getInstance();
		gs = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
		physW = mc.getWindow().getWidth();
		physH = mc.getWindow().getHeight();
		k = scale();
		logoBox = Math.round(LOGO * k);
		logoCy = cyPhys;
		orbitA = logoBox * ORBIT_A;
		orbitBottom = logoCy + orbitA * ORBIT_REACH;
		captionMid = orbitBottom + GAP_CAPTION * k + CAPTION_LINE_GUI * gs / 2.0;
	}

	/**
	 * Draws the loading picture.
	 *
	 * @param layer    whole-picture opacity 0..1 (the loading -> title dissolve; 1 elsewhere)
	 * @param cyFrac   logo centre as a share of the window height (LOGO_AT, or higher to clear a button)
	 * @param fill     rail fill 0..1, or a negative number for the sweep (no real measure)
	 * @param caption  the stage line, "" for none
	 * @param boot     draw the caption in the boot font (the startup load) instead of Inter
	 */
	static void draw(GuiGraphics g, float layer, double cyFrac, double fill, String caption, boolean boot) {
		draw(g, layer, cyFrac, fill, caption, boot, true);
	}

	/**
	 * As above; {@code logo} false leaves the logo out (the loading -> title hand-off draws
	 * it itself, in flight: OriginScreenRenderer.renderLoadingFade). The logo's box this
	 * frame is then still readable through {@link #logoCxGui()} and its siblings.
	 */
	static void draw(GuiGraphics g, float layer, double cyFrac, double fill, String caption, boolean boot, boolean logo) {
		Minecraft mc = Minecraft.getInstance();
		layout(Math.round(mc.getWindow().getHeight() * cyFrac));
		int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();

		// The backdrop is flat color.bg, the same colour as the launcher's launch cover.
		g.fill(0, 0, gw, gh, fade(OriginTheme.CLEAN_BG, layer));

		// The faint orbit pills behind the logo (A-Workbench-3), the title's rings' siblings. They stay
		// through the loading -> title hand-off (logo false) and fade with the layer.
		double aGui = orbitA / gs;
		if (aGui > 1.0) {
			ORBIT.draw(g, (float) logoCxGui(), (float) logoCyGui(), (float) aGui, (float) (aGui * ORBIT_SHORT),
					OriginTheme.LOADING_ORBIT, layer);
		}

		// The official logo, through the one drawing path (origin_logo.png at LOGO_PATH).
		if (logo) {
			long t0 = com.origin.client.client.OriginFrameLog.partStart();
			OriginScreenRenderer.drawLogo(g, logoCxGui(), logoCyGui(), logoSizeGui(), layer);
			com.origin.client.client.OriginFrameLog.part("o:logo", t0);
		}

		// The progress (the emerald stroke on the 120 deg pill; the straight rail only if the ring latched
		// off) and the caption.
		long t0 = com.origin.client.client.OriginFrameLog.partStart();
		if (!ring(g, layer, fill)) {
			rail(g, layer, fill);
		}
		com.origin.client.client.OriginFrameLog.part("o:rail", t0);
		if (caption != null && !caption.isEmpty()) {
			t0 = com.origin.client.client.OriginFrameLog.partStart();
			caption(g, layer, caption, boot, gw);
			com.origin.client.client.OriginFrameLog.part("o:caption", t0);
		}
	}

	// The logo's box in GUI px, as the last draw() laid it out (the hand-off flies it from here).
	static double logoCxGui() {
		return physW / 2.0 / gs;
	}

	static double logoCyGui() {
		return logoCy / gs;
	}

	static double logoSizeGui() {
		return logoBox / gs;
	}

	/** The logo's box size in physical px at this window's height (what drawLogo bakes). */
	static int logoPhys() {
		return (int) Math.round(LOGO * scale());
	}

	// ---- the progress ring ----

	/** The emerald stroke along the progress pill: the real fill, or a travelling segment for none. */
	private static boolean ring(GuiGraphics g, float layer, double fill) {
		if (PROGRESS.failed() || orbitA / gs <= 1.0) {
			return false;
		}
		double from, to;
		if (fill < 0) {
			double t = (RingField.sceneMs() % SWEEP_MS) / SWEEP_MS;
			from = t;
			to = t + SWEEP_SHARE;
		} else {
			from = 0.0;
			to = Math.max(0.0, Math.min(1.0, fill));
		}
		float width = (float) Math.max(2.0, PROGRESS_W * k);
		return PROGRESS.draw(g, gs, physW / 2.0, logoCy, orbitA, orbitA * ORBIT_SHORT, PROGRESS_TILT,
				from, to, width, fade(OriginTheme.CLEAN_BAR_FILL, layer));
	}

	// ---- the fallback rail (only when the ring latched off) ----

	private static void rail(GuiGraphics g, float layer, double fill) {
		int w = (int) Math.round(RAIL_W * k), h = RAIL_GUI * gs;
		int x = (int) Math.round((physW - w) / 2.0);
		int y = (int) Math.round(orbitBottom + (GAP_CAPTION * k - h) / 2.0);
		PoseStack pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		// The track: a 1 GUI px hairline through the middle of the 2 GUI px band.
		int th = TRACK_GUI * gs;
		pill(g, x, y + (h - th) / 2, w, th, fade(OriginTheme.CLEAN_BAR_TRACK, layer));
		int fillArgb = fade(OriginTheme.CLEAN_BAR_FILL, layer);
		if (fill < 0) {
			// Indeterminate: a segment slides across (never a made-up percentage).
			double t = (RingField.sceneMs() % SWEEP_MS) / SWEEP_MS;
			double eased = t * t * (3.0 - 2.0 * t);
			double seg = w * SWEEP_SHARE;
			double from = -seg + eased * (w + seg);
			int a = (int) Math.round(Math.max(0, from)), b = (int) Math.round(Math.min(w, from + seg));
			pill(g, x + a, y, b - a, h, fillArgb);
		} else {
			double fw = w * Math.max(0.0, Math.min(1.0, fill));
			int whole = (int) fw;
			pill(g, x, y, whole, h, fillArgb);
			double part = fw - whole;
			if (part > 0.02 && whole < w) {
				// The last, partly covered pixel column: the fill's end glides instead of stepping.
				g.fill(x + whole, y, x + whole + 1, y + h, fade(fillArgb, (float) part));
			}
		}
		pose.popPose();
	}

	/** A pill (a bar with round ends) in whole pixels: rows of equal inset are one fill each. */
	private static void pill(GuiGraphics g, int x, int y, int w, int h, int argb) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = h / 2;
		if (r == 0 || w < h) {
			g.fill(x, y, x + w, y + h, argb);
			return;
		}
		int row = 0;
		while (row < h) {
			int inset = inset(row, h, r);
			int end = row + 1;
			while (end < h && inset(end, h, r) == inset) {
				end++;
			}
			g.fill(x + inset, y + row, x + w - inset, y + end, argb);
			row = end;
		}
	}

	private static int inset(int row, int h, int r) {
		double dy = Math.abs(row + 0.5 - h / 2.0);
		return r - (int) Math.round(Math.sqrt(Math.max(0.0, r * (double) r - dy * dy)));
	}

	// ---- the caption ----

	private static void caption(GuiGraphics g, float layer, String text, boolean boot, int gw) {
		double cx = gw / 2.0;
		float em = OriginTheme.TITLE_SMALL_EM;   // GUI px: the same size as Connecting's Cancel label
		int argb = fade(OriginTheme.TITLE_TEXT_MUTED, layer);
		if (boot) {
			// Before Inter exists: Minecraft's ascii.png in whole physical pixels per font pixel, sized so its
			// 7-pixel caps come closest to Inter's cap height at this em (the line keeps its size later).
			double capPhys = em * INTER_CAP_PER_EM * gs;
			int per = Math.max(1, (int) Math.round(capPhys / 7.0));
			double scale = per / (double) gs;
			OriginScreenRenderer.bootText(g, text, cx, captionMid / gs - 3.5 * scale, (float) scale, 0, argb, 0.5f);
			return;
		}
		if (com.origin.client.client.gui.OriginSdfFont.active()) {
			float w = com.origin.client.client.gui.OriginSdfFont.widthEm(text, em, 0f, false);
			float cap = com.origin.client.client.gui.OriginSdfFont.capHeight(em, false);
			com.origin.client.client.gui.OriginSdfFont.drawEm(g, text, (float) (cx - w / 2.0), (float) (captionMid / gs + cap / 2.0),
					em, 0f, argb, false);
			return;
		}
		Font font = Minecraft.getInstance().font;
		g.drawString(font, text, (int) Math.round(cx - font.width(text) / 2.0), (int) Math.round(captionMid / gs - 4), argb, true);
	}

	/** A colour with its alpha scaled by {@code k} (0..1). */
	private static int fade(int argb, float k) {
		if (k >= 1f) {
			return argb;
		}
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, k));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
