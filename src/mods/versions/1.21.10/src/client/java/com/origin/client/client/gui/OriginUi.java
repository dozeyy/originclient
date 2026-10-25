package com.origin.client.client.gui;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ARGB;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

// The premium drawing kit shared by the mod menu and HUD editor: baked
// high-res assets (rounded panels, Apple-style switch, 96px icon atlas,
// radial glow) drawn with GL_LINEAR so nothing but the font is
// ever pixelated. GL discipline as documented in CODE_REVIEW.md: blend +
// default func before textured draws, shader color reset after tints.
//
// 1.21.10 port note: the 1.21.1 design (Workbench radii via craftedRadius,
// emerald/neutral block switch, full-track slider, theme-tinted mod icons, no
// cursor glow) on this era's drawing calls: every textured quad uses the
// RenderPipelines blit signature, with the u/v offsets ahead of the destination
// width/height:
//   1.21.1:  blit(id, x, y, dstW, dstH, u, v, srcW, srcH, texW, texH)
//   1.21.10: blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, dstW, dstH, srcW, srcH, texW, texH)
// Tints ride each blit's ARGB color arg (setShaderColor is gone since 1.21.6).
public final class OriginUi {
	private static volatile boolean loaded = false;
	private static boolean ok = false;

	/** The one reference to the official logo (a classpath path). Everything that reads the logo uses this. */
	public static final String LOGO_PATH = "/assets/originclient/textures/ui/origin_logo.png";
	private static ResourceLocation trackTex, knobTex, glowTex, logoTex, starTex;

	// Theme-matched toggles: familiar state language without borrowing the
	// operating system's exact colors.
	private static final int IOS_ON_DISABLED = 0xFF3E5147;
	private static final int IOS_OFF_DISABLED = 0xFF242A26;

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

	/** Shared immediate hover response for every hand-rendered Origin control. */
	public static float hover(String id, boolean target) {
		return anim("hover:" + id, target,
				target ? OriginTheme.HOVER_IN_MS : OriginTheme.HOVER_OUT_MS);
	}

	/**
	 * The single block-surface primitive for the whole client: fill + 1px hairline
	 * border, corners anti-aliased per SCREEN pixel. Older screens may still
	 * request pill-sized corner values, so every request is normalized to the
	 * Workbench 1/2/3 px scale from the control's short edge (craftedRadius) —
	 * a missed call site can never bring circular buttons back.
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
		int r = craftedRadius(w, h, corner);
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

	private static int craftedRadius(int w, int h, int requested) {
		if (requested <= 0) {
			return 0;
		}
		int shortEdge = Math.min(w, h);
		int limit = shortEdge <= 24 ? OriginTheme.RADIUS_SM
				: shortEdge <= 56 ? OriginTheme.RADIUS_MD
				: OriginTheme.RADIUS_LG;
		return Math.max(0, Math.min(Math.min(requested, limit), shortEdge / 2));
	}

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


	// ---- exact-geometry primitives for the 2026-10 menu redesign (OriginDeck) ----

	/**
	 * A rounded rect at EXACT geometry and radius -- no Workbench radius
	 * normalisation. Used by OriginDeck (the 2026-10 menu redesign), which draws
	 * in PHYSICAL pixels (its pose is scaled by 1/guiScale) and sizes every
	 * corner itself.
	 *
	 * <p>PER-VERSION DELTA (1.21.10): 1.21.1 draws this as one quad through the
	 * rounded-box SDF shader. This era has no per-draw uniform channel to carry a
	 * rect/radius into a shader (see OriginShaders), so -- like {@link #panel} --
	 * the same per-screen-pixel coverage is BAKED: one corner mask per radius
	 * (keyed in 1/8 px), blitted 1:1 at the four corners (the caller's units ARE
	 * screen pixels, so one texel lands on one pixel), with the straight runs as
	 * plain fills. Everything goes through g.fill / g.blit, i.e. the batched
	 * GUI's own render states, so layering against text and icons follows
	 * submission order like every other Origin surface on this era.
	 *
	 * <p>Geometry is rounded to whole pixels (OriginDeck already snaps boxes;
	 * circles lose only their sub-pixel offset). `aaPad` is a shader notion
	 * (quad overdraw for the AA ramp) and is unused here: the mask carries the
	 * whole ramp inside the box. As in 1.21.1's shader, a visible `border`
	 * REPLACES the fill in a 1px ring (the fill is drawn inset by 1px with the
	 * radius shrunk by 1px -- the exact inner edge of the ring) rather than
	 * being painted over it.
	 */
	public static void roundRect(GuiGraphics g, float x, float y, float w, float h, float r, int fill, int border, float aaPad) {
		int x0 = Math.round(x), y0 = Math.round(y);
		int iw = Math.round(x + w) - x0, ih = Math.round(y + h) - y0;
		if (iw <= 0 || ih <= 0) {
			return;
		}
		float rr = Math.max(0f, Math.min(r, Math.min(iw, ih) / 2f));
		boolean hasBorder = ((border >>> 24) & 0xFF) > 0;
		if (hasBorder && iw > 2 && ih > 2) {
			deckShape(g, x0 + 1, y0 + 1, iw - 2, ih - 2, Math.max(0f, rr - 1f), false, fill);
			deckShape(g, x0, y0, iw, ih, rr, true, border);
		} else {
			deckShape(g, x0, y0, iw, ih, rr, false, hasBorder ? border : fill);
		}
	}

	/** A soft radial glow (the baked radial_glow texture, 512 px) centred on (cx,cy). */
	public static void softGlow(GuiGraphics g, float cx, float cy, float size, int argb) {
		ensureLoaded();
		if (!ok || glowTex == null || ((argb >>> 24) & 0xFF) == 0 || size <= 0) {
			return;
		}
		// The tint rides the blit's ARGB (no setShaderColor on this era); the
		// texture is registered LINEAR by reg(), so the scaled glow stays smooth.
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx - size / 2f, cy - size / 2f);
		pose.scale(size / 512f, size / 512f);
		g.blit(RenderPipelines.GUI_TEXTURED, glowTex, 0, 0, 0f, 0f, 512, 512, 512, 512, 512, 512, argb);
		pose.popMatrix();
	}

	/** Fill (ring=false) or 1px outline (ring=true) of a rounded box, whole-pixel
	 *  geometry in the CALLER's units (physical px under OriginDeck's pose). */
	private static void deckShape(GuiGraphics g, int x, int y, int w, int h, float r, boolean ring, int color) {
		if (((color >>> 24) & 0xFF) == 0 || w <= 0 || h <= 0) {
			return;
		}
		int q = Math.round(r * 8f);               // the radius in 1/8 px: the mask key
		ResourceLocation mask = q >= 4 ? deckCorner(q, ring) : null;
		int big = (q + 7) / 8;                    // ceil(r): one quadrant of the mask
		// A corner cell never exceeds half the box; with r <= min(w,h)/2 the part
		// cut off by an odd side is the arc's flat extreme (coverage ~1), which the
		// one-pixel middle band below draws as solid.
		int cw = mask == null ? 0 : Math.min(big, w / 2);
		int ch = mask == null ? 0 : Math.min(big, h / 2);
		if (ring) {
			if (mask == null) {
				deckFill(g, x, y, x + w, y + 1, color);
				deckFill(g, x, y + h - 1, x + w, y + h, color);
				deckFill(g, x, y + 1, x + 1, y + h - 1, color);
				deckFill(g, x + w - 1, y + 1, x + w, y + h - 1, color);
				return;
			}
			deckFill(g, x + cw, y, x + w - cw, y + 1, color);              // top
			deckFill(g, x + cw, y + h - 1, x + w - cw, y + h, color);      // bottom
			deckFill(g, x, y + ch, x + 1, y + h - ch, color);              // left
			deckFill(g, x + w - 1, y + ch, x + w, y + h - ch, color);      // right
		} else {
			deckFill(g, x + cw, y, x + w - cw, y + h, color);              // centre column
			deckFill(g, x, y + ch, x + cw, y + h - ch, color);             // left band
			deckFill(g, x + w - cw, y + ch, x + w, y + h - ch, color);     // right band
		}
		if (cw <= 0 || ch <= 0) {
			return;
		}
		// The mask is 2*big square and holds all four corners (mirrored); crop
		// each blit to the cw x ch cell that touches its own corner.
		int t = big * 2;
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y, 0f, 0f, cw, ch, cw, ch, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x + w - cw, y, (float) (t - cw), 0f, cw, ch, cw, ch, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x, y + h - ch, 0f, (float) (t - ch), cw, ch, cw, ch, t, t, color);
		g.blit(RenderPipelines.GUI_TEXTURED, mask, x + w - cw, y + h - ch, (float) (t - cw), (float) (t - ch), cw, ch, cw, ch, t, t, color);
	}

	private static void deckFill(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
		if (x1 > x0 && y1 > y0) {
			g.fill(x0, y0, x1, y1, color);
		}
	}

	// Corner masks for roundRect, keyed by radius in 1/8 px. Baked at the size
	// they are drawn (no guiScale factor: OriginDeck's units are already screen
	// pixels). Only the menu's handful of radii x the current window scale ever
	// occur, each mask at most a few dozen px square, so this stays tiny.
	private static final Map<Integer, ResourceLocation> DECK_FILL = new HashMap<>();
	private static final Map<Integer, ResourceLocation> DECK_RING = new HashMap<>();

	private static ResourceLocation deckCorner(int q, boolean ring) {
		Map<Integer, ResourceLocation> cache = ring ? DECK_RING : DECK_FILL;
		if (cache.containsKey(q)) {
			return cache.get(q);   // may hold null: a failed bake is remembered, not retried
		}
		ResourceLocation id = null;
		try {
			double r = q / 8.0;
			int big = (q + 7) / 8, t = big * 2;
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, t, t, false);
			for (int py = 0; py < t; py++) {
				for (int px = 0; px < t; px++) {
					// Mirror into the top-left quadrant: box edges at x=0 / y=0, the
					// arc centred at (r, r). Rounded-box signed distance:
					//   q = r - p;  d = |max(q,0)| + min(max(q.x,q.y),0) - r
					int lx = px < big ? px : t - 1 - px;
					int ly = py < big ? py : t - 1 - py;
					double qx = r - (lx + 0.5), qy = r - (ly + 0.5);
					double ox = Math.max(qx, 0), oy = Math.max(qy, 0);
					double d = Math.sqrt(ox * ox + oy * oy) + Math.min(Math.max(qx, qy), 0) - r;
					double cov = clamp01(0.5 - d);
					if (ring) {
						cov -= clamp01(0.5 - (d + 1));   // minus the shape shrunk by 1px
					}
					int a = cov <= 0.001 ? 0 : (int) Math.round(255 * Math.min(1.0, cov));
					img.setPixel(px, py, (a << 24) | 0xFFFFFF);   // white, tinted at blit time
				}
			}
			String name = "origin_deck_" + (ring ? "ring_" : "fill_") + q;
			id = ResourceLocation.fromNamespaceAndPath("originclient", "textures/ui/" + name);
			DynamicTexture tex = new DynamicTexture(() -> name, img);
			tex.setFilter(false, false);   // drawn 1:1: NEAREST is exact
			Minecraft.getInstance().getTextureManager().register(id, tex);
		} catch (Throwable e) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: deck corner mask q=" + q + " failed to bake", e);
		}
		cache.put(q, id);
		return id;
	}

	/**
	 * Apple iOS-style toggle (Will's redesign spec): a fully-rounded pill track
	 * that is GREEN when on and RED when off, with a pure-white circular knob
	 * that slides smoothly LEFT = off / RIGHT = on. The green↔red crossfade and
	 * the knob's travel are both eased on the same 0..1 progress, so flipping it
	 * animates as one motion. Same signature/geometry as before
	 * (wDisp × wDisp*8/15) so every existing layout and hit-test is unchanged.
	 * Built from this era's baked rounded-rect masks so it stays crisp at any size.
	 * Returns knob progress 0..1.
	 */
	public static float switchAt(GuiGraphics g, String id, int x, int y, int wDisp, boolean on, boolean enabled) {
		int hDisp = wDisp * 8 / 15;
		float k = anim("sw:" + id, on, 170.0);

		// Inventory-style block switch: neutral/off -> emerald/on.
		int track = enabled
				? OriginTheme.lerpColor(OriginTheme.SWITCH_OFF, OriginTheme.SWITCH_ON, k)
				: OriginTheme.lerpColor(IOS_OFF_DISABLED, IOS_ON_DISABLED, k);
		panel(g, x, y, wDisp, hDisp, OriginTheme.RADIUS_SM, track, OriginTheme.SWITCH_STROKE);

		// Near-white square knob sliding between the track's inset ends.
		int pad = Math.max(1, Math.round(hDisp * 0.12f));
		int knob = hDisp - 2 * pad;
		int travel = Math.max(0, wDisp - 2 * pad - knob);
		int kx = x + pad + Math.round(k * travel);
		panel(g, kx, y + pad, knob, knob, OriginTheme.RADIUS_SM,
				enabled ? OriginTheme.SWITCH_KNOB : 0xFFB8B8B8, OriginTheme.SWITCH_STROKE);
		return k;
	}

	/**
	 * Mod icon: Origin's own 24-grid line glyph from the mod_icons atlas (see
	 * ModIcons), tinted with the FULL argb so icons follow the theme colour --
	 * TEXT on a card, ACCENT when the mod is on, MUTED in a field. The alpha
	 * carries the mod menu's open/close and page-swap fades.
	 */
	public static void icon(GuiGraphics g, String name, int x, int y, int size, int argb) {
		ModIcons.draw(g, name, x, y, size, argb);
	}

	/**
	 * Cursor glow -- intentionally does nothing now (matches 1.21.1).
	 *
	 * A soft radial bloom is the opposite of the pixel grid: it's the one effect
	 * that can't exist on Minecraft's grid at any size, because it IS the blur.
	 * Kept as a no-op rather than deleted so the call sites (mod menu halo, HUD
	 * editor) stay readable as "there was a glow here" and don't need touching.
	 */
	public static void glow(GuiGraphics g, double cx, double cy, int diameter, float alpha) {
	}

	/** THE Origin logo: textures/ui/origin_logo.png (LOGO_PATH), the only way mod UI draws the mark.
	 *  Never redraw it; if the texture is missing nothing is drawn (fail soft). */
	public static void logo(GuiGraphics g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok || logoTex == null) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, logoTex, (int) (cx - size / 2.0), (int) (cy - size / 2.0), 0f, 0f, size, size, 256, 256, 256, 256, ARGB.white(alpha));
	}

	/** Rounded pill slider: track + fill + a ball knob that stays vertically
	 *  centered and sits flush at both ends (its travel is inset by the knob
	 *  radius). `y` is the pill top; value 0..1. Returns the knob center x. */
	public static int slider(GuiGraphics g, int x, int y, int w, double value, boolean active) {
		int h = 6;              // pill height
		int r = 5;              // knob radius
		double v = Math.max(0.0, Math.min(1.0, value));
		int cy = y + h / 2;     // pill vertical center — the knob centers on this
		panel(g, x, y, w, h, OriginTheme.RADIUS_SM, OriginTheme.SWITCH_OFF, OriginTheme.SWITCH_STROKE);
		// The knob CENTER travels the full track (x .. x+w) so it reaches both
		// endpoints — at 0% its center sits on the left end, at 100% on the right
		// end (the ball overhangs by its radius, like a standard slider). This
		// also matches the drag hit-test, which maps the full track width to 0..1.
		int kx = x + (int) Math.round(v * w);
		int fw = kx - x;        // fill runs from the track start to the knob center
		if (fw > 0) {
			panel(g, x, y, Math.min(w, fw), h, OriginTheme.RADIUS_SM,
					active ? OriginTheme.ACCENT_2 : OriginTheme.ACCENT, 0);
		}
		int kd = active ? r * 2 + 4 : r * 2 + 2;
		panel(g, kx - kd / 2, cy - kd / 2, kd, kd, OriginTheme.RADIUS_SM,
				OriginTheme.TEXT, OriginTheme.BOX_BORDER);
		return kx;
	}

	// ---- internals ----



	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		ensureLoaded0();
	}

	private static final String[] TEXTURES = {
			"/assets/originclient/textures/ui/switch_track.png", "/assets/originclient/textures/ui/switch_knob.png",
			"/assets/originclient/textures/ui/radial_glow.png",
			LOGO_PATH, "/assets/originclient/textures/ui/star.png",
	};

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
			starTex = reg(mc, "ui_star", "/assets/originclient/textures/ui/star.png");
			logoTex = reg(mc, "ui_logo", LOGO_PATH);
			ok = true;
		} catch (Throwable e) {
			ok = false;
			// The images after the failing one were decoded by the prefetch but never
			// handed over: free them (a no-op for the ones already taken).
			for (String p : TEXTURES) {
				com.origin.client.client.OriginAssets.discard(p);
			}
			com.origin.client.OriginClient.LOGGER.warn("Origin UI assets failed to load; using flat fallbacks", e);
		}
	}

	// Decoded on the startup reload's worker threads (OriginAssets); only the GPU upload happens here.
	private static JsonObject readJson(String path) throws Exception {
		return com.origin.client.client.OriginAssets.json(path);
	}

	private static ResourceLocation reg(Minecraft mc, String name, String path) throws Exception {
		NativeImage image = com.origin.client.client.OriginAssets.image(path);
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
		// 1.21.11: DynamicTexture lost setFilter — filtering now rides the texture's
		// GpuSampler. GUI_TEXTURED's default samples NEAREST (choppy icons/font), so
		// bind a LINEAR clamp sampler to match the smooth pre-1.21.6 look.
		DynamicTexture tex;
		try {
			tex = new DynamicTexture(() -> "originclient:" + name, image);
		} catch (Throwable t) {
			image.close();   // never reached a texture: ours to free
			throw t;
		}
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
