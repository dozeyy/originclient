package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The drawing kit for the 2026-10 menu redesign (design/menu-redesign, approved
 * by Will as mock v5). Screens built on it lay out in DESIGN PIXELS -- the
 * mock's own units, drawn at 1920x1080 where one design px is one screen
 * pixel -- and the kit maps them onto the real window:
 *
 *   * {@link #scale} picks physical px per design px from the window size
 *     alone, so the menu looks the same at every Minecraft GUI scale (the way
 *     Lunar's menu does) instead of growing in 1/2/3/4x jumps;
 *   * every draw happens in PHYSICAL pixels (pose scaled by 1/guiScale), with
 *     box edges snapped to whole pixels, so 1px lines are exactly 1px and text
 *     (MSDF Inter, the smooth font Will picked) is placed at sub-pixel
 *     precision -- this is what makes the words line up with the lines;
 *   * click targets are recorded while drawing ({@link #hit}) and replayed by
 *     the screen's mouseClicked, so what you see and what you click can never
 *     disagree (one geometry, not a render copy and an input copy).
 *
 * Colours are the mock's :root tokens. Everything fails soft: no MSDF shader ->
 * vanilla text.
 *
 * PER-VERSION DELTA (1.21.6-1.21.8, the deferred GUI): every draw here is QUEUED
 * into GuiGraphics' GuiRenderState and rendered later in batches, so nothing may
 * draw immediately or touch GL state:
 *   * the pose is a 2D Matrix3x2fStack (pushMatrix / scale(x, y) / popMatrix);
 *   * rounded boxes are OriginUi.roundRect's baked coverage masks at one texel per
 *     physical pixel (this era has no rounded-box SDF pipeline -- see OriginShaders);
 *     text is OriginSdfFont's MSDF render state, icons ModIcons' SDF render state,
 *     all of them GuiElementRenderStates, so they layer in submission order;
 *   * clips go through GuiGraphics.enableScissor, the scissor every queued element
 *     records at submit time (a raw GL scissor would be long gone by the time the
 *     batches draw). See {@link #applyClip} for the coordinate handling.
 */
public final class OriginDeck {
	// ---- the mock's palette (design/menu-redesign/mock/mock4.tpl.html :root) ----
	public static final int PANEL = 0xF0141816;      // rgba(20,24,22,.94)
	public static final int CARD = 0xFF1D2320;
	public static final int CARD_H = 0xFF232A26;
	public static final int LINE = 0x0FFFFFFF;       // white .06
	public static final int LINE2 = 0x1AFFFFFF;      // white .10
	public static final int TEXT = 0xFFF1E9D2;
	public static final int DIM = 0xADF1E9D2;        // text .68
	public static final int MUTE = 0x6BF1E9D2;       // text .42
	public static final int ACC = 0xFF5FD09B;
	public static final int ACC_D = 0xFF2E7A57;
	public static final int ACC_SOFT = 0x245FD09B;   // accent .14
	public static final int ON_ACC = 0xFF0A1710;     // text on an accent fill
	public static final int ON_TEXT = 0xFFDFF7EA;    // text on the ENABLED bar
	public static final int RED = 0xFFD9675A;
	public static final int RED_SOFT = 0x29D9675A;   // red .16
	public static final int GOLD = 0xFFE9C46A;
	public static final int GOLD_BG = 0xFF3A3220;
	public static final int FIELD = 0x47000000;      // black .28 (search / text fields)

	/** Letter-spacing of the small all-caps labels (the mock's `.caps`). */
	public static final float CAPS_TRACK = 2.4f;

	public final GuiGraphics g;
	public final float s;      // physical px per design px
	public final double gs;    // Minecraft's GUI scale (GUI px -> physical px)
	public final float ox, oy; // where design (0,0) sits, in physical px
	public final float mx, my; // the mouse, in design px
	public float alpha = 1f;   // global fade (open / close / page swap)

	private final ArrayDeque<float[]> clips = new ArrayDeque<>();
	// The pose as the screen handed it over, before this frame's 1/guiScale --
	// the space GuiGraphics' scissor stack is specified in (see applyClip).
	private final Matrix3x2f basePose;
	private final List<Hit> hits;

	/** A click target recorded at draw time; see the class comment. */
	public record Hit(float x0, float y0, float x1, float y1, Runnable action) {
		boolean contains(float x, float y) {
			return x >= x0 && x < x1 && y >= y0 && y < y1;
		}
	}

	/**
	 * Physical px per design px for a window: the mock's 1080p size, grown on
	 * taller windows, never shrunk below 75% (keeps 13px text readable), and
	 * always small enough that a designW x designH panel fits with a margin.
	 */
	public static float scale(int winW, int winH, float designW, float designH) {
		float s = Math.max(winH / 1080f, 0.75f);
		s = Math.min(s, Math.min((winW - 32f) / designW, (winH - 32f) / designH));
		return Math.max(0.35f, s);
	}

	/** Opens a frame. `mouseX/Y` are the GUI-px mouse; `hits` is cleared and refilled. */
	public OriginDeck(GuiGraphics g, float s, float ox, float oy, double mouseX, double mouseY, List<Hit> hits) {
		this.g = g;
		this.s = s;
		this.gs = Minecraft.getInstance().getWindow().getGuiScale();
		this.ox = ox;
		this.oy = oy;
		this.mx = (float) ((mouseX * gs - ox) / s);
		this.my = (float) ((mouseY * gs - oy) / s);
		this.hits = hits;
		hits.clear();
		this.basePose = new Matrix3x2f(g.pose());
		g.pose().pushMatrix();
		g.pose().scale((float) (1.0 / gs), (float) (1.0 / gs));
	}

	/** Closes the frame (pops the physical-pixel pose, drops any open clip). */
	public void end() {
		while (!clips.isEmpty()) {
			unclip();
		}
		g.pose().popMatrix();
	}

	/** GUI px -> design px (for input events between frames). */
	public static float toDesign(double gui, double gs, float origin, float s) {
		return (float) ((gui * gs - origin) / s);
	}

	/** Design px -> GUI px (to place vanilla-coordinate overlays like the colour picker). */
	public int guiX(float x) {
		return (int) Math.round((ox + x * s) / gs);
	}

	public int guiY(float y) {
		return (int) Math.round((oy + y * s) / gs);
	}

	// ---- colour ----

	/** `argb` with the frame's fade applied. */
	public int fa(int argb) {
		return mul(argb, alpha);
	}

	public static int mul(int argb, float k) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, k)));
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** `top` composited over `bottom` (source-over), as one ARGB colour. */
	public static int over(int top, int bottom) {
		float ta = ((top >>> 24) & 0xFF) / 255f, ba = ((bottom >>> 24) & 0xFF) / 255f;
		float a = ta + ba * (1 - ta);
		if (a <= 0f) {
			return 0;
		}
		int out = Math.round(a * 255) << 24;
		for (int sh = 0; sh <= 16; sh += 8) {
			float tc = ((top >> sh) & 0xFF), bc = ((bottom >> sh) & 0xFF);
			out |= Math.round((tc * ta + bc * ba * (1 - ta)) / a) << sh;
		}
		return out;
	}

	public static int lerp(int a, int b, float t) {
		return com.origin.client.client.theme.OriginTheme.lerpColor(a, b, t);
	}

	// ---- geometry ----

	private float px(float x) {
		return ox + x * s;
	}

	private float py(float y) {
		return oy + y * s;
	}

	/** A box snapped to whole physical pixels: {x, y, w, h} in physical px. */
	private float[] snap(float x, float y, float w, float h) {
		float x0 = Math.round(px(x)), y0 = Math.round(py(y));
		float x1 = Math.round(px(x + w)), y1 = Math.round(py(y + h));
		return new float[]{x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0)};
	}

	/** Filled rounded box (radius in design px). */
	public void rect(float x, float y, float w, float h, float r, int fill) {
		rect(x, y, w, h, r, fill, 0);
	}

	/** Filled rounded box with a 1-physical-px inside border. */
	public void rect(float x, float y, float w, float h, float r, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		float[] b = snap(x, y, w, h);
		// The border ring REPLACES the fill (1.21.1's SDF shader; roundRect's mask
		// partition here); the mock's borders are translucent overlays (white .06
		// over the card), so composite the border onto the fill first.
		int bd = ((border >>> 24) & 0xFF) == 0 ? 0 : over(border, fill);
		OriginUi.roundRect(g, b[0], b[1], b[2], b[3], r * s, fa(fill), fa(bd), 0f);
	}

	/** A filled circle (switch knobs, slider thumbs, the "new" dot). */
	public void circle(float cx, float cy, float rad, int fill) {
		float r = rad * s;
		OriginUi.roundRect(g, px(cx) - r, py(cy) - r, r * 2, r * 2, r, fa(fill), 0, 1.5f);
	}

	/** A hairline or plain box, snapped (dividers, slider tracks). */
	public void fill(float x, float y, float w, float h, int color) {
		float[] b = snap(x, y, w, h);
		g.fill((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), fa(color));
	}

	/** Vertical gradient box (the grid's bottom fade). */
	public void gradient(float x, float y, float w, float h, int top, int bottom) {
		float[] b = snap(x, y, w, h);
		g.fillGradient((int) b[0], (int) b[1], (int) (b[0] + b[2]), (int) (b[1] + b[3]), fa(top), fa(bottom));
	}

	/** Soft layered drop shadow under a panel. */
	public void shadow(float x, float y, float w, float h, float r) {
		int[] alphas = {0x1A, 0x10, 0x08};
		for (int i = 0; i < alphas.length; i++) {
			float grow = 10 * (i + 1);
			rect(x - grow, y - grow + 14, w + grow * 2, h + grow * 2, r + grow, alphas[i] << 24);
		}
	}

	// ---- text (MSDF Inter, baseline-placed) ----

	public float textW(String str, float size, boolean bold, float tracking) {
		if (OriginSdfFont.ready()) {
			return OriginSdfFont.widthEm(str, size, tracking, bold);
		}
		var font = Minecraft.getInstance().font;
		return font.width(str) * vanillaK(size) / s + tracking * Math.max(0, str.length() - 1);
	}

	public float capH(float size, boolean bold) {
		return OriginSdfFont.ready() ? OriginSdfFont.capHeight(size, bold) : size * 0.727f;
	}

	/** Text with its baseline at `baseline` (design px). Returns the advance. */
	public float text(String str, float x, float baseline, float size, boolean bold, float tracking, int color) {
		if (str == null || str.isEmpty()) {
			return 0;
		}
		if ((fa(color) >>> 24) < 4) {
			// vanilla Font treats alpha < 4 as opaque; a faded-out label must vanish
			return textW(str, size, bold, tracking);
		}
		if (OriginSdfFont.ready()) {
			OriginSdfFont.drawEm(g, str, px(x), py(baseline), size * s, tracking * s, fa(color), bold);
		} else {
			// Fail-soft: vanilla glyphs scaled so their caps match Inter's.
			var font = Minecraft.getInstance().font;
			float k = vanillaK(size);
			var pose = g.pose();
			pose.pushMatrix();
			pose.translate(px(x), py(baseline) - 7 * k);
			pose.scale(k, k);
			g.drawString(font, str, 0, 0, fa(color), false);
			pose.popMatrix();
		}
		return textW(str, size, bold, tracking);
	}

	private float vanillaK(float size) {
		return size * 0.727f * s / 7f;
	}

	/** Text optically centred (on its cap height) on `cy`. */
	public float textMid(String str, float x, float cy, float size, boolean bold, float tracking, int color) {
		return text(str, x, cy + capH(size, bold) / 2f, size, bold, tracking, color);
	}

	/** Text centred on (cx, cy). */
	public void textCenter(String str, float cx, float cy, float size, boolean bold, float tracking, int color) {
		textMid(str, cx - textW(str, size, bold, tracking) / 2f, cy, size, bold, tracking, color);
	}

	/** Text right-aligned to `right`, centred on `cy`. */
	public void textRight(String str, float right, float cy, float size, boolean bold, float tracking, int color) {
		textMid(str, right - textW(str, size, bold, tracking), cy, size, bold, tracking, color);
	}

	/** `str` shortened with an ellipsis to fit `maxW`. */
	public String fit(String str, float maxW, float size, boolean bold, float tracking) {
		if (textW(str, size, bold, tracking) <= maxW) {
			return str;
		}
		String out = str;
		while (out.length() > 1 && textW(out + "…", size, bold, tracking) > maxW) {
			out = out.substring(0, out.length() - 1);
		}
		return out.stripTrailing() + "…";
	}

	/** Word-wraps `str` into lines no wider than `maxW`. */
	public List<String> wrap(String str, float maxW, float size, boolean bold) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String word : str.split(" ")) {
			String test = cur.length() == 0 ? word : cur + " " + word;
			if (textW(test, size, bold, 0) > maxW && cur.length() > 0) {
				out.add(cur.toString());
				cur = new StringBuilder(word);
			} else {
				cur = new StringBuilder(test);
			}
		}
		if (cur.length() > 0) {
			out.add(cur.toString());
		}
		return out;
	}

	// ---- icons + brand ----

	/** An atlas glyph (ModIcons id) in a size x size box at (x,y). */
	public void icon(String id, float x, float y, float size, int color) {
		int sz = Math.round(size * s);
		ModIcons.draw(g, id, Math.round(px(x + size / 2f) - sz / 2f), Math.round(py(y + size / 2f) - sz / 2f), sz, fa(color));
	}

	/** The Origin mark (textures/ui/origin_logo.png) with its soft white glow. */
	public void logo(float cx, float cy, float size, float glow) {
		if (glow > 0) {
			OriginUi.softGlow(g, px(cx), py(cy), size * s * 1.6f, mul(0xFFFFFFFF, glow * alpha));
		}
		OriginUi.logo(g, px(cx), py(cy), Math.round(size * s), alpha);
	}

	// ---- controls ----

	/** The mock's toggle: 36x20 pill, accent when on, knob slides (eased). */
	public void toggle(String id, float x, float y, boolean on) {
		float k = OriginUi.anim("deck-sw:" + id, on, 150.0);
		rect(x, y, 36, 20, 10, lerp(0x1FFFFFFF, ACC, k));
		circle(x + 10 + 16 * k, y + 10, 7, lerp(0xB3F1E9D2, 0xFFFFFFFF, k));
	}

	/** The mock's slider: a 4px track, accent fill, 14px thumb. `t` in 0..1. */
	public void slider(float x, float cy, float w, float t, boolean active) {
		t = Math.max(0f, Math.min(1f, t));
		rect(x, cy - 2, w, 4, 2, 0x1FFFFFFF);
		if (t > 0) {
			rect(x, cy - 2, w * t, 4, 2, ACC);
		}
		circle(x + w * t, cy, active ? 8 : 7, TEXT);
	}

	// ---- clipping (scroll regions) ----

	/** Restricts drawing AND hit targets to a design-px box until {@link #unclip}. */
	public void clip(float x, float y, float w, float h) {
		float[] c = {x, y, x + w, y + h};
		if (!clips.isEmpty()) {
			float[] p = clips.peek();
			c = new float[]{Math.max(c[0], p[0]), Math.max(c[1], p[1]), Math.min(c[2], p[2]), Math.min(c[3], p[3])};
		}
		clips.push(c);
		applyClip(c);
	}

	public void unclip() {
		clips.pop();
		// One GuiGraphics scissor per clip(): popping it restores the parent clip
		// (GuiGraphics' scissor stack intersects nested regions itself).
		g.disableScissor();
	}

	// 1.21.6-1.21.8: the clip is a GuiGraphics scissor, because every element is
	// queued and only drawn later -- each one records the scissor-stack top when
	// it is submitted, and the renderer applies it at batch time. That stack is in
	// GUI pixels (whole ints; the renderer multiplies by the GUI scale), so a clip
	// can only sit on GUI-pixel boundaries, i.e. on a multiple of guiScale physical
	// px. It is rounded INWARD, so a scroll region never bleeds past its designed
	// box (it may stop up to guiScale-1 physical px short -- inside the margins the
	// layout already leaves around every scroll region). enableScissor maps its
	// rectangle through the CURRENT pose, so it is issued under the pose the screen
	// handed us (pushMatrix + set(basePose)), not this kit's 1/guiScale pose: the
	// GUI-px ints go in unchanged instead of being divided and floored again.
	private void applyClip(float[] c) {
		int gx0 = (int) Math.ceil(px(c[0]) / gs - 1e-4), gy0 = (int) Math.ceil(py(c[1]) / gs - 1e-4);
		int gx1 = (int) Math.floor(px(c[2]) / gs + 1e-4), gy1 = (int) Math.floor(py(c[3]) / gs + 1e-4);
		var pose = g.pose();
		pose.pushMatrix();
		pose.set(basePose);
		g.enableScissor(gx0, gy0, Math.max(gx0, gx1), Math.max(gy0, gy1));
		pose.popMatrix();
	}

	private boolean inClip(float x, float y) {
		if (clips.isEmpty()) {
			return true;
		}
		float[] c = clips.peek();
		return x >= c[0] && x < c[2] && y >= c[1] && y < c[3];
	}

	// ---- input ----

	/** Is the mouse over this box (and inside the current clip)? */
	public boolean over(float x, float y, float w, float h) {
		return mx >= x && mx < x + w && my >= y && my < y + h && inClip(mx, my);
	}

	/** Records a click target (clipped to the current scroll region). Later hits win. */
	public void hit(float x, float y, float w, float h, Runnable action) {
		float x0 = x, y0 = y, x1 = x + w, y1 = y + h;
		if (!clips.isEmpty()) {
			float[] c = clips.peek();
			x0 = Math.max(x0, c[0]);
			y0 = Math.max(y0, c[1]);
			x1 = Math.min(x1, c[2]);
			y1 = Math.min(y1, c[3]);
		}
		if (x1 > x0 && y1 > y0) {
			hits.add(new Hit(x0, y0, x1, y1, action));
		}
	}

	/** Runs the topmost hit under (x,y) design px. True if one ran. */
	public static boolean click(List<Hit> hits, float x, float y) {
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit h = hits.get(i);
			if (h.contains(x, y)) {
				h.action().run();
				return true;
			}
		}
		return false;
	}
}
