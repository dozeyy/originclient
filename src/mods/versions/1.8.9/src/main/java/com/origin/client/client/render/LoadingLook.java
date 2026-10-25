package com.origin.client.client.render;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.Pose;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

/**
 * The one loading picture, ported from 1.21.1's LoadingLook (DESIGN-SYSTEM 15.3): the
 * startup splash (OriginTitleScreen's settle hold and dissolve), the world-loading scenes
 * (GuiDownloadTerrain, GuiScreenWorking, the LoadingScreenRenderer replacement) and
 * "Connecting to the server" all draw THIS layout over their own backdrop; only the
 * caption changes.
 *
 * <pre>
 *   the official logo     72 design px box, centred at 44 % of the height on EVERY loading
 *                         screen (Connecting's Cancel moves, the logo never does)
 *   24 below the logo     the progress bar: 200 design px wide, 2 GUI px tall, capsule ends,
 *                         an emerald fill over a 1 GUI px hairline track with the real fill,
 *                         or a segment sliding along it when there is no measure (Connecting)
 *   12 below the bar      the caption: one line, the real stage name
 * </pre>
 *
 * <p>No rings, orbits or pills: the only ring shapes on these screens are inside
 * origin_logo.png itself, never redrawn around it (Will, 2026-10-25: drawn look-alikes read
 * as a fake logo).
 *
 * <p>"Design px" are physical pixels at 1080p, scaled by window height / 1080 (never below
 * 0.75), so the picture looks the same at any resolution or GUI scale. The bar is drawn in
 * whole physical pixels (the pose is scaled back to 1 GUI unit = 1 pixel), so it is crisp
 * at every GUI scale.
 *
 * <p>1.8.9 differences from 1.21.1, on purpose: the backdrop is each screen's own (the caller
 * draws it: the panorama under the loading dim, the menu backdrop, or the splash's near-black),
 * and the caption is Minecraft's own font with its shadow (DECISIONS: Inter only on 1.21.1's
 * title text; 1.8.9 fonts are baked before anything here draws, so no boot font is needed).
 * The window size comes from Minecraft.displayWidth/displayHeight and the GUI scale from
 * OriginScreenRenderer.scale() (1.8.9's ScaledResolution rule, allocation-free).
 *
 * <p>Cost: one logo blit (baked once per size by OriginScreenRenderer.drawLogo), a few bar
 * fills, one caption line. Nothing is allocated per frame: the geometry lives in static
 * fields (render thread only) and the caption strings are cached by the callers.
 */
final class LoadingLook {
	/** Logo box, design px. */
	private static final double LOGO = 72.0;
	/** Logo centre, share of the window height: the same on every loading screen. */
	private static final double LOGO_AT = 0.44;
	/** Bar width and the gaps logo bottom -> bar and bar -> caption, design px (DESIGN-SYSTEM 15.3). */
	private static final double BAR_W = 200.0, GAP_BAR = 24.0, GAP_CAPTION = 12.0;
	/** The bar: 2 GUI px tall (capsule ends, radius h/2) over a 1 GUI px hairline track. */
	private static final int BAR_GUI = 2, TRACK_GUI = 1;
	/** The caption's line box, GUI px (Minecraft's 8 px font in a 10 px line). */
	private static final double CAPTION_LINE_GUI = 10.0;
	/** No measure: one slide of the segment across the bar, and the segment's share of the bar. */
	private static final double SWEEP_MS = 1600.0, SWEEP_SEG = 0.26;

	// The frame's geometry, in physical pixels (computed by layout()).
	private static int gs, physW, barTop;
	private static double k, logoBox, logoCy, captionMid;

	private LoadingLook() {
	}

	private static double scale() {
		return Math.max(0.75, Math.max(1, Minecraft.getMinecraft().displayHeight) / 1080.0);
	}

	/**
	 * GUI px from the logo's centre down to the caption's bottom edge at this window's scale: what a
	 * screen with a button below the picture (Connecting's Cancel) needs to keep the button clear of it.
	 */
	static double belowCentreGui() {
		double g = OriginScreenRenderer.scale();
		double s = scale(), box = Math.round(LOGO * s);
		return (box / 2.0 + (GAP_BAR + GAP_CAPTION) * s) / g + BAR_GUI + CAPTION_LINE_GUI;
	}

	/** The logo centre's height, GUI px, on every loading screen at this window size (what draw() uses). */
	static double logoCentreGui() {
		return Math.round(Math.max(1, Minecraft.getMinecraft().displayHeight) * LOGO_AT) / (double) OriginScreenRenderer.scale();
	}

	private static void layout() {
		Minecraft mc = Minecraft.getMinecraft();
		gs = OriginScreenRenderer.scale();
		physW = Math.max(1, mc.displayWidth);
		k = scale();
		logoBox = Math.round(LOGO * k);
		logoCy = Math.round(Math.max(1, mc.displayHeight) * LOGO_AT);
		barTop = (int) Math.round(logoCy + logoBox / 2.0 + GAP_BAR * k);
		captionMid = barTop + BAR_GUI * gs + GAP_CAPTION * k + CAPTION_LINE_GUI * gs / 2.0;
	}

	/**
	 * Draws the loading picture (no backdrop: the caller has drawn its own).
	 *
	 * @param layer    whole-picture opacity 0..1 (the splash -> title dissolve; 1 elsewhere)
	 * @param fill     bar fill 0..1, or a negative number for the sweep (no real measure)
	 * @param caption  the stage line, "" for none
	 */
	static void draw(Gfx g, float layer, double fill, String caption) {
		layout();
		OriginScreenRenderer.drawLogo(g, physW / 2.0 / gs, logoCy / gs, logoBox / gs, layer);
		bar(g, layer, fill);
		if (caption != null && !caption.isEmpty()) {
			caption(g, layer, caption);
		}
		Gfx.resetColor();
	}

	// ---- the progress bar ----

	private static void bar(Gfx g, float layer, double fill) {
		int w = (int) Math.round(BAR_W * k), h = BAR_GUI * gs;
		int x = (int) Math.round((physW - w) / 2.0);
		int y = barTop;
		Pose pose = g.pose();
		pose.pushPose();
		pose.scale(1f / gs, 1f / gs, 1f);
		// The track: a 1 GUI px hairline through the middle of the 2 GUI px band.
		int th = TRACK_GUI * gs;
		pill(g, x, y + (h - th) / 2, w, th, fade(OriginTheme.CLEAN_BAR_TRACK, layer));
		int fillArgb = fade(OriginTheme.CLEAN_BAR_FILL, layer);
		if (fill < 0) {
			// Indeterminate: a segment slides across (never a made-up percentage).
			double t = (SceneClock.sceneMs() % SWEEP_MS) / SWEEP_MS;
			double eased = t * t * (3.0 - 2.0 * t);
			double seg = w * SWEEP_SEG;
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
	private static void pill(Gfx g, int x, int y, int w, int h, int argb) {
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

	private static void caption(Gfx g, float layer, String text) {
		int argb = fade(OriginTheme.CLEAN_TEXT_DIM, layer);
		// FontRenderer draws a colour whose alpha is under 4 fully opaque.
		if (((argb >>> 24) & 0xFF) < 4) {
			return;
		}
		FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
		double cx = physW / 2.0 / gs;
		g.drawString(font, text, (int) Math.round(cx - font.getStringWidth(text) / 2.0),
				(int) Math.round(captionMid / gs - 4), argb, true);
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
