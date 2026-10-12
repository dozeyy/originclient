package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;

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
 * vanilla text, no SDF shader -> software rounded rects.
 *
 * 1.17.1: a port of 1.21.1's kit onto this module's pre-1.20 backend -- every
 * draw goes through Gfx (PoseStack + GuiComponent statics) instead of
 * GuiGraphics; the MSDF text, SDF boxes and scissor are the same mechanisms
 * (this module's OriginShaders compiles the core shaders on demand). Every Gfx
 * fill/blit/text call draws immediately, so the GL scissor below covers
 * exactly what is drawn inside a clip.
 *
 * See-through mode ({@link #seeThrough}): with no panel behind it, the mock's
 * dim cream text washes out over the world (Will, 2026-10-12: "the words
 * should be way more visible"). The text and icon paths then lift every light
 * colour toward opaque white -- keeping title > body > secondary order -- and,
 * where the text is NOT on an opaque surface this frame (a card, a popover),
 * put a soft dark halo and a 1px drop shadow under it. Screens only set the
 * flag; every widget drawn through the kit gets it.
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

	public final Gfx g;
	public final float s;      // physical px per design px
	public final double gs;    // Minecraft's GUI scale (GUI px -> physical px)
	public final float ox, oy; // where design (0,0) sits, in physical px
	public final float mx, my; // the mouse, in design px
	public float alpha = 1f;   // global fade (open / close / page swap)

	/**
	 * The screen has no panel behind its content (see the class comment). Set it
	 * right after opening the frame, before drawing; off = the approved look,
	 * byte for byte.
	 */
	public boolean seeThrough = false;

	/** Fill alpha from which a box counts as an opaque surface text can sit on unaided. */
	private static final int SOLID_A = 0xC0;

	private final ArrayDeque<float[]> clips = new ArrayDeque<>();
	// opaque boxes drawn so far this frame (design px x0,y0,x1,y1); see-through mode only
	private final List<float[]> solids = new ArrayList<>();
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
	public OriginDeck(Gfx g, float s, float ox, float oy, double mouseX, double mouseY, List<Hit> hits) {
		this.g = g;
		this.s = s;
		this.gs = Minecraft.getInstance().getWindow().getGuiScale();
		this.ox = ox;
		this.oy = oy;
		this.mx = (float) ((mouseX * gs - ox) / s);
		this.my = (float) ((mouseY * gs - oy) / s);
		this.hits = hits;
		hits.clear();
		g.pose().pushPose();
		g.pose().scale((float) (1.0 / gs), (float) (1.0 / gs), 1f);
	}

	/** Closes the frame (pops the physical-pixel pose, drops any open clip). */
	public void end() {
		while (!clips.isEmpty()) {
			unclip();
		}
		g.pose().popPose();
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
		// The SDF shader REPLACES the fill with the border colour in the border
		// ring; the mock's borders are translucent overlays (white .06 over the
		// card), so composite the border onto the fill first.
		int bd = ((border >>> 24) & 0xFF) == 0 ? 0 : over(border, fill);
		OriginUi.roundRect(g, b[0], b[1], b[2], b[3], r * s, fa(fill), fa(bd), 0f);
		if (seeThrough && ((fill >>> 24) & 0xFF) >= SOLID_A) {
			solids.add(new float[]{x, y, x + w, y + h});
		}
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
		float adv = textW(str, size, bold, tracking);
		if ((fa(color) >>> 24) < 4) {
			// vanilla Font treats alpha < 4 as opaque; a faded-out label must vanish
			return adv;
		}
		if (seeThrough && isLight(color)) {
			color = legible(color);
			float top = baseline - capH(size, bold), bottom = baseline + size * 0.18f;
			if (!onSolid(x + adv / 2f, (top + bottom) / 2f)) {
				float k = ((color >>> 24) & 0xFF) / 255f;
				halo(x, top, x + adv, bottom, Math.min(adv, bottom - top), k);
				float d = dropPx();
				rawText(str, px(x), py(baseline) + d, size, bold, tracking, fa(mul(0xFF000000, 0.6f * k)));
			}
		}
		rawText(str, px(x), py(baseline), size, bold, tracking, fa(color));
		return adv;
	}

	/** One text draw at a PHYSICAL-px baseline position, colour already final. */
	private void rawText(String str, float physX, float physBaseline, float size, boolean bold, float tracking, int argb) {
		if ((argb >>> 24) < 4) {
			return; // vanilla Font would draw alpha < 4 as opaque
		}
		if (OriginSdfFont.ready()) {
			OriginSdfFont.drawEm(g, str, physX, physBaseline, size * s, tracking * s, argb, bold);
		} else {
			// Fail-soft: vanilla glyphs scaled so their caps match Inter's.
			var font = Minecraft.getInstance().font;
			float k = vanillaK(size);
			var pose = g.pose();
			pose.pushPose();
			pose.translate(physX, physBaseline - 7 * k, 0);
			pose.scale(k, k, 1f);
			g.drawString(font, str, 0, 0, argb, false);
			pose.popPose();
		}
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
		int ix = Math.round(px(x + size / 2f) - sz / 2f), iy = Math.round(py(y + size / 2f) - sz / 2f);
		if (seeThrough && isLight(color) && (fa(color) >>> 24) > 2) {
			color = legible(color);
			if (!onSolid(x + size / 2f, y + size / 2f)) {
				float k = ((color >>> 24) & 0xFF) / 255f;
				halo(x, y, x + size, y + size, size * 0.8f, k);
				ModIcons.draw(g, id, ix, iy + Math.round(dropPx()), sz, fa(mul(0xFF000000, 0.6f * k)));
			}
		}
		ModIcons.draw(g, id, ix, iy, sz, fa(color));
	}

	/** The Origin mark (textures/ui/origin_logo.png) with its soft white glow. */
	public void logo(float cx, float cy, float size, float glow) {
		if (glow > 0) {
			OriginUi.softGlow(g, px(cx), py(cy), size * s * 1.6f, mul(0xFFFFFFFF, glow * alpha));
		}
		OriginUi.logo(g, px(cx), py(cy), Math.round(size * s), alpha);
	}

	// ---- see-through legibility (see the class comment) ----

	/** The mod menu's "Solid Menu Background" is off (the menu and every OriginWindow honour it). */
	public static boolean menuSeeThrough() {
		return com.origin.client.client.mods.Mods.metaNum("menuBgOpacity", 1.0) <= 0.5;
	}

	/** A light colour (cream / white / accent / gold / red): one that relies on a dark panel behind it. */
	private static boolean isLight(int argb) {
		int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
		return 0.2126f * r + 0.7152f * gr + 0.0722f * b >= 0.4f * 255;
	}

	/**
	 * `argb` lifted for see-through mode: alpha raised on a curve (a^0.3: TEXT
	 * 1.0 stays 1.0, DIM .68 -> .89, MUTE .42 -> .77; still 0 at 0, so fades
	 * keep working and the hierarchy keeps its order), and neutral creams moved
	 * most of the way to white. Coloured text (accent, gold, red) keeps its hue.
	 */
	public static int legible(int argb) {
		float a = ((argb >>> 24) & 0xFF) / 255f;
		int na = Math.round((float) Math.pow(a, 0.3) * 255f);
		int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
		int rgb = argb & 0xFFFFFF;
		if (Math.max(r, Math.max(gr, b)) - Math.min(r, Math.min(gr, b)) < 48) {
			rgb = lerp(0xFF000000 | rgb, 0xFFFFFFFF, 0.6f) & 0xFFFFFF;
		}
		return (na << 24) | rgb;
	}

	/** Is design point (x,y) on an opaque box drawn earlier this frame? */
	private boolean onSolid(float x, float y) {
		for (float[] r : solids) {
			if (x >= r[0] && x < r[2] && y >= r[1] && y < r[3]) {
				return true;
			}
		}
		return false;
	}

	/** Drop-shadow offset in physical px: one design px, never under one screen px. */
	private float dropPx() {
		return Math.max(1f, Math.round(s));
	}

	/**
	 * Soft dark backing behind a glyph run: four stacked pills, each larger
	 * and fainter (core ~48% black), so the edge fades out instead of reading
	 * as a box. Over a dark world it all but vanishes; over snow or sky it is
	 * what keeps the words legible. `unit` scales the spread (the text's height).
	 */
	private void halo(float x0, float y0, float x1, float y1, float unit, float k) {
		float[] grow = {0.15f, 0.4f, 0.7f, 1.0f};
		int[] al = {0x40, 0x2C, 0x1C, 0x0E};
		for (int i = 0; i < grow.length; i++) {
			float gy = unit * grow[i], gx = gy + unit * 0.25f;
			float[] b = {px(x0 - gx), py(y0 - gy), (x1 - x0 + 2 * gx) * s, (y1 - y0 + 2 * gy) * s};
			float r = Math.min(b[2], b[3]) / 2f;
			OriginUi.roundRect(g, b[0], b[1], b[2], b[3], r, fa(mul(al[i] << 24, k)), 0, 0f);
		}
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
		if (clips.isEmpty()) {
			RenderSystem.disableScissor();
		} else {
			applyClip(clips.peek());
		}
	}

	// GL scissor directly in window pixels: Gfx.enableScissor takes GUI px and
	// doesn't follow this kit's physical-pixel pose. Every Gfx fill/blit/text call
	// draws immediately (GuiComponent statics, immediate Font.draw), and the custom
	// shaders draw immediately, so the scissor covers exactly what's drawn inside.
	private void applyClip(float[] c) {
		int winH = Minecraft.getInstance().getWindow().getHeight();
		int x0 = Math.round(px(c[0])), y0 = Math.round(py(c[1]));
		int x1 = Math.round(px(c[2])), y1 = Math.round(py(c[3]));
		RenderSystem.enableScissor(x0, winH - y1, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
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
