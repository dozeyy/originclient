package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;

// The premium drawing kit shared by the mod menu and HUD editor: baked
// high-res assets (rounded panels, Apple-style switch, 96px icon atlas,
// radial glow, brand rings) drawn with GL_LINEAR so nothing but the font is
// ever pixelated. GL discipline as documented in CODE_REVIEW.md: blend +
// default func before textured draws, shader color reset after tints.
public final class OriginUi {
	private static final Gson GSON = new Gson();
	private static volatile boolean loaded = false;
	private static boolean ok = false;

	/** The one reference to the official logo (a classpath path). Everything that reads the logo uses this. */
	public static final String LOGO_PATH = "/assets/originclient/textures/ui/origin_logo.png";
	private static ResourceLocation fillTex, borderTex, trackTex, knobTex, glowTex, logoTex, starTex;
	private static int panelTexSize = 96, panelCorner = 24;

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

	/** Shared fast hover response for every hand-rendered Origin control. */
	public static float hover(String id, boolean target) {
		return anim("hover:" + id, target,
				target ? OriginTheme.HOVER_IN_MS : OriginTheme.HOVER_OUT_MS);
	}

	/**
	 * The single block-surface primitive for the whole client. Older screens may
	 * still request pill-sized corner values, so the renderer normalizes every
	 * request to the Workbench 1/2/3 px scale based on the control's short edge.
	 * This prevents a missed call site from bringing circular buttons back.
	 */
	public static void panel(Gfx g, int x, int y, int w, int h, int corner, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = craftedRadius(w, h, corner);
		// 1.8.9 (like 1.16.5): fixed-function GL has no rounded-box SDF shader, so every panel is
		// the software anti-aliased fill + stroke (Gfx keeps the faint AA pixels).
		roundedFill(g, x, y, w, h, r, fill);
		if (((border >>> 24) & 0xFF) > 0) {
			roundedStroke(g, x, y, w, h, r, border);
		}
	}

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

	/**
	 * A rounded rect at an EXACT radius -- no Workbench radius normalisation.
	 * Used by OriginDeck (the 2026-10 menu redesign), which draws in physical
	 * pixels and sizes every corner itself. 1.21.1 draws this as a rounded-box
	 * SDF quad; fixed-function 1.8.9 has no shader, so it is the software path
	 * with a FLOAT radius: the box edges are whole pixels (OriginDeck snaps them),
	 * the interior is a few bulk quads, and only the corner squares are shaded
	 * per pixel by their exact coverage of the rounded shape. Everything goes out
	 * as ONE fixed-function quad batch (aaLine's state), not a fill per run.
	 * `border` (if any) is a 1px ring just inside the edge, drawn OVER the fill
	 * -- a translucent border composites onto the fill, as the mock's do.
	 */
	public static void roundRect(Gfx g, float x, float y, float w, float h, float r, int fill, int border) {
		int x0 = Math.round(x), y0 = Math.round(y);
		int wi = Math.round(x + w) - x0, hi = Math.round(y + h) - y0;
		if (wi <= 0 || hi <= 0) {
			return;
		}
		boolean hasFill = ((fill >>> 24) & 0xFF) > 0, hasBorder = ((border >>> 24) & 0xFF) > 0;
		if (!hasFill && !hasBorder) {
			return;
		}
		float rad = Math.max(0f, Math.min(r, Math.min(wi, hi) / 2f));
		GlStateManager.disableTexture2D();
		Gfx.defaultBlend();
		GlStateManager.disableAlpha();
		WorldRenderer buf = Tessellator.getInstance().getWorldRenderer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
		if (hasFill) {
			roundShape(buf, x0, y0, wi, hi, rad, false, fill);
		}
		if (hasBorder) {
			roundShape(buf, x0, y0, wi, hi, rad, true, border);
		}
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countGfxDraw();
		}
		Tessellator.getInstance().draw();
		Gfx.resetColor();   // vertex colours leave the GL colour undefined (see Gfx)
		GlStateManager.disableBlend();
		GlStateManager.enableAlpha();
		GlStateManager.enableTexture2D();
	}

	/** Emits a rounded box (ring=false) or its 1px inside ring (ring=true). */
	private static void roundShape(WorldRenderer buf, int x, int y, int w, int h, float rad,
								   boolean ring, int color) {
		int K = (int) Math.ceil(rad);
		if (ring && rad < 1f) {
			K = 0;
		}
		// Corner squares; clamped so opposite corners never overlap (odd sizes).
		int kl = Math.min(K, w / 2), kr = Math.min(K, w - kl);
		int kt = Math.min(K, h / 2), kb = Math.min(K, h - kt);
		if (!ring) {
			quad(buf, x + kl, y, x + w - kr, y + h, color);              // centre column
			quad(buf, x, y + kt, x + kl, y + h - kb, color);             // left band
			quad(buf, x + w - kr, y + kt, x + w, y + h - kb, color);     // right band
		} else {
			quad(buf, x + kl, y, x + w - kr, y + 1, color);              // top
			quad(buf, x + kl, y + h - 1, x + w - kr, y + h, color);      // bottom
			quad(buf, x, y + kt, x + 1, y + h - kb, color);              // left
			quad(buf, x + w - 1, y + kt, x + w, y + h - kb, color);      // right
		}
		if (K == 0) {
			return;
		}
		// arc centres sit `rad` in from each edge
		cornerRuns(buf, x, y, kl, kt, x + rad, y + rad, -1, -1, rad, ring, color);
		cornerRuns(buf, x + w - kr, y, kr, kt, x + w - rad, y + rad, 1, -1, rad, ring, color);
		cornerRuns(buf, x, y + h - kb, kl, kb, x + rad, y + h - rad, -1, 1, rad, ring, color);
		cornerRuns(buf, x + w - kr, y + h - kb, kr, kb, x + w - rad, y + h - rad, 1, 1, rad, ring, color);
	}

	/**
	 * Shades one corner square (sw x sh at sx,sy) per pixel. Distance is measured
	 * only OUTWARD from the arc centre (sx/sy = -1 left/top, +1 right/bottom), so
	 * pixels level with the straight edges get the edge's plain coverage and the
	 * square may be any size around the arc. Same-alpha runs batch into one quad.
	 */
	private static void cornerRuns(WorldRenderer buf, int sx0, int sy0, int sw, int sh,
								   float acx, float acy, int dirX, int dirY, float rad, boolean ring, int color) {
		int base = (color >>> 24) & 0xFF;
		int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
		for (int py = sy0; py < sy0 + sh; py++) {
			int runStart = -1, runA = 0;
			for (int px = sx0; px <= sx0 + sw; px++) {
				int a = 0;
				if (px < sx0 + sw) {
					double dx = Math.max(0.0, (px + 0.5 - acx) * dirX);
					double dy = Math.max(0.0, (py + 0.5 - acy) * dirY);
					double dist = Math.sqrt(dx * dx + dy * dy);
					double cov = clamp01(rad - dist + 0.5);
					if (ring) {
						cov -= clamp01(rad - 1 - dist + 0.5);
					}
					a = cov <= 0.001 ? 0 : (int) Math.round(base * cov);
				}
				if (a != runA) {
					if (runStart >= 0 && runA > 0) {
						buf.pos(runStart, py, 0f).color(cr, cg, cb, runA).endVertex();
						buf.pos(runStart, py + 1, 0f).color(cr, cg, cb, runA).endVertex();
						buf.pos(px, py + 1, 0f).color(cr, cg, cb, runA).endVertex();
						buf.pos(px, py, 0f).color(cr, cg, cb, runA).endVertex();
					}
					runStart = px;
					runA = a;
				}
			}
		}
	}

	private static void quad(WorldRenderer buf, int x0, int y0, int x1, int y1, int color) {
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		int a = (color >>> 24) & 0xFF, cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
		buf.pos(x0, y0, 0f).color(cr, cg, cb, a).endVertex();
		buf.pos(x0, y1, 0f).color(cr, cg, cb, a).endVertex();
		buf.pos(x1, y1, 0f).color(cr, cg, cb, a).endVertex();
		buf.pos(x1, y0, 0f).color(cr, cg, cb, a).endVertex();
	}

	/** A soft radial glow (the baked radial_glow texture) centred on (cx,cy). */
	public static void softGlow(Gfx g, float cx, float cy, float size, int argb) {
		ensureLoaded();
		float a = ((argb >>> 24) & 0xFF) / 255f;
		if (!ok || glowTex == null || a <= 0f) {
			return;
		}
		Gfx.defaultBlend();
		tint(argb);
		Pose pose = g.pose();
		pose.pushPose();
		pose.translate(cx - size / 2f, cy - size / 2f, 0);
		pose.scale(size / 512f, size / 512f, 1f);
		g.blit(glowTex, 0, 0, 0f, 0f, 512, 512, 512, 512);
		pose.popPose();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	/**
	 * Kept for source compatibility with every existing button-shaped call site.
	 * The supplied cut is normalized by {@link #panel}, so bevel and panel paths
	 * cannot diverge into separate shape languages.
	 */
	public static void bevelPanel(Gfx g, int x, int y, int w, int h, int cut, int fill, int border) {
		panel(g, x, y, w, h, cut, fill, border);
	}

	// ---- rounded-rect primitives (software anti-aliased) ----

	/** Solid rounded rectangle: straight interior as bulk fills, 4 AA disc corners. */
	private static void roundedFill(Gfx g, int x, int y, int w, int h, int r, int color) {
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
		aaCorner(g, x, y, x + r, y + r, r, 0, color);                       // top-left
		aaCorner(g, x + w - r, y, x + w - r, y + r, r, 0, color);           // top-right
		aaCorner(g, x, y + h - r, x + r, y + h - r, r, 0, color);           // bottom-left
		aaCorner(g, x + w - r, y + h - r, x + w - r, y + h - r, r, 0, color); // bottom-right
	}

	/** 1px rounded outline: straight edges (clear of the corners) + 4 AA ring corners. */
	private static void roundedStroke(Gfx g, int x, int y, int w, int h, int r, int color) {
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
		aaCorner(g, x, y, x + r, y + r, r, r - 1, color);                       // top-left
		aaCorner(g, x + w - r, y, x + w - r, y + r, r, r - 1, color);           // top-right
		aaCorner(g, x, y + h - r, x + r, y + h - r, r, r - 1, color);           // bottom-left
		aaCorner(g, x + w - r, y + h - r, x + w - r, y + h - r, r, r - 1, color); // bottom-right
	}

	/**
	 * Paints one r×r corner square at (rx,ry), arc-centered at (cx,cy). Each
	 * pixel's alpha = its coverage of the annulus [rInner, rOuter] (rInner=0 →
	 * solid quarter-disc for fills; rInner=rOuter-1 → a 1px ring for borders),
	 * so the curve blends smoothly against whatever is already behind it.
	 */
	private static void aaCorner(Gfx g, int rx, int ry, double cx, double cy, int rOuter, int rInner, int color) {
		int base = (color >>> 24) & 0xFF;
		if (base == 0) {
			return;
		}
		int rgb = color & 0xFFFFFF;
		// Batch consecutive same-alpha pixels in each row into one fill — the
		// fully-covered interior of a corner collapses to a single run, so a
		// rounded panel costs a handful of quads per corner instead of r² of them.
		for (int py = ry; py < ry + rOuter; py++) {
			int runStart = -1, runArgb = 0;
			for (int px = rx; px <= rx + rOuter; px++) {
				int argb = 0;
				if (px < rx + rOuter) {
					double dx = px + 0.5 - cx, dy = py + 0.5 - cy;
					double dist = Math.sqrt(dx * dx + dy * dy);
					double cov = clamp01(rOuter - dist + 0.5) - clamp01(rInner - dist + 0.5);
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
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	// ---- anti-aliased vector icons ----

	/**
	 * A smooth stroked line from (ax,ay) to (bx,by), `half` px to each side of
	 * the centre. Per-pixel coverage = distance-to-segment, so the stroke is
	 * anti-aliased and round-capped — the same coverage trick the rounded
	 * corners use, reused for crisp little glyphs (the mod menu's back chevron,
	 * Edit HUD pencil and close X).
	 *
	 * Coverage is evaluated per DEVICE pixel, not per GUI pixel. It used to run
	 * on the GUI grid, so at GUI scale 2-4 every coverage cell was a 2x2..4x4
	 * block of screen pixels and the rotated strokes came out as visibly
	 * stair-stepped, low-res icons next to the crisp text -- the fixed-function version of
	 * the jagged-icon bug (newer versions had the SDF quad clipping its own
	 * anti-aliasing; there is no SDF shader here). Now the loop runs in device
	 * pixels under a 1/scale pose, so the AA ramp is one real pixel wide at any
	 * GUI scale, and the bounds pad by half + 1 device px so the ramp is never
	 * cut off. All runs go out as ONE fixed-function quad batch (texturing off,
	 * blend on -- Gfx.fillGradient's state) instead of a fill per run, since
	 * device resolution means scale-times more runs.
	 */
	public static void aaLine(Gfx g, double ax, double ay, double bx, double by, double half, int color) {
		int base = (color >>> 24) & 0xFF;
		if (base == 0) {
			return;
		}
		int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF;
		// GUI scale is a whole number on this era; 1 = the old GUI-pixel grid.
		int s = Gfx.guiScale();
		float inv = 1f / s;
		ax *= s;
		ay *= s;
		bx *= s;
		by *= s;
		half *= s;
		int x0 = (int) Math.floor(Math.min(ax, bx) - half - 1);
		int x1 = (int) Math.ceil(Math.max(ax, bx) + half + 1);
		int y0 = (int) Math.floor(Math.min(ay, by) - half - 1);
		int y1 = (int) Math.ceil(Math.max(ay, by) + half + 1);
		double abx = bx - ax, aby = by - ay;
		double len2 = abx * abx + aby * aby;
		GlStateManager.disableTexture2D();
		Gfx.defaultBlend();
		GlStateManager.disableAlpha();
		WorldRenderer buf = Tessellator.getInstance().getWorldRenderer();
		buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
		for (int py = y0; py < y1; py++) {
			int runStart = -1, runA = 0;
			for (int px = x0; px <= x1; px++) {
				int a = 0;
				if (px < x1) {
					double dpx = px + 0.5 - ax, dpy = py + 0.5 - ay;
					double t = len2 <= 1e-6 ? 0 : clamp01((dpx * abx + dpy * aby) / len2);
					double cxp = ax + t * abx, cyp = ay + t * aby;
					double dx = px + 0.5 - cxp, dy = py + 0.5 - cyp;
					double dist = Math.sqrt(dx * dx + dy * dy);
					double cov = clamp01(half - dist + 0.5);
					a = cov <= 0.001 ? 0 : (int) Math.round(base * cov);
				}
				if (a != runA) {
					if (runStart >= 0 && runA > 0) {
						// one device-pixel-tall run, mapped back to GUI units
						float qx0 = runStart * inv, qx1 = px * inv, qy0 = py * inv, qy1 = (py + 1) * inv;
						buf.pos(qx0, qy0, 0f).color(cr, cg, cb, runA).endVertex();
						buf.pos(qx0, qy1, 0f).color(cr, cg, cb, runA).endVertex();
						buf.pos(qx1, qy1, 0f).color(cr, cg, cb, runA).endVertex();
						buf.pos(qx1, qy0, 0f).color(cr, cg, cb, runA).endVertex();
					}
					runStart = px;
					runA = a;
				}
			}
		}
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countGfxDraw();
		}
		Tessellator.getInstance().draw();
		Gfx.resetColor();   // vertex colours leave the GL colour undefined (see Gfx)
		GlStateManager.disableBlend();
		GlStateManager.enableAlpha();
		GlStateManager.enableTexture2D();
	}

	/**
	 * A stroke from (ax,ay)→(bx,by), `half` px to each side. 1.21.1 draws it as a
	 * rounded-box SDF capsule; fixed-function 1.8.9 has no shader, so it is
	 * {@link #aaLine}'s per-DEVICE-pixel coverage stroke (anti-aliased,
	 * round-capped) -- the same crisp vector look at any GUI scale.
	 */
	public static void capsule(Gfx g, double ax, double ay, double bx, double by, double half, int color) {
		// 1.8.9: no SDF shader -- the per-pixel coverage stroke is the vector path.
		aaLine(g, ax, ay, bx, by, half, color);
	}

	/** A clean × mark filling a size×size box at (x,y). */
	public static void iconClose(Gfx g, int x, int y, int size, int color) {
		double h = Math.max(0.9, size * 0.10);
		double in = size * 0.22;
		capsule(g, x + in, y + in, x + size - in, y + size - in, h, color);
		capsule(g, x + size - in, y + in, x + in, y + size - in, h, color);
	}

	/** A pencil (edit) glyph filling a size×size box at (x,y): a tapered shaft
	 *  from the eraser end down to a graphite point. */
	public static void iconEdit(Gfx g, int x, int y, int size, int color) {
		double s = size;
		double ex = x + s * 0.80, ey = y + s * 0.20;   // eraser end (top-right)
		double mx = x + s * 0.36, my = y + s * 0.64;   // where the shaft meets the tip
		double tx = x + s * 0.16, ty = y + s * 0.84;   // graphite point (bottom-left)
		capsule(g, ex, ey, mx, my, s * 0.12, color);   // shaft (thicker)
		capsule(g, mx, my, tx, ty, s * 0.055, color);  // tip (tapers to a point)
	}

	/** A chevron ("<" when left, ">" when right) filling a size×size box at (x,y),
	 *  drawn as two SDF-capsule strokes meeting at the point. Replaces the
	 *  font-glyph back/dropdown/cycle arrows so all menu chrome is vector. */
	public static void iconChevron(Gfx g, int x, int y, int size, int color, boolean left) {
		double h = Math.max(0.9, size * 0.10);
		double midY = y + size * 0.5;
		double pointX = x + size * (left ? 0.34 : 0.66);   // the vertex
		double armX = x + size * (left ? 0.66 : 0.34);     // the two open ends
		double topY = y + size * 0.24, botY = y + size * 0.76;
		capsule(g, pointX, midY, armX, topY, h, color);
		capsule(g, pointX, midY, armX, botY, h, color);
	}

	/**
	 * Apple iOS-style toggle (Will's redesign spec): a fully-rounded pill track
	 * that is GREEN when on and RED when off, with a pure-white circular knob
	 * that slides smoothly LEFT = off / RIGHT = on. The green↔red crossfade and
	 * the knob's travel are both eased on the same 0..1 progress, so flipping it
	 * animates as one motion. Same signature/geometry as before
	 * (wDisp × wDisp*8/15) so every existing layout and hit-test is unchanged.
	 * Returns knob progress 0..1.
	 */
	public static float switchAt(Gfx g, String id, int x, int y, int wDisp, boolean on, boolean enabled) {
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
	public static void icon(Gfx g, String name, int x, int y, int size, int argb) {
		ModIcons.draw(g, name, x, y, size, argb);
	}

	/**
	 * The favourite STAR — a baked, anti-aliased 5-point star blitted through
	 * GL_LINEAR so it stays crisp at any size. Replaces the pixelated "★" font
	 * glyph. Tinted by `argb`: gold when pinned, faint white otherwise. Only RGB
	 * + alpha are used.
	 */
	public static void star(Gfx g, int x, int y, int size, int argb) {
		ensureLoaded();
		float a = ((argb >>> 24) & 0xFF) / 255f;
		if (!ok || starTex == null || a <= 0f) {
			return;
		}
		Gfx.defaultBlend();
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, a);
		g.blit(starTex, x, y, size, size, 0f, 0f, 64, 64, 64, 64);
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	/**
	 * Cursor glow -- intentionally does nothing now.
	 *
	 * A soft radial bloom is the opposite of the pixel grid: it's the one effect
	 * that can't exist on Minecraft's grid at any size, because it IS the blur.
	 * Kept as a no-op rather than deleted so the call sites (mod menu halo, HUD
	 * editor) stay readable as "there was a glow here" and don't need touching.
	 */
	public static void glow(Gfx g, double cx, double cy, int diameter, float alpha) {
	}

	/** THE Origin logo: textures/ui/origin_logo.png (LOGO_PATH), the only way mod UI draws the mark.
	 *  Never redraw it; if the texture is missing nothing is drawn (fail soft). */
	public static void logo(Gfx g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok || logoTex == null) {
			return;
		}
		Gfx.defaultBlend();
		GlStateManager.color(1f, 1f, 1f, alpha);
		g.blit(logoTex, (int) (cx - size / 2.0), (int) (cy - size / 2.0), size, size, 0f, 0f, 256, 256, 256, 256);
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	/** Rounded pill slider: track + fill + a ball knob that stays vertically
	 *  centered and sits flush at both ends (its travel is inset by the knob
	 *  radius). `y` is the pill top; value 0..1. Returns the knob center x. */
	public static int slider(Gfx g, int x, int y, int w, double value, boolean active) {
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

	private static void tint(int argb) {
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
	}

	private static void nine(Gfx g, ResourceLocation tex, int x, int y, int w, int h, int cd) {
		int c = panelCorner, t = panelTexSize, mid = t - 2 * c, mw = w - 2 * cd, mh = h - 2 * cd;
		g.blit(tex, x, y, cd, cd, 0f, 0f, c, c, t, t);
		g.blit(tex, x + w - cd, y, cd, cd, (float) (t - c), 0f, c, c, t, t);
		g.blit(tex, x, y + h - cd, cd, cd, 0f, (float) (t - c), c, c, t, t);
		g.blit(tex, x + w - cd, y + h - cd, cd, cd, (float) (t - c), (float) (t - c), c, c, t, t);
		if (mw > 0) {
			g.blit(tex, x + cd, y, mw, cd, (float) c, 0f, mid, c, t, t);
			g.blit(tex, x + cd, y + h - cd, mw, cd, (float) c, (float) (t - c), mid, c, t, t);
		}
		if (mh > 0) {
			g.blit(tex, x, y + cd, cd, mh, 0f, (float) c, c, mid, t, t);
			g.blit(tex, x + w - cd, y + cd, cd, mh, (float) (t - c), (float) c, c, mid, t, t);
		}
		if (mw > 0 && mh > 0) {
			g.blit(tex, x + cd, y + cd, mw, mh, (float) c, (float) c, mid, mid, t, t);
		}
	}

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
			Minecraft mc = Minecraft.getMinecraft();
			JsonObject btn = readJson("/assets/originclient/textures/ui/buttons.json");
			panelTexSize = btn.get("texSize").getAsInt();
			panelCorner = btn.get("corner").getAsInt();
			fillTex = reg(mc, "ui_fill", "/assets/originclient/textures/ui/button_fill.png");
			borderTex = reg(mc, "ui_border", "/assets/originclient/textures/ui/button_border.png");
			trackTex = reg(mc, "ui_switch_track", "/assets/originclient/textures/ui/switch_track.png");
			knobTex = reg(mc, "ui_switch_knob", "/assets/originclient/textures/ui/switch_knob.png");
			glowTex = reg(mc, "ui_glow", "/assets/originclient/textures/ui/radial_glow.png");
			logoTex = reg(mc, "ui_logo", LOGO_PATH);
			starTex = reg(mc, "ui_star", "/assets/originclient/textures/ui/star.png");
			ok = true;
		} catch (Throwable e) {
			ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin UI assets failed to load; using flat fallbacks", e);
		}
	}

	private static JsonObject readJson(String path) throws Exception {
		try (InputStream in = OriginUi.class.getResourceAsStream(path)) {
			return GSON.fromJson(new String(com.origin.client.client.IoCompat.readAllBytes(in), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
		}
	}

	// 1.8.9: PNG via TextureUtil.readBufferedImage -> DynamicTexture, GL_LINEAR + mipmaps
	// (Gfx.registerTexture): the baked art is large and drawn small.
	private static ResourceLocation reg(Minecraft mc, String name, String path) throws Exception {
		ResourceLocation id = new ResourceLocation("originclient", name);
		if (Gfx.registerTexture(id, Gfx.readImage(path), true, true) == null) {
			throw new IllegalStateException("texture upload failed: " + path);
		}
		return id;
	}
}
