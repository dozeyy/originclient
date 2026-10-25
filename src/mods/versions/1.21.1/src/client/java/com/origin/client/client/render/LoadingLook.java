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
 *   the official logo     72 design px box, centred at 44 % of the height, one faint orbit
 *                         hairline behind it (OrbitHairlines, A-Workbench-3)
 *   24 below              the rail: 200 design px wide, 2 GUI px tall fill (accent) over a
 *                         1 GUI px hairline track (white 8 %)
 *   12 below              the caption: textDim, the real stage name (+ a percentage only
 *                         where Minecraft reports one)
 * </pre>
 *
 * "Design px" are physical pixels at 1080p, scaled by window height / 1080 (never below
 * 0.75), so the picture looks the same at any resolution or GUI scale. Everything is drawn
 * in whole physical pixels (the pose is scaled back to 1 GUI unit = 1 pixel), so the rail
 * is crisp at every GUI scale.
 *
 * <p>Cost: one fill, one cached orbit-buffer draw, one logo blit, a few rail fills, one caption line. No shader, no blur,
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
	// CLEAN_BG (color.bg), CLEAN_BAR_TRACK (white 8 %), CLEAN_BAR_FILL (accent),
	// CLEAN_CAPTION (color.textDim).

	/** Logo box, design px. */
	private static final double LOGO = 72.0;
	/** Logo centre, share of the window height. */
	static final double LOGO_AT = 0.44;
	private static final double RAIL_W = 200.0;
	/** The bar is 2 GUI px tall (A-Workbench-3) over a 1 GUI px hairline track. */
	private static final int RAIL_GUI = 2, TRACK_GUI = 1;
	/** The faint orbit pill behind the logo: half-extents (long, short) as shares of the logo box, tilted 120 deg. */
	private static final double ORBIT_A = 1.1, ORBIT_B = 0.4;
	private static final OrbitHairlines ORBIT = new OrbitHairlines(1, 120.0);
	/** Logo box to rail, rail to caption, design px. */
	private static final double GAP_RAIL = 24.0, GAP_CAPTION = 12.0;
	/** The caption's line box, design px (type.caption is 12 / 16). */
	private static final double CAPTION_BOX = 16.0, CAPTION_EM = 12.0;
	/** The sweep: a 28 % segment, one pass per 1.2 s. */
	private static final double SWEEP_SHARE = 0.28, SWEEP_MS = 1200.0;

	// The frame's geometry, in physical pixels (computed by layout()).
	private static int gs, physW, physH;
	private static double k, logoBox, logoCy, railTop, railW, railH, captionMid;

	private LoadingLook() {
	}

	private static double scale() {
		return Math.max(0.75, Minecraft.getInstance().getWindow().getHeight() / 1080.0);
	}

	/**
	 * GUI px from the logo's centre down to the caption's bottom edge at this window's scale:
	 * what a screen with a button below the picture needs to know to fit it above the button.
	 */
	static double belowCentreGui() {
		Minecraft mc = Minecraft.getInstance();
		double kk = scale();
		double rail = RAIL_GUI * Math.max(1, Math.round(mc.getWindow().getGuiScale()));
		double phys = Math.round(LOGO * kk) / 2.0 + GAP_RAIL * kk + rail + (GAP_CAPTION + CAPTION_BOX) * kk;
		return phys / Math.max(1.0, mc.getWindow().getGuiScale());
	}

	private static void layout(double cyPhys) {
		Minecraft mc = Minecraft.getInstance();
		gs = Math.max(1, (int) Math.round(mc.getWindow().getGuiScale()));
		physW = mc.getWindow().getWidth();
		physH = mc.getWindow().getHeight();
		k = scale();
		logoBox = Math.round(LOGO * k);
		logoCy = cyPhys;
		railW = Math.round(RAIL_W * k);
		railH = RAIL_GUI * gs;
		railTop = Math.round(logoCy + logoBox / 2.0 + GAP_RAIL * k);
		captionMid = railTop + railH + GAP_CAPTION * k + CAPTION_BOX * k / 2.0;
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

		// One faint orbit hairline behind the logo (A-Workbench-3), the title's rings' sibling. It
		// stays through the loading -> title hand-off (logo false) and fades with the layer.
		ORBIT.draw(g, (float) logoCxGui(), (float) logoCyGui(), (float) (logoSizeGui() * ORBIT_A),
				(float) (logoSizeGui() * ORBIT_B), OriginTheme.TITLE_ORBIT, layer);

		// The official logo, through the one drawing path (origin_logo.png at LOGO_PATH).
		if (logo) {
			long t0 = com.origin.client.client.OriginFrameLog.partStart();
			OriginScreenRenderer.drawLogo(g, logoCxGui(), logoCyGui(), logoSizeGui(), layer);
			com.origin.client.client.OriginFrameLog.part("o:logo", t0);
		}

		// The rail and the caption.
		long t0 = com.origin.client.client.OriginFrameLog.partStart();
		rail(g, layer, fill);
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

	// ---- the rail ----

	private static void rail(GuiGraphics g, float layer, double fill) {
		int w = (int) railW, h = (int) railH;
		int x = (int) Math.round((physW - railW) / 2.0), y = (int) railTop;
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
		if (boot) {
			// Whole physical pixels per font pixel: 1 at 1080p, 2 at 1620p, 3 at 4K.
			int per = Math.max(1, (int) Math.floor(CAPTION_EM * k / 8.0 + 0.25));
			double scale = per / (double) gs;
			OriginScreenRenderer.bootText(g, text, cx, captionMid / gs - 3.5 * scale, (float) scale, 0, OriginTheme.CLEAN_CAPTION, 0.5f);
			return;
		}
		if (com.origin.client.client.gui.OriginSdfFont.active()) {
			float em = (float) (CAPTION_EM * k / gs);
			float w = com.origin.client.client.gui.OriginSdfFont.widthEm(text, em, 0f, false);
			float cap = com.origin.client.client.gui.OriginSdfFont.capHeight(em, false);
			com.origin.client.client.gui.OriginSdfFont.drawEm(g, text, (float) (cx - w / 2.0), (float) (captionMid / gs + cap / 2.0),
					em, 0f, fade(OriginTheme.CLEAN_CAPTION, layer), false);
			return;
		}
		Font font = Minecraft.getInstance().font;
		g.drawString(font, text, (int) Math.round(cx - font.width(text) / 2.0), (int) Math.round(captionMid / gs - 4), fade(OriginTheme.CLEAN_CAPTION, layer), true);
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
