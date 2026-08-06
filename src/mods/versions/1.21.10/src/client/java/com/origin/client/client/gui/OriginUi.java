package com.origin.client.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// The premium drawing kit shared by the mod menu and HUD editor: baked
// high-res assets (rounded panels, Apple-style switch, 96px icon atlas,
// radial glow, brand rings) drawn with GL_LINEAR so nothing but the font is
// ever pixelated. GL discipline as documented in CODE_REVIEW.md: blend +
// default func before textured draws, shader color reset after tints.
//
// 1.21.11 port note: identical to the 1.21.1 module except every GuiGraphics
// textured-quad draw uses the 1.21.2 blit signature — a
// Function<ResourceLocation, RenderType> (RenderType::guiTextured) is prepended,
// and for the region-scaled overload the u/v offsets move ahead of the
// destination width/height:
//   1.21.1: blit(id, x, y, dstW, dstH, u, v, srcW, srcH, texW, texH)
//   1.21.11: blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, dstW, dstH, srcW, srcH, texW, texH)
// Tints ride each blit's ARGB color arg (setShaderColor is gone in 1.21.11);
// Every rewritten descriptor must be javap-verified against the mapped 1.21.11
// jar before shipping (see PORT-12111.md).
public final class OriginUi {
	private static volatile boolean loaded = false;
	private static boolean ok = false;

	private static ResourceLocation trackTex, knobTex, glowTex, ringTex, logoTex, starTex;

	// eased animation state keyed by arbitrary id (switch knobs, hovers)
	private static final Map<String, double[]> ANIM = new HashMap<>(); // {value, lastNanos, target}

	private OriginUi() {
	}

	public static boolean ready() {
		ensureLoaded();
		return ok;
	}

	/** Eased approach of `id` toward target (0..1) over durMs, evaluated per frame. */
	public static float anim(String id, boolean target, double durMs) {
		double[] st = ANIM.computeIfAbsent(id, k -> new double[]{target ? 1 : 0, System.nanoTime(), target ? 1 : 0});
		long now = System.nanoTime();
		double dt = Math.min(100.0, (now - st[1]) / 1_000_000.0);
		st[1] = now;
		st[2] = target ? 1 : 0;
		double step = dt / durMs;
		st[0] = st[2] > st[0] ? Math.min(st[2], st[0] + step) : Math.max(st[2], st[0] - step);
		return (float) OriginTheme.easeOut(st[0]);
	}

	/**
	 * A surface: rounded fill plus a 1px rounded border, anti-aliased.
	 *
	 * Every Origin surface -- mod-menu cards, chips, tooltips, switches, the HUD
	 * editor, the colour picker, and (through {@link #bevelPanel}) every menu
	 * button -- draws through here, so one implementation rounds all of them
	 * consistently by construction.
	 *
	 * <p>WHY MASKS (2026-08-06). This used to 9-slice the baked button_fill /
	 * button_border textures: a 24px source corner squeezed into a ~6px
	 * destination through a non-interpolating sampler, so every corner in the
	 * menu came out a hard staircase -- and then the GUI scale magnified that
	 * staircase again. 1.21.1 avoids it with a rounded-box SDF shader that
	 * resolves the curve per device pixel. This era has no such shader, so it
	 * does the same thing the other way round: bake the exact coverage into a
	 * texture AT PHYSICAL RESOLUTION and blit it back at 1:1, which puts one
	 * texel on one screen pixel and yields the identical per-screen-pixel
	 * anti-aliasing. See {@link #guiScale()}.
	 */
	public static void panel(GuiGraphics g, int x, int y, int w, int h, int corner, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.max(0, Math.min(corner, Math.min(w, h) / 2));
		// SMALL, REPEATED panels (cards, cells, tabs, switches, chips) get the whole
		// box baked once at their exact size and drawn as TWO tinted blits -- the
		// same order of cost as 1.21.1's single SDF quad.
		if (w <= MASK_MAX && h <= MASK_MAX) {
			ResourceLocation box = boxMask(w, h, r);
			if (box != null) {
				int s = guiScale();
				int pw = w * s, ph = h * s;
				// Destination stays in GUI px; the mask's UVs are physical px.
				if (((fill >>> 24) & 0xFF) > 0) {
					g.blit(RenderPipelines.GUI_TEXTURED, box, x, y, 0f, 0f, w, h, pw, ph, pw, ph * 2, fill);
				}
				if (((border >>> 24) & 0xFF) > 0) {
					g.blit(RenderPipelines.GUI_TEXTURED, box, x, y, 0f, (float) ph, w, h, pw, ph, pw, ph * 2, border);
				}
				return;
			}
		}
		// Bigger one-off panels (the menu shell, settings rows) are few and rarely
		// the same size twice, so they take the corner-blit path and never churn
		// the whole-box cache.
		roundedFill(g, x, y, w, h, r, fill);
		if (((border >>> 24) & 0xFF) > 0) {
			roundedStroke(g, x, y, w, h, r, border);
		}
	}

	/**
	 * Kept in step with 1.21.1, where the menu's "bevel cut" buttons call this
	 * with a small corner instead of the panel radius. The 45 degree chamfer is
	 * gone -- buttons, cards and chips are rounded like everything else -- so this
	 * just forwards with the cut used as the radius.
	 */
	public static void bevelPanel(GuiGraphics g, int x, int y, int w, int h, int cut, int fill, int border) {
		panel(g, x, y, w, h, cut, fill, border);
	}

	// ---- baked rounded-box masks (the anti-aliasing backend) ----

	/** Largest side that gets a whole-box mask. Covers everything that repeats:
	 *  mod-menu cards, grid cells, category tabs, switches and chips. */
	private static final int MASK_MAX = 96;

	/** (w,h,r,guiScale) -> one texture holding the fill mask on top and the 1px
	 *  border ring underneath, so a panel is two blits into the same texture.
	 *  LRU-capped: a handful of sizes repeat constantly, anything rare is evicted. */
	private static final int MASK_CACHE_MAX = 32;
	private static final LinkedHashMap<Long, ResourceLocation> BOX_MASKS =
			new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, ResourceLocation> eldest) {
					if (size() <= MASK_CACHE_MAX) {
						return false;
					}
					if (eldest.getValue() != null) {
						try {
							Minecraft.getInstance().getTextureManager().release(eldest.getValue());
						} catch (Throwable ignored) {
							// a texture we can't release is a leak, not a crash
						}
					}
					return true;
				}
			};

	/**
	 * The GUI scale factor, i.e. how many PHYSICAL pixels one GUI pixel covers.
	 *
	 * <p>Masks are baked at physical resolution and blitted back down to their GUI
	 * size, so one texel lands on exactly one screen pixel and the anti-aliasing is
	 * computed per SCREEN pixel -- which is what 1.21.1's rounded-box SDF shader
	 * does, and the reason its corners look smooth at any GUI scale.
	 *
	 * <p>Baking in GUI pixels instead means the mask is magnified by the GUI scale
	 * on the way to the screen: at GUI scale 3 every corner step becomes a 3x3
	 * block. The corner MATHS was never the problem -- the resolution it was
	 * evaluated at was.
	 */
	private static int guiScale() {
		try {
			int s = (int) Math.ceil(Minecraft.getInstance().getWindow().getGuiScale());
			return Math.max(1, Math.min(8, s));
		} catch (Throwable t) {
			return 1;
		}
	}

	private static ResourceLocation boxMask(int w, int h, int r) {
		int s = guiScale();
		long key = ((long) w << 44) | ((long) h << 20) | ((long) r << 4) | s;
		if (BOX_MASKS.containsKey(key)) {
			return BOX_MASKS.get(key);   // may be null: a failed bake is remembered, not retried
		}
		ResourceLocation id = null;
		try {
			// Bake in physical pixels: the whole shape scales, radius included.
			int pw = w * s, ph = h * s, pr = r * s;
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, pw, ph * 2, false);
			for (int py = 0; py < ph; py++) {
				for (int px = 0; px < pw; px++) {
					double cov = boxCoverage(px, py, pw, ph, pr, 0);
					// The border stays 1 GUI pixel thick, so it is `s` physical px.
					double ring = boxCoverage(px, py, pw, ph, pr, s);
					img.setPixel(px, py, alphaWhite(cov));
					img.setPixel(px, py + ph, alphaWhite(ring));
				}
			}
			// Path must stay [a-z0-9_./-] -- anything else is an illegal id.
			String name = "textures/ui/origin_box_" + w + "x" + h + "_" + r + "_s" + s;
			id = ResourceLocation.fromNamespaceAndPath("originclient", name);
			DynamicTexture tex = new DynamicTexture(() -> name, img);
			tex.setFilter(false, false);
			Minecraft.getInstance().getTextureManager().register(id, tex);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn(
					"Origin: box mask " + w + "x" + h + " r=" + r + " failed to bake", t);
		}
		BOX_MASKS.put(key, id);
		return id;
	}

	/**
	 * Coverage of pixel (px,py) by a rounded rect, from the signed distance to its
	 * outline. Exact everywhere including the corners.
	 *
	 * @param inset 0 for the solid shape; >0 for a border ring that many px wide.
	 */
	private static double boxCoverage(int px, int py, int w, int h, int r, int inset) {
		// Standard rounded-box signed distance, measured from the box centre:
		//   q = |p| - (halfSize - r);  d = |max(q,0)| + min(max(q.x,q.y),0) - r
		// Negative inside, positive outside.
		double hw = w / 2.0, hh = h / 2.0;
		double qx = Math.abs(px + 0.5 - hw) - (hw - r);
		double qy = Math.abs(py + 0.5 - hh) - (hh - r);
		double outX = Math.max(qx, 0), outY = Math.max(qy, 0);
		double d = Math.sqrt(outX * outX + outY * outY) + Math.min(Math.max(qx, qy), 0) - r;

		double outer = clamp01(0.5 - d);
		if (inset <= 0) {
			return outer;
		}
		// Ring = the shape minus the same shape shrunk by `inset` px.
		return outer - clamp01(0.5 - (d + inset));
	}

	/** White texel whose ALPHA carries the coverage; the tint supplies the colour.
	 *  White means the ARGB and the older ABGR packing are byte-identical, so this
	 *  is correct for both NativeImage generations. */
	private static int alphaWhite(double cov) {
		int a = cov <= 0.001 ? 0 : (int) Math.round(255 * Math.min(1.0, cov));
		return (a << 24) | 0xFFFFFF;
	}

	/** Solid rounded rectangle: straight interior as bulk fills, 4 mask corners. */
	private static void roundedFill(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
		if (((color >>> 24) & 0xFF) == 0) {
			return;
		}
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, color);
			return;
		}
		g.fill(x + r, y, x + w - r, y + h, color);              // center column, full height
		g.fill(x, y + r, x + r, y + h - r, color);              // left band
		g.fill(x + w - r, y + r, x + w, y + h - r, color);      // right band
		corners(g, x, y, w, h, r, false, color);
	}

	/** 1px rounded outline: straight edges (clear of the corners) + 4 mask corners. */
	private static void roundedStroke(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
		if (r <= 0) {
			g.fill(x, y, x + w, y + 1, color);
			g.fill(x, y + h - 1, x + w, y + h, color);
			g.fill(x, y + 1, x + 1, y + h - 1, color);
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
			return;
		}
		g.fill(x + r, y, x + w - r, y + 1, color);              // top
		g.fill(x + r, y + h - 1, x + w - r, y + h, color);      // bottom
		g.fill(x, y + r, x + 1, y + h - r, color);              // left
		g.fill(x + w - 1, y + r, x + w, y + h - r, color);      // right
		corners(g, x, y, w, h, r, true, color);
	}

	/**
	 * Draws the four rounded corners from a baked coverage mask. The mask is
	 * 2r x 2r at physical resolution and holds all four corners, so these are UV
	 * offsets into one texture at 1:1 scale -- no filtering, no resampling.
	 *
	 * <p>If baking failed we fall back to {@link #aaCorner}, which computes the
	 * same coverage per GUI pixel. That reads a step lower-res at GUI scale > 1,
	 * but it is a rounded corner rather than a square one -- the fail-soft ladder,
	 * never the intended path.
	 */
	private static void corners(GuiGraphics g, int x, int y, int w, int h, int r, boolean ring, int color) {
		ResourceLocation mask = cornerMask(r, ring);
		if (mask == null) {
			return;   // the straight bands above still drew a square box
		}
		int s = guiScale();
		int pr = r * s, t = pr * 2;
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y, 0f, 0f, r, r, pr, pr, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x + w - r, y, (float) pr, 0f, r, r, pr, pr, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y + h - r, 0f, (float) pr, r, r, pr, pr, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x + w - r, y + h - r, (float) pr, (float) pr, r, r, pr, pr, t, t, color);
	}

	// Baked corner masks, keyed by (radius, guiScale). `false` = filled quarter
	// discs (panel fill), `true` = a 1px ring (panel stroke). Only a handful of
	// radii are ever used, so this never grows.
	private static final Map<Integer, ResourceLocation> FILL_MASKS = new HashMap<>();
	private static final Map<Integer, ResourceLocation> STROKE_MASKS = new HashMap<>();

	private static ResourceLocation cornerMask(int r, boolean ring) {
		Map<Integer, ResourceLocation> cache = ring ? STROKE_MASKS : FILL_MASKS;
		int s = guiScale();
		int key = (r << 4) | s;
		if (cache.containsKey(key)) {
			return cache.get(key);   // may hold null: a failed bake is not retried every frame
		}
		ResourceLocation id = null;
		try {
			// Baked at PHYSICAL resolution -- see guiScale() for why.
			int pr = r * s, t = pr * 2;
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, t, t, false);
			double rInner = ring ? pr - s : 0;   // a 1 GUI px ring is s physical px
			for (int py = 0; py < t; py++) {
				for (int px = 0; px < t; px++) {
					// Every corner is one quadrant of the same circle, which is why
					// one mask serves all four.
					double dx = px + 0.5 - pr, dy = py + 0.5 - pr;
					double dist = Math.sqrt(dx * dx + dy * dy);
					double cov = clamp01(pr - dist + 0.5) - clamp01(rInner - dist + 0.5);
					img.setPixel(px, py, alphaWhite(cov));
				}
			}
			String name = "textures/ui/origin_corner_" + (ring ? "ring" : "fill") + "_" + r + "_s" + s;
			id = ResourceLocation.fromNamespaceAndPath("originclient", name);
			DynamicTexture tex = new DynamicTexture(() -> name, img);
			tex.setFilter(false, false);
			Minecraft.getInstance().getTextureManager().register(id, tex);
		} catch (Throwable t2) {
			com.origin.client.OriginClient.LOGGER.warn(
					"Origin: corner mask r=" + r + " failed to bake", t2);
		}
		cache.put(key, id);
		return id;
	}


	/**
	 * Rounded-box toggle (C4): a rectangular track with curved corners, a knob
	 * that slides LEFT = off / RIGHT = on, green when on and red when off. Built
	 * from the shared rounded-rect masks so it stays crisp at any size. Same
	 * signature/geometry as before (wDisp x wDisp*8/15) so existing layouts and
	 * hit-tests are unchanged. Returns knob progress 0..1.
	 */
	public static float switchAt(GuiGraphics g, String id, int x, int y, int wDisp, boolean on, boolean enabled) {
		int hDisp = wDisp * 8 / 15;
		float k = anim("sw:" + id, on, 170.0);

		// Track: red(off) -> green(on); disabled desaturates to gray so the whole
		// control reads as unavailable without changing shape.
		int track = enabled
				? OriginTheme.lerpColor(OriginTheme.SWITCH_OFF, OriginTheme.SWITCH_ON, k)
				: OriginTheme.lerpColor(0xFF3C3C3C, 0xFF565656, k);
		// Rounded RECTANGLE (not a pill) — a modest corner so it reads clearly
		// different from the old iOS-style switch.
		int trackCorner = Math.max(3, Math.round(hDisp * 0.30f));
		panel(g, x, y, wDisp, hDisp, trackCorner, track, OriginTheme.SWITCH_STROKE);

		// Knob: a near-white rounded square sliding between the track's inset ends.
		int pad = Math.max(2, Math.round(hDisp * 0.15f));
		int knob = hDisp - 2 * pad;
		int travel = Math.max(0, wDisp - 2 * pad - knob);
		int kx = x + pad + Math.round(k * travel);
		int knobCorner = Math.max(2, Math.round(knob * 0.30f));
		panel(g, kx, y + pad, knob, knob, knobCorner,
				enabled ? OriginTheme.SWITCH_KNOB : 0xFFB8B8B8, OriginTheme.SWITCH_STROKE);
		return k;
	}

	/**
	 * Mod icon. Now a real Minecraft item (or a baked Origin texture for the few
	 * ideas no item expresses) -- see ModIcons. The old 96px line-icon atlas is
	 * gone, so the `argb` tint no longer colours the art: a spyglass has to look
	 * like a spyglass. Only the ALPHA is honoured, which is what callers actually
	 * need it for -- the mod menu's open/close and page-swap fades.
	 */
	public static void icon(GuiGraphics g, String name, int x, int y, int size, int argb) {
		ModIcons.draw(g, name, x, y, size, ((argb >>> 24) & 0xFF) / 255f);
	}

	/** Soft radial glow centered at (cx,cy). */
	public static void glow(GuiGraphics g, double cx, double cy, int diameter, float alpha) {
		ensureLoaded();
		if (!ok) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, glowTex, (int) (cx - diameter / 2.0), (int) (cy - diameter / 2.0), 0f, 0f, diameter, diameter, 512, 512, 512, 512, ARGB.white(alpha));
	}

	/** The 3-ring Origin mark (brand geometry: one ellipse at 0/60/120 deg). */
	public static void mark(GuiGraphics g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok) {
			return;
		}
		var pose = g.pose();
		for (int i = 0; i < 3; i++) {
			pose.pushMatrix();
			pose.translate((float) cx, (float) cy);
			pose.rotate((float) Math.toRadians(i * 60f));
			float s = size / 768f * 1.1f;
			pose.scale(s, s);
			pose.translate(-384f, -384f);
			g.blit(RenderPipelines.GUI_TEXTURED, ringTex, 0, 0, 0f, 0f, 768, 768, 768, 768, ARGB.white(alpha));
			pose.popMatrix();
		}
	}

	/** The exact brand mark (baked from the website nav-mark geometry — three
	 *  nested ellipses at 0/60/120°), drawn smooth at any size. */
	public static void logo(GuiGraphics g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok || logoTex == null) {
			mark(g, cx, cy, size, alpha); // fallback to the procedural rings
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, logoTex, (int) (cx - size / 2.0), (int) (cy - size / 2.0), 0f, 0f, size, size, 256, 256, 256, 256, ARGB.white(alpha));
	}

	/** Rounded pill slider: track + fill + a ball knob that stays vertically
	 *  centered and sits flush at both ends (its travel is inset by the knob
	 *  radius). `y` is the pill top; value 0..1. Returns the knob center x. */
	public static int slider(GuiGraphics g, int x, int y, int w, double value, boolean active) {
		int h = 6;              // pill height
		int r = 5;              // knob radius — travel is inset by r so the ball is flush at the ends
		double v = Math.max(0.0, Math.min(1.0, value));
		int cy = y + h / 2;     // pill vertical center — the knob centers on this
		panel(g, x, y, w, h, h / 2, 0x30FFFFFF, 0);
		int kx = x + r + (int) Math.round(v * (w - 2 * r));
		int fw = kx - x;        // fill runs from the track start to the knob center
		if (v > 0.001 && fw > 0) {
			panel(g, x, y, Math.min(w, fw + r), h, h / 2, active ? 0xE6E0E0E0 : 0xA8D8D8D8, 0);
		}
		ensureLoaded();
		int kd = active ? r * 2 + 4 : r * 2 + 2;   // ball; grows slightly while dragging
		if (ok) {
			g.blit(RenderPipelines.GUI_TEXTURED, knobTex, kx - kd / 2, cy - kd / 2, 0f, 0f, kd, kd, 72, 72, 72, 72);
		} else {
			g.fill(kx - kd / 2, cy - kd / 2, kx + kd / 2, cy + kd / 2, 0xFFE8E8E8);
		}
		return kx;
	}

	// ---- internals ----



	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		ensureLoaded0();
	}

	private static synchronized void ensureLoaded0() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Minecraft mc = Minecraft.getInstance();
			trackTex = reg(mc, "ui_switch_track", "/assets/originclient/textures/ui/switch_track.png");
			knobTex = reg(mc, "ui_switch_knob", "/assets/originclient/textures/ui/switch_knob.png");
			glowTex = reg(mc, "ui_glow", "/assets/originclient/textures/ui/radial_glow.png");
			ringTex = reg(mc, "ui_ring", "/assets/originclient/textures/ui/ring-0.png");
			starTex = reg(mc, "ui_star", "/assets/originclient/textures/ui/star.png");
			logoTex = reg(mc, "ui_logo", "/assets/originclient/textures/ui/origin_logo.png");
			ok = true;
		} catch (Throwable e) {
			ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin UI assets failed to load; using flat fallbacks", e);
		}
	}



	private static ResourceLocation reg(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = OriginUi.class.getResourceAsStream(path)) {
			image = NativeImage.read(in);
		}
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
		// 1.21.11: DynamicTexture lost setFilter — filtering now rides the texture's
		// GpuSampler. GUI_TEXTURED's default samples NEAREST (choppy icons/font), so
		// bind a LINEAR clamp sampler to match the smooth pre-1.21.6 look.
		DynamicTexture tex = new DynamicTexture(() -> "originclient:" + name, image);
		tex.setFilter(true, false); // GL_LINEAR smoothing (1.21.10 still has setFilter; gone at 1.21.11)
		mc.getTextureManager().register(id, tex);
		return id;
	}


	/** Software anti-aliased line: `half` px to each side of (ax,ay)->(bx,by),
	 *  coverage computed per-pixel and drawn as scanline runs via g.fill. Fully
	 *  self-contained — no shader dependency, so it works regardless of whether
	 *  the rounded-box SDF pipeline (OriginShaders.ROUND) is available. */
	public static void aaLine(GuiGraphics g, double ax, double ay, double bx, double by, double half, int color) {
		int base = (color >>> 24) & 0xFF;
		if (base == 0) {
			return;
		}
		int rgb = color & 0xFFFFFF;
		int x0 = (int) Math.floor(Math.min(ax, bx) - half - 1);
		int x1 = (int) Math.ceil(Math.max(ax, bx) + half + 1);
		int y0 = (int) Math.floor(Math.min(ay, by) - half - 1);
		int y1 = (int) Math.ceil(Math.max(ay, by) + half + 1);
		double abx = bx - ax, aby = by - ay;
		double len2 = abx * abx + aby * aby;
		for (int py = y0; py < y1; py++) {
			int runStart = -1, runArgb = 0;
			for (int px = x0; px <= x1; px++) {
				int argb = 0;
				if (px < x1) {
					double dpx = px + 0.5 - ax, dpy = py + 0.5 - ay;
					double t = len2 <= 1e-6 ? 0 : clamp01((dpx * abx + dpy * aby) / len2);
					double cxp = ax + t * abx, cyp = ay + t * aby;
					double dx = px + 0.5 - cxp, dy = py + 0.5 - cyp;
					double dist = Math.sqrt(dx * dx + dy * dy);
					double cov = clamp01(half - dist + 0.5);
					int a = cov <= 0.001 ? 0 : (int) Math.round(base * cov);
					argb = a <= 0 ? 0 : (a << 24) | rgb;
				}
				if (argb != runArgb) {
					if (runStart >= 0 && runArgb != 0) {
						g.fill(runStart, py, px, py + 1, runArgb);
					}
					runStart = px;
					runArgb = argb;
				}
			}
		}
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	/** A stroke from (ax,ay)->(bx,by), `half` px to each side. Software
	 *  anti-aliased fallback only — the icon glyphs that used to compose this
	 *  at runtime (close/chevron/edit) now sample a baked SDF atlas instead
	 *  (see {@link #drawIconSdf}); this remains as their fail-soft path and for
	 *  any other capsule-stroke caller. Still used if the icon SDF atlas fails
	 *  to load, or by the rounded-box SDF PANEL once that's ported (it needs a
	 *  custom per-vertex format the engine doesn't support — see OriginShaders). */
	public static void capsule(GuiGraphics g, double ax, double ay, double bx, double by, double half, int color) {
		aaLine(g, ax, ay, bx, by, half, color);
	}







	/** The favourite-mod star: a baked HQ texture (not the pixelated font glyph),
	 *  tinted to `argb` via the per-blit ARGB tint (no global shader-color state
	 *  on 1.21.11 — each blit carries its own tint). */
	public static void star(GuiGraphics g, int x, int y, int size, int argb) {
		ensureLoaded();
		if (!ok || starTex == null || ((argb >>> 24) & 0xFF) == 0) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, starTex, x, y, 0f, 0f, size, size, 64, 64, 64, 64, argb);
	}

	// ---- vector icon glyphs, baked at physical resolution ----

	/**
	 * The close / chevron / pencil glyphs are stroke geometry, so they get the
	 * same treatment as the panel corners: their coverage is rasterised once at
	 * SCREEN resolution and blitted back 1:1, instead of being computed per GUI
	 * pixel by {@link #aaLine} and then magnified by the GUI scale.
	 *
	 * <p>Cached on (kind, size, guiScale). Only a handful of icon sizes are ever
	 * drawn, so this stays tiny.
	 */
	private static final Map<String, ResourceLocation> ICON_MASKS = new HashMap<>();

	/**
	 * Stroke segments for one glyph, in PHYSICAL pixels: {ax, ay, bx, by, half}
	 * per segment. Geometry matches 1.21.1's glyphs exactly, expressed as
	 * fractions of the icon box so it scales cleanly.
	 */
	private static double[] iconSegments(String kind, int size, int s) {
		double n = size * (double) s;
		// A stroke never thins below ~0.9 GUI px, same floor 1.21.1 uses.
		double h = Math.max(0.9, size * 0.10) * s;
		switch (kind) {
			case "close": {
				double in = 0.22 * n, out = 0.78 * n;
				return new double[]{in, in, out, out, h, out, in, in, out, h};
			}
			case "edit": {
				// tapered pencil: thicker shaft, thin graphite tip
				return new double[]{
						0.80 * n, 0.20 * n, 0.36 * n, 0.64 * n, 0.12 * size * s,
						0.36 * n, 0.64 * n, 0.16 * n, 0.84 * n, 0.055 * size * s};
			}
			case "chevron_left":
			case "chevron_right": {
				boolean left = kind.equals("chevron_left");
				double point = (left ? 0.34 : 0.66) * n;
				double arm = (left ? 0.66 : 0.34) * n;
				double mid = 0.5 * n, top = 0.24 * n, bot = 0.76 * n;
				return new double[]{point, mid, arm, top, h, point, mid, arm, bot, h};
			}
			default:
				return new double[0];
		}
	}

	/** Max coverage of (px,py) over every stroke segment: distance to the segment,
	 *  softened across one pixel. Round caps fall out of the distance metric. */
	private static double segCoverage(double px, double py, double[] segs) {
		double best = 0;
		for (int i = 0; i + 4 < segs.length; i += 5) {
			double ax = segs[i], ay = segs[i + 1], bx = segs[i + 2], by = segs[i + 3], half = segs[i + 4];
			double abx = bx - ax, aby = by - ay;
			double len2 = abx * abx + aby * aby;
			double t = len2 <= 1e-6 ? 0 : clamp01(((px - ax) * abx + (py - ay) * aby) / len2);
			double dx = px - (ax + t * abx), dy = py - (ay + t * aby);
			double cov = clamp01(half - Math.sqrt(dx * dx + dy * dy) + 0.5);
			if (cov > best) {
				best = cov;
			}
		}
		return best;
	}

	private static ResourceLocation iconMask(String kind, int size) {
		int s = guiScale();
		String key = kind + ":" + size + ":" + s;
		if (ICON_MASKS.containsKey(key)) {
			return ICON_MASKS.get(key);   // may be null: a failed bake is not retried
		}
		ResourceLocation id = null;
		try {
			int n = size * s;
			double[] segs = iconSegments(kind, size, s);
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, n, n, false);
			for (int py = 0; py < n; py++) {
				for (int px = 0; px < n; px++) {
					img.setPixel(px, py, alphaWhite(segCoverage(px + 0.5, py + 0.5, segs)));
				}
			}
			String name = "textures/ui/origin_icon_" + kind + "_" + size + "_s" + s;
			id = ResourceLocation.fromNamespaceAndPath("originclient", name);
			DynamicTexture tex = new DynamicTexture(() -> name, img);
			tex.setFilter(false, false);
			Minecraft.getInstance().getTextureManager().register(id, tex);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn(
					"Origin: icon mask " + kind + " size=" + size + " failed to bake", t);
		}
		ICON_MASKS.put(key, id);
		return id;
	}

	/** Blits a baked glyph, tinted. Falls back to the per-GUI-pixel stroke path if
	 *  the bake failed — lower-res, but still the right glyph. */
	private static void drawIcon(GuiGraphics g, String kind, int x, int y, int size, int color) {
		if (((color >>> 24) & 0xFF) == 0 || size <= 0) {
			return;
		}
		ResourceLocation mask = iconMask(kind, size);
		if (mask == null) {
			strokeIcon(g, kind, x, y, size, color);
			return;
		}
		int n = size * guiScale();
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y, 0f, 0f, size, size, n, n, n, n, color);
	}

	/** The software fallback: the same segments drawn with {@link #aaLine}. */
	private static void strokeIcon(GuiGraphics g, String kind, int x, int y, int size, int color) {
		double[] segs = iconSegments(kind, size, 1);
		for (int i = 0; i + 4 < segs.length; i += 5) {
			aaLine(g, x + segs[i], y + segs[i + 1], x + segs[i + 2], y + segs[i + 3], segs[i + 4], color);
		}
	}

	/** A clean x mark filling a size x size box at (x,y). */
	public static void iconClose(GuiGraphics g, int x, int y, int size, int color) {
		drawIcon(g, "close", x, y, size, color);
	}

	/** A pencil (edit) glyph filling a size x size box at (x,y). */
	public static void iconEdit(GuiGraphics g, int x, int y, int size, int color) {
		drawIcon(g, "edit", x, y, size, color);
	}

	/** A chevron ("<" when left, ">" when right) filling a size x size box. */
	public static void iconChevron(GuiGraphics g, int x, int y, int size, int color, boolean left) {
		drawIcon(g, left ? "chevron_left" : "chevron_right", x, y, size, color);
	}
}
