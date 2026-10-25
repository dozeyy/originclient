package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.theme.OriginTheme;

/**
 * The one loading picture, ported from 1.21.1's LoadingLook (the design truth,
 * docs/overhaul/DESIGN-SYSTEM.md section 15.3) onto this era's Gfx PoseStack shim:
 * the startup resource load, the world-loading screens and "Connecting to the server"
 * all draw THIS layout and only the caption changes.
 *
 * <pre>
 *   the official logo     72 design px box, centred at 44 % of the height on EVERY loading
 *                         screen (Connecting included: its Cancel button moves, the logo
 *                         never does)
 *   24 below the logo     the progress bar: 200 design px wide, 2 GUI px tall, capsule ends,
 *                         an emerald fill over a 1 GUI px hairline track with the real fraction,
 *                         or a segment sliding along it when there is no measure (Connecting)
 *   12 below the bar      the caption, textMuted, the real stage name (+ a percentage only
 *                         where Minecraft reports one)
 * </pre>
 *
 * <p>No rings, orbits or pills: the only ring shapes on these screens are inside
 * origin_logo.png itself, never redrawn around it (Will, 2026-10-25: drawn look-alikes read
 * as a fake logo).
 *
 * <p>Differences from 1.21.1, by era (same look): the caller draws the backdrop (this
 * version's own: near-black + centre lift at startup, the blurred panorama behind the world
 * load and Connecting), and the caption is Minecraft's own font after startup (no Inter/MSDF
 * on this era). The startup caption uses the boot font (Minecraft's ascii.png), as on 1.21.1,
 * because it draws before fonts are baked.
 *
 * <p>Colour: on 1.16.5 a fill's colour lives in its vertices and ignores the GL colour, and
 * on 1.17-1.19.4 fills multiply the shader colour, so the caller resets the colour to white
 * before draw() and every fill here carries its own faded alpha. The boot-font caption goes
 * through OriginScreenRenderer.bootText, which applies the dissolve itself.
 *
 * <p>"Design px" are physical pixels at 1080p, scaled by window height / 1080 (never below
 * 0.75). The bar is drawn in whole physical pixels (the pose is scaled back to 1 GUI unit =
 * 1 pixel), so it is crisp at every GUI scale. Nothing is allocated per frame: the geometry
 * lives in static fields (render thread only) and the caption strings are cached by callers.
 */
final class LoadingLook {
	/** Logo box, design px. */
	private static final double LOGO = 72.0;
	/** Logo centre, share of the window height: the same on every loading screen. */
	static final double LOGO_AT = 0.44;
	/** Bar width and the gaps logo bottom -> bar and bar -> caption, design px (DESIGN-SYSTEM 15.3). */
	private static final double BAR_W = 200.0, GAP_BAR = 24.0, GAP_CAPTION = 12.0;
	/** The bar: 2 GUI px tall (capsule ends, radius h/2) over a 1 GUI px hairline track. */
	private static final int BAR_GUI = 2, TRACK_GUI = 1;
	/** The caption's line box, GUI px (an 8 GUI px em in a 10 px line, like a vanilla text line). */
	private static final double CAPTION_LINE_GUI = 10.0;
	/** Inter's cap height per em: the boot-font caption matches 1.21.1's size with it. */
	private static final double INTER_CAP_PER_EM = 0.727;
	/** No measure: one slide of the segment across the bar, and the segment's share of the bar. */
	private static final double SWEEP_MS = 1600.0, SWEEP_SEG = 0.26;

	// The frame's geometry, in physical pixels (computed by layout()).
	private static int gs, physW, barTop;
	private static double k, logoBox, logoCy, captionMid;

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
		double s = scale(), box = Math.round(LOGO * s);
		return (box / 2.0 + (GAP_BAR + GAP_CAPTION) * s) / g + BAR_GUI + CAPTION_LINE_GUI;
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
		k = scale();
		logoBox = Math.round(LOGO * k);
		logoCy = cyPhys;
		barTop = (int) Math.round(logoCy + logoBox / 2.0 + GAP_BAR * k);
		captionMid = barTop + BAR_GUI * gs + GAP_CAPTION * k + CAPTION_LINE_GUI * gs / 2.0;
	}

	/**
	 * Draws the loading picture over the caller's backdrop. The caller resets the
	 * render colour to white first (see the class note).
	 *
	 * @param layer    whole-picture opacity 0..1 (the loading -> title dissolve; 1 elsewhere)
	 * @param fill     bar fill 0..1, or a negative number for the sweep (no real measure)
	 * @param caption  the stage line, "" for none
	 * @param boot     draw the caption in the boot font (the startup load) instead of Minecraft's font
	 */
	static void draw(Gfx g, float layer, double fill, String caption, boolean boot) {
		Minecraft mc = Minecraft.getInstance();
		layout(Math.round(mc.getWindow().getHeight() * LOGO_AT));
		int gw = mc.getWindow().getGuiScaledWidth();
		// The official logo, through the one drawing path (origin_logo.png, baked per size).
		OriginScreenRenderer.drawLogo(g, physW / 2.0 / gs, logoCy / gs, logoBox / gs, layer);
		bar(g, layer, fill);
		if (caption != null && !caption.isEmpty()) {
			caption(g, layer, caption, boot, gw);
		}
	}

	// ---- the progress bar ----

	private static void bar(Gfx g, float layer, double fill) {
		int w = (int) Math.round(BAR_W * k), h = BAR_GUI * gs;
		int x = (int) Math.round((physW - w) / 2.0);
		int y = barTop;
		PoseStack pose = g.pose();
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

	private static void caption(Gfx g, float layer, String text, boolean boot, int gw) {
		double cx = gw / 2.0;
		if (boot) {
			// Minecraft's ascii.png in whole physical pixels per font pixel, sized so its 7-pixel
			// caps come closest to 1.21.1's caption cap height. bootText applies the dissolve
			// (layerAlpha) itself, so the colour goes in unfaded.
			double capPhys = OriginTheme.LOADING_CAPTION_EM * INTER_CAP_PER_EM * gs;
			int per = Math.max(1, (int) Math.round(capPhys / 7.0));
			double scale = per / (double) gs;
			OriginScreenRenderer.bootText(g, text, cx, captionMid / gs - 3.5 * scale, (float) scale, 0,
					OriginTheme.LOADING_CAPTION, 0.5f);
			return;
		}
		Font font = Minecraft.getInstance().font;
		g.drawString(font, text, (int) Math.round(cx - font.width(text) / 2.0), (int) Math.round(captionMid / gs - 4),
				fade(OriginTheme.LOADING_CAPTION, layer), true);
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
