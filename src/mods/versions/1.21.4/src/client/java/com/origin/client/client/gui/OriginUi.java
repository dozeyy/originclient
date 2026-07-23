package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.ARGB;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// The premium drawing kit shared by the mod menu and HUD editor: baked
// high-res assets (rounded panels, Apple-style switch, 96px icon atlas,
// radial glow, brand rings) drawn with GL_LINEAR so nothing but the font is
// ever pixelated. GL discipline as documented in CODE_REVIEW.md: blend +
// default func before textured draws, shader color reset after tints.
public final class OriginUi {
	private static final Gson GSON = new Gson();
	private static volatile boolean loaded = false;
	private static boolean ok = false;

	private static ResourceLocation fillTex, borderTex, trackTex, knobTex, glowTex, ringTex, logoTex, starTex;
	private static int panelTexSize = 96, panelCorner = 24;

	// iOS toggle colors (Will's redesign spec): green when ON, red when OFF, a
	// pure-white circular knob that slides between them. Kept local to OriginUi
	// so the switch look is one value, independent of the shared OriginTheme
	// tokens (which stay squared/monochrome for the rest of the system).
	private static final int IOS_ON = 0xFF34C759;   // Apple system green
	private static final int IOS_OFF = 0xFFFF3B30;  // Apple system red
	private static final int IOS_ON_DISABLED = 0xFF4A6B52;
	private static final int IOS_OFF_DISABLED = 0xFF6B4A48;

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
	 * The 2026-07 redesign (Will's spec, reference OneConfig): every Origin
	 * surface gets subtle, smooth rounded corners with NO visible aliasing.
	 * `corner` is the radius again (clamped to half the smaller side). Corners
	 * are software-anti-aliased by coverage — each corner pixel's alpha is
	 * scaled by how much of it falls inside the arc — so curves read smooth at
	 * any size without a single texture or shader. Every surface in the mod
	 * menu, HUD editor, chips, tooltips and switches draws through here, so one
	 * change rounds all of them consistently by construction.
	 */
	public static void panel(GuiGraphics g, int x, int y, int w, int h, int corner, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.max(0, Math.min(corner, Math.min(w, h) / 2));
		// Scalable path: one rounded-box SDF quad, perfect curves at any scale.
		// Gated to screens (menus) so it never touches the in-world HUD render
		// path, and falls back to the software fills below if the shader is off
		// or failed to load.
		if (OriginShaders.roundActive()) {
			roundShader(g, x, y, w, h, r, fill, border);
			return;
		}
		roundedFill(g, x, y, w, h, r, fill);
		if (((border >>> 24) & 0xFF) > 0) {
			roundedStroke(g, x, y, w, h, r, border);
		}
	}

	/** Draws the rounded rect as a single SDF quad via {@link OriginShaders#ROUND}. */
	private static void roundShader(GuiGraphics g, int x, int y, int w, int h, int r, int fill, int border) {
		float hw = w / 2f, hh = h / 2f;
		boolean hasBorder = ((border >>> 24) & 0xFF) > 0;
		var sh = OriginShaders.ROUND;
		Matrix4f m = g.pose().last().pose();
		// POSITION_TEX: UV carries the local pixel coordinate measured from centre,
		// which the fragment shader turns into the rounded-box distance.
		BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		bb.addVertex(m, x, y, 0).setUv(-hw, -hh);
		bb.addVertex(m, x, y + h, 0).setUv(-hw, hh);
		bb.addVertex(m, x + w, y + h, 0).setUv(hw, hh);
		bb.addVertex(m, x + w, y, 0).setUv(hw, -hh);
		MeshData mesh = bb.build();
		if (mesh == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(sh);
		sh.safeGetUniform("RectHalf").set(hw, hh);
		sh.safeGetUniform("Radius").set((float) Math.min(r, Math.min(hw, hh)));
		sh.safeGetUniform("Border").set(hasBorder ? 1.0f : 0.0f);
		sh.safeGetUniform("FillColor").set(((fill >> 16) & 0xFF) / 255f, ((fill >> 8) & 0xFF) / 255f,
				(fill & 0xFF) / 255f, ((fill >>> 24) & 0xFF) / 255f);
		sh.safeGetUniform("BorderColor").set(((border >> 16) & 0xFF) / 255f, ((border >> 8) & 0xFF) / 255f,
				(border & 0xFF) / 255f, ((border >>> 24) & 0xFF) / 255f);
		BufferUploader.drawWithShader(mesh);
		// restore state so it can't leak into later GUI / world (sky) rendering
		OriginShaders.restoreState();
	}

	/**
	 * Kept for source compatibility with every existing "button-shaped" call
	 * site. The old 45° chamfer is gone — buttons, cards and chips are rounded
	 * like everything else now (Will's redesign: one consistent rounded look),
	 * so this just forwards to {@link #panel} with the cut used as the radius.
	 */
	public static void bevelPanel(GuiGraphics g, int x, int y, int w, int h, int cut, int fill, int border) {
		panel(g, x, y, w, h, cut, fill, border);
	}

	// ---- rounded-rect primitives (software anti-aliased) ----

	/** Solid rounded rectangle: straight interior as bulk fills, 4 AA disc corners. */
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
		aaCorner(g, x, y, x + r, y + r, r, 0, color);                       // top-left
		aaCorner(g, x + w - r, y, x + w - r, y + r, r, 0, color);           // top-right
		aaCorner(g, x, y + h - r, x + r, y + h - r, r, 0, color);           // bottom-left
		aaCorner(g, x + w - r, y + h - r, x + w - r, y + h - r, r, 0, color); // bottom-right
	}

	/** 1px rounded outline: straight edges (clear of the corners) + 4 AA ring corners. */
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
	private static void aaCorner(GuiGraphics g, int rx, int ry, double cx, double cy, int rOuter, int rInner, int color) {
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
	 * corners use, reused for crisp little glyphs (the sidebar's edit/close
	 * icons) instead of blocky diagonals.
	 */
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

	/**
	 * A stroke from (ax,ay)→(bx,by), `half` px to each side, drawn as a rounded-box
	 * SDF CAPSULE through the {@link OriginShaders#ROUND} shader so it anti-aliases
	 * in SCREEN space — exactly the vector crispness of the menu text and panels,
	 * instead of the logical-pixel coverage {@link #aaLine} produces (which read a
	 * step lower-res than everything else). The capsule is a thin horizontal
	 * rounded rect (corner = half → round caps) rotated into place by the pose, so
	 * the SDF resolves the edge per device-pixel at any GUI scale. Falls back to
	 * {@link #aaLine} when the SDF shader isn't available (crash safety).
	 */
	public static void capsule(GuiGraphics g, double ax, double ay, double bx, double by, double half, int color) {
		if (((color >>> 24) & 0xFF) == 0) {
			return;
		}
		if (!OriginShaders.roundActive() || OriginShaders.ROUND == null) {
			aaLine(g, ax, ay, bx, by, half, color);
			return;
		}
		double dx = bx - ax, dy = by - ay;
		double len = Math.sqrt(dx * dx + dy * dy);
		double cxp = (ax + bx) / 2.0, cyp = (ay + by) / 2.0;
		double ang = Math.toDegrees(Math.atan2(dy, dx));
		// FLOAT-precise capsule: a rounded box whose half-extents keep the true
		// stroke half-width as the corner radius, so thin/tapered strokes (the
		// pencil's tip) stay exactly their intended sub-pixel weight instead of
		// being quantised up to a 2px min. Round caps land on the endpoints.
		float H = (float) half;
		float halfX = (float) (len / 2.0) + H;
		var pose = g.pose();
		pose.pushPose();
		pose.translate((float) cxp, (float) cyp, 0);
		pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) ang));
		roundShaderF(g, halfX, H, H, color, 0);
		pose.popPose();
	}

	/** Float-precise rounded box centred on the current pose origin (half-extents
	 *  halfX×halfY, corner radius r). Same SDF path as {@link #roundShader} but
	 *  without the integer quantisation, for crisp small vector icons. */
	private static void roundShaderF(GuiGraphics g, float halfX, float halfY, float r, int fill, int border) {
		var sh = OriginShaders.ROUND;
		if (sh == null) {
			return;
		}
		boolean hasBorder = ((border >>> 24) & 0xFF) > 0;
		Matrix4f m = g.pose().last().pose();
		BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
		bb.addVertex(m, -halfX, -halfY, 0).setUv(-halfX, -halfY);
		bb.addVertex(m, -halfX, halfY, 0).setUv(-halfX, halfY);
		bb.addVertex(m, halfX, halfY, 0).setUv(halfX, halfY);
		bb.addVertex(m, halfX, -halfY, 0).setUv(halfX, -halfY);
		MeshData mesh = bb.build();
		if (mesh == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(sh);
		sh.safeGetUniform("RectHalf").set(halfX, halfY);
		sh.safeGetUniform("Radius").set(Math.min(r, Math.min(halfX, halfY)));
		sh.safeGetUniform("Border").set(hasBorder ? 1.0f : 0.0f);
		sh.safeGetUniform("FillColor").set(((fill >> 16) & 0xFF) / 255f, ((fill >> 8) & 0xFF) / 255f,
				(fill & 0xFF) / 255f, ((fill >>> 24) & 0xFF) / 255f);
		sh.safeGetUniform("BorderColor").set(((border >> 16) & 0xFF) / 255f, ((border >> 8) & 0xFF) / 255f,
				(border & 0xFF) / 255f, ((border >>> 24) & 0xFF) / 255f);
		BufferUploader.drawWithShader(mesh);
		OriginShaders.restoreState();
	}

	/** A clean × mark filling a size×size box at (x,y). */
	public static void iconClose(GuiGraphics g, int x, int y, int size, int color) {
		double h = Math.max(0.9, size * 0.10);
		double in = size * 0.22;
		capsule(g, x + in, y + in, x + size - in, y + size - in, h, color);
		capsule(g, x + size - in, y + in, x + in, y + size - in, h, color);
	}

	/** A pencil (edit) glyph filling a size×size box at (x,y): a tapered shaft
	 *  from the eraser end down to a graphite point. */
	public static void iconEdit(GuiGraphics g, int x, int y, int size, int color) {
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
	public static void iconChevron(GuiGraphics g, int x, int y, int size, int color, boolean left) {
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
	public static float switchAt(GuiGraphics g, String id, int x, int y, int wDisp, boolean on, boolean enabled) {
		int hDisp = wDisp * 8 / 15;
		float k = anim("sw:" + id, on, 170.0);

		// Track: red(off) -> green(on); disabled uses muted tones so the whole
		// control reads as unavailable without changing shape.
		int track = enabled
				? OriginTheme.lerpColor(IOS_OFF, IOS_ON, k)
				: OriginTheme.lerpColor(IOS_OFF_DISABLED, IOS_ON_DISABLED, k);
		// Fully-rounded pill: radius = half the height.
		panel(g, x, y, wDisp, hDisp, hDisp / 2, track, 0);

		// Knob: a white circle sliding between the track's inset ends.
		int pad = Math.max(1, Math.round(hDisp * 0.12f));
		int knob = hDisp - 2 * pad;
		int travel = Math.max(0, wDisp - 2 * pad - knob);
		int kx = x + pad + Math.round(k * travel);
		panel(g, kx, y + pad, knob, knob, knob / 2,
				enabled ? 0xFFFFFFFF : 0xFFDDDDDD, 0);
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

	/**
	 * The favourite STAR — a baked, anti-aliased 5-point star blitted through
	 * GL_LINEAR so it stays crisp at any size. Replaces the pixelated "★" font
	 * glyph. Tinted by `argb`: gold when pinned, faint white otherwise. Only RGB
	 * + alpha are used.
	 */
	public static void star(GuiGraphics g, int x, int y, int size, int argb) {
		ensureLoaded();
		float a = ((argb >>> 24) & 0xFF) / 255f;
		if (!ok || starTex == null || a <= 0f) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		// 1.21.4 batched-GUI era: setShaderColor never reaches blits (they flush
		// after the handler returns), so the tint rides the per-blit ARGB argument.
		g.blit(RenderType::guiTextured, starTex, x, y, 0f, 0f, size, size, 64, 64, 64, 64, argb);
	}

	/**
	 * Cursor glow -- intentionally does nothing now.
	 *
	 * A soft radial bloom is the opposite of the pixel grid: it's the one effect
	 * that can't exist on Minecraft's grid at any size, because it IS the blur.
	 * Kept as a no-op rather than deleted so the call sites (mod menu halo, HUD
	 * editor) stay readable as "there was a glow here" and don't need touching.
	 */
	public static void glow(GuiGraphics g, double cx, double cy, int diameter, float alpha) {
	}

	/** The 3-ring Origin mark (brand geometry: one ellipse at 0/60/120 deg). */
	public static void mark(GuiGraphics g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		var pose = g.pose();
		for (int i = 0; i < 3; i++) {
			pose.pushPose();
			pose.translate(cx, cy, 0);
			pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(i * 60f));
			float s = size / 768f * 1.1f;
			pose.scale(s, s, 1f);
			pose.translate(-384f, -384f, 0);
			g.blit(RenderType::guiTextured, ringTex, 0, 0, 0f, 0f, 768, 768, 768, 768, ARGB.white(alpha));
			pose.popPose();
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
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		g.blit(RenderType::guiTextured, logoTex, (int) (cx - size / 2.0), (int) (cy - size / 2.0), 0f, 0f, size, size, 256, 256, 256, 256, ARGB.white(alpha));
	}

	/** Rounded pill slider: track + fill + a ball knob that stays vertically
	 *  centered and sits flush at both ends (its travel is inset by the knob
	 *  radius). `y` is the pill top; value 0..1. Returns the knob center x. */
	public static int slider(GuiGraphics g, int x, int y, int w, double value, boolean active) {
		int h = 6;              // pill height
		int r = 5;              // knob radius
		double v = Math.max(0.0, Math.min(1.0, value));
		int cy = y + h / 2;     // pill vertical center — the knob centers on this
		panel(g, x, y, w, h, h / 2, 0x30FFFFFF, 0);
		// The knob CENTER travels the full track (x .. x+w) so it reaches both
		// endpoints — at 0% its center sits on the left end, at 100% on the right
		// end (the ball overhangs by its radius, like a standard slider). This
		// also matches the drag hit-test, which maps the full track width to 0..1.
		int kx = x + (int) Math.round(v * w);
		int fw = kx - x;        // fill runs from the track start to the knob center
		if (fw > 0) {
			panel(g, x, y, Math.min(w, fw), h, h / 2, active ? 0xE6E0E0E0 : 0xA8D8D8D8, 0);
		}
		ensureLoaded();
		int kd = active ? r * 2 + 4 : r * 2 + 2;   // ball; grows slightly while dragging
		if (ok) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			g.blit(RenderType::guiTextured, knobTex, kx - kd / 2, cy - kd / 2, 0f, 0f, kd, kd, 72, 72, 72, 72);
		} else {
			g.fill(kx - kd / 2, cy - kd / 2, kx + kd / 2, cy + kd / 2, 0xFFE8E8E8);
		}
		return kx;
	}

	// ---- internals ----

	private static void tint(int argb) {
		RenderSystem.setShaderColor(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
	}

	private static void nine(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, int cd) {
		int c = panelCorner, t = panelTexSize, mid = t - 2 * c, mw = w - 2 * cd, mh = h - 2 * cd;
		int argb = 0xFFFFFFFF;
		g.blit(RenderType::guiTextured, tex, x, y, 0f, 0f, cd, cd, c, c, t, t, argb);
		g.blit(RenderType::guiTextured, tex, x + w - cd, y, (float) (t - c), 0f, cd, cd, c, c, t, t, argb);
		g.blit(RenderType::guiTextured, tex, x, y + h - cd, 0f, (float) (t - c), cd, cd, c, c, t, t, argb);
		g.blit(RenderType::guiTextured, tex, x + w - cd, y + h - cd, (float) (t - c), (float) (t - c), cd, cd, c, c, t, t, argb);
		if (mw > 0) {
			g.blit(RenderType::guiTextured, tex, x + cd, y, (float) c, 0f, mw, cd, mid, c, t, t, argb);
			g.blit(RenderType::guiTextured, tex, x + cd, y + h - cd, (float) c, (float) (t - c), mw, cd, mid, c, t, t, argb);
		}
		if (mh > 0) {
			g.blit(RenderType::guiTextured, tex, x, y + cd, 0f, (float) c, cd, mh, c, mid, t, t, argb);
			g.blit(RenderType::guiTextured, tex, x + w - cd, y + cd, (float) (t - c), (float) c, cd, mh, c, mid, t, t, argb);
		}
		if (mw > 0 && mh > 0) {
			g.blit(RenderType::guiTextured, tex, x + cd, y + cd, (float) c, (float) c, mw, mh, mid, mid, t, t, argb);
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
			Minecraft mc = Minecraft.getInstance();
			JsonObject btn = readJson("/assets/originclient/textures/ui/buttons.json");
			panelTexSize = btn.get("texSize").getAsInt();
			panelCorner = btn.get("corner").getAsInt();
			fillTex = reg(mc, "ui_fill", "/assets/originclient/textures/ui/button_fill.png");
			borderTex = reg(mc, "ui_border", "/assets/originclient/textures/ui/button_border.png");
			trackTex = reg(mc, "ui_switch_track", "/assets/originclient/textures/ui/switch_track.png");
			knobTex = reg(mc, "ui_switch_knob", "/assets/originclient/textures/ui/switch_knob.png");
			glowTex = reg(mc, "ui_glow", "/assets/originclient/textures/ui/radial_glow.png");
			ringTex = reg(mc, "ui_ring", "/assets/originclient/textures/ui/ring-0.png");
			logoTex = reg(mc, "ui_logo", "/assets/originclient/textures/ui/origin_logo.png");
			starTex = reg(mc, "ui_star", "/assets/originclient/textures/ui/star.png");
			ok = true;
		} catch (Throwable e) {
			ok = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin UI assets failed to load; using flat fallbacks", e);
		}
	}

	private static JsonObject readJson(String path) throws Exception {
		try (InputStream in = OriginUi.class.getResourceAsStream(path)) {
			return GSON.fromJson(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), JsonObject.class);
		}
	}

	private static ResourceLocation reg(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = OriginUi.class.getResourceAsStream(path)) {
			image = NativeImage.read(in);
		}
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
		DynamicTexture tex = new DynamicTexture(image);
		tex.setFilter(true, false);
		mc.getTextureManager().register(id, tex);
		return id;
	}
}
