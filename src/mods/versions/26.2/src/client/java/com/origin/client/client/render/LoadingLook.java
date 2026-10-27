package com.origin.client.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import com.origin.client.client.theme.OriginTheme;

/**
 * The one loading picture, ported from 1.21.1's LoadingLook (the design truth,
 * docs/overhaul/DESIGN-SYSTEM.md section 15.3): the startup resource load, the world-loading
 * screens (LevelLoadingScreen, ProgressScreen, GenericMessageScreen) and "Connecting to the
 * server" all draw THIS layout and only the caption changes.
 *
 * <pre>
 *   the official logo     72 design px box, centred at 44 % of the height on EVERY loading
 *                         screen (Connecting included: its Cancel button moves, the logo
 *                         never does, so the logo carries across the screens)
 *   24 below the logo     the progress bar: 200 design px wide, 2 GUI px tall, capsule ends,
 *                         an emerald fill over a 1 GUI px hairline track with the real fraction,
 *                         or a segment sliding along it when there is no measure (Connecting)
 *   12 below the bar      the caption: Minecraft's own font, one font px per GUI px (the same
 *                         font as the loading Cancel's label), textMuted with vanilla's shadow,
 *                         the real stage name (+ a percentage only where Minecraft reports one)
 * </pre>
 *
 * <p>No rings, orbits or pills: the only ring shapes on these screens are inside origin_logo.png
 * itself, never redrawn around it (Will, 2026-10-25: drawn look-alikes read as a fake logo).
 *
 * <p>Backdrop rule (the parity spec, origin-port recipes/loading-screens.md, as 1.21.1): every
 * loading screen is OPAQUE flat color.bg (OriginTheme.CLEAN_BG, #0E1110) over the whole window,
 * drawn here first, never the world or a panorama showing through; the only translucency is the
 * startup load's dissolve into the title (the {@code layer} argument). The progress fill is exactly
 * its fraction wide: nothing at 0 %, no minimum width or nub (pill() draws nothing for w <= 0).
 *
 * "Design px" are physical pixels at 1080p, scaled by window height / 1080 (never below
 * 0.75), so the picture looks the same at any resolution or GUI scale. Everything is drawn
 * in whole physical pixels (the pose is scaled back to 1 GUI unit = 1 pixel), so the bar
 * is crisp at every GUI scale.
 *
 * <p>26.x era: plain GuiGraphicsExtractor fills and one logo blit only (per-blit ARGB, no
 * shader colour, Matrix3x2fStack pose). No Origin pipeline is built here, so the picture is safe
 * under the startup loading overlay (26.3: pipelines built there fail and latch off). Nothing is
 * allocated per frame: the geometry lives in static fields (render thread only) and the caption
 * strings are cached by their callers.
 *
 * <p>Text (Will, 2026-10-27: loading screens use Minecraft's font on every version): the startup
 * load draws before Minecraft's font is loaded, so its caption reads ascii.png itself
 * (OriginScreenRenderer.bootText, the boot font); every later screen uses Minecraft's Font. Both put
 * the same glyphs on the same pixels. No Inter/MSDF on any loading screen.
 */
final class LoadingLook {
	// Colours: OriginTheme's loading tokens, never copied here: CLEAN_BAR_FILL (the emerald
	// progress, #5FD09B), CLEAN_BAR_TRACK (the hairline track), TITLE_TEXT_MUTED (caption).

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

	/** The picture's own step (logo to bar, 24 design px) in GUI px: the gap from the caption down to Cancel. */
	static double stepGui() {
		return GAP_BAR * scale() / Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
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
	 * Draws the loading picture: the opaque backdrop, the logo, the bar and the caption.
	 *
	 * @param layer    whole-picture opacity 0..1 (the loading -> title dissolve; 1 elsewhere)
	 * @param fill     bar fill 0..1, or a negative number for the sweep (no real measure)
	 * @param caption  the stage line, "" for none
	 * @param boot     draw the caption from ascii.png itself (the startup load, before Minecraft's font is loaded)
	 */
	static void draw(GuiGraphicsExtractor g, float layer, double fill, String caption, boolean boot) {
		Minecraft mc = Minecraft.getInstance();
		layout(Math.round(mc.getWindow().getHeight() * LOGO_AT));
		int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();

		// The backdrop is flat color.bg, opaque (the dissolve's layer aside), the launcher's launch-cover colour.
		g.fill(0, 0, gw, gh, fade(OriginTheme.CLEAN_BG, layer));

		// The official logo, through the one drawing path (origin_logo.png at LOGO_PATH).
		OriginScreenRenderer.drawLogo(g, physW / 2.0 / gs, logoCy / gs, logoBox / gs, layer);

		bar(g, layer, fill);
		if (caption != null && !caption.isEmpty()) {
			caption(g, layer, caption, boot, gw);
		}
	}

	// ---- the progress bar ----

	private static void bar(GuiGraphicsExtractor g, float layer, double fill) {
		int w = (int) Math.round(BAR_W * k), h = BAR_GUI * gs;
		int x = (int) Math.round((physW - w) / 2.0);
		int y = barTop;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.scale(1f / gs, 1f / gs);
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
		pose.popMatrix();
	}

	/** A pill (a bar with round ends) in whole pixels: rows of equal inset are one fill each. */
	private static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
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

	private static void caption(GuiGraphicsExtractor g, float layer, String text, boolean boot, int gw) {
		double cx = gw / 2.0;
		int argb = fade(OriginTheme.TITLE_TEXT_MUTED, layer);
		// Minecraft's own font on every loading screen (Will, 2026-10-27), one font px = one GUI px, with
		// vanilla's shadow. Both paths below put the same glyphs on the same pixels: the caps' top row at
		// `top`, the ink (no trailing advance pixel) centred.
		int top = (int) Math.round(captionMid / gs - 4.0);
		if (boot) {
			// The startup load draws before Minecraft's font is loaded, so it reads ascii.png itself
			// (OriginScreenRenderer.bootText: the same advances and shadow as Font, centred on its ink).
			OriginScreenRenderer.bootText(g, text, cx, top, 1f, 0, argb, 0.5f);
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		boolean unicode = mc.options.forceUnicodeFont().get();
		if (text != inkText || unicode != inkUnicode) {
			// Measured once per caption string (callers cache their strings), not per frame.
			inkText = text;
			inkUnicode = unicode;
			inkWidth = Math.max(0, font.width(text) - 1);   // Font.width counts the last glyph's 1 px advance gap
		}
		g.text(font, text, (int) Math.round(cx - inkWidth / 2.0), top, argb, true);
	}

	// The last measured caption (render thread only): identity of the string, the font mode, its ink width.
	private static String inkText;
	private static boolean inkUnicode;
	private static int inkWidth;

	/** A colour with its alpha scaled by {@code k} (0..1). */
	private static int fade(int argb, float k) {
		if (k >= 1f) {
			return argb;
		}
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, k));
		return (a << 24) | (argb & 0xFFFFFF);
	}
}
