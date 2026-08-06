package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// The premium drawing kit shared by the mod menu and HUD editor: baked
// high-res assets (rounded panels, Apple-style switch, 96px icon atlas,
// radial glow, brand rings). 26.2 render port: immediate-mode GuiGraphics ->
// retained-mode GuiGraphicsExtractor; texture tint is folded into the blit color
// arg (no RenderSystem.setShaderColor), blend is per-RenderPipeline, and the 2D
// pose() is org.joml.Matrix3x2fStack.
public final class OriginUi {
	private static final Gson GSON = new Gson();
	private static volatile boolean loaded = false;
	private static boolean ok = false;

	// Untinted draw = full white; alpha-only tint packs into the color arg.
	private static final int WHITE = 0xFFFFFFFF;

	private static Identifier fillTex, borderTex, trackTex, knobTex, iconsTex, glowTex, ringTex, logoTex;
	private static int panelTexSize = 96, panelCorner = 24;
	private static int iconCell = 96, atlasW = 576, atlasH = 384;
	private static final Map<String, int[]> ICONS = new HashMap<>();

	// eased animation state keyed by arbitrary id (switch knobs, hovers)
	private static final Map<String, double[]> ANIM = new HashMap<>(); // {value, lastNanos, target}

	private OriginUi() {
	}

	public static boolean ready() {
		ensureLoaded();
		return ok;
	}

	/** Pack an alpha (0..1) onto white for an alpha-only texture tint. */
	private static int alpha(float a) {
		int ai = Math.max(0, Math.min(255, Math.round(a * 255f)));
		return (ai << 24) | 0x00FFFFFF;
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

	/** Rounded panel: 9-sliced baked masks, fill + hairline border. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int corner, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int r = Math.max(0, Math.min(corner, Math.min(w, h) / 2));
		// SMALL panels (grid cells, tabs, switches, chips) draw as TWO blits into a
		// baked rounded-box mask — one tinted pass for the fill, one for the border —
		// instead of the 9-18 blit nine-slice. This is the fix for the Item Size /
		// mod-menu lag (Will): the cost is per PANEL DRAWN, and the Item Size "All"
		// grid draws ~50 of them a frame. Baked at PHYSICAL resolution so corners stay
		// crisp at any GUI scale. Matches 1.21.11.
		if (w <= MASK_MAX && h <= MASK_MAX) {
			Identifier box = boxMask(w, h, r);
			if (box != null) {
				int s = guiScale();
				int pw = w * s, ph = h * s;
				if (((fill >>> 24) & 0xFF) > 0) {
					g.blit(RenderPipelines.GUI_TEXTURED, box, x, y, 0f, 0f, w, h, pw, ph, pw, ph * 2, fill);
				}
				if (((border >>> 24) & 0xFF) > 0) {
					g.blit(RenderPipelines.GUI_TEXTURED, box, x, y, 0f, (float) ph, w, h, pw, ph, pw, ph * 2, border);
				}
				return;
			}
		}
		// Larger panels (the menu backdrop etc.) keep the nine-slice texture path.
		ensureLoaded();
		if (!ok) {
			g.fill(x, y, x + w, y + h, fill);
			return;
		}
		int cd = Math.min(r, Math.min(w, h) / 2);
		nine(g, fillTex, x, y, w, h, cd, fill);
		if (((border >>> 24) & 0xFF) > 0) {
			nine(g, borderTex, x, y, w, h, cd, border);
		}
	}

	// ---- UI glyphs (close / edit / chevron / star) ----
	// 1.21.x draws these from its SDF icon atlas; 26.2's atlas only carries mod-card
	// icons, so they're drawn as plain vector geometry here — no assets to load, and
	// it keeps the thin outlined line-icon look the design calls for.

	/** Diagonal (or any) 1px-ish line between two points, `t` px thick. */
	private static void stroke(GuiGraphicsExtractor g, double x0, double y0, double x1, double y1, int t, int argb) {
		double dx = x1 - x0, dy = y1 - y0;
		int steps = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
		if (steps <= 0) {
			return;
		}
		for (int i = 0; i <= steps; i++) {
			double f = i / (double) steps;
			int px = (int) Math.round(x0 + dx * f);
			int py = (int) Math.round(y0 + dy * f);
			g.fill(px, py, px + t, py + t, argb);
		}
	}

	/** Close "×" — two diagonals across the box. */
	public static void iconClose(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		int p = Math.max(2, size / 5), t = Math.max(1, size / 8);
		stroke(g, x + p, y + p, x + size - p - t, y + size - p - t, t, color);
		stroke(g, x + size - p - t, y + p, x + p, y + size - p - t, t, color);
	}

	/** Edit "pencil" — a diagonal shaft with a short tip stroke. */
	public static void iconEdit(GuiGraphicsExtractor g, int x, int y, int size, int color) {
		int p = Math.max(2, size / 5), t = Math.max(1, size / 8);
		stroke(g, x + p, y + size - p - t, x + size - p - t, y + p, t, color);
		stroke(g, x + p, y + size - p - t, x + p + t * 2, y + size - p - t, t, color);
	}

	/** Chevron "‹"/"›" — two strokes meeting at a point. */
	public static void iconChevron(GuiGraphicsExtractor g, int x, int y, int size, int color, boolean left) {
		int p = Math.max(2, size / 4), t = Math.max(1, size / 8);
		int midY = y + size / 2;
		int tipX = left ? x + p : x + size - p - t;
		int endX = left ? x + size - p - t : x + p;
		stroke(g, tipX, midY, endX, y + p, t, color);
		stroke(g, tipX, midY, endX, y + size - p - t, t, color);
	}

	/** Star (favourite marker) — a compact 4-point asterisk. */
	public static void star(GuiGraphicsExtractor g, int x, int y, int size, int argb) {
		int t = Math.max(1, size / 8);
		int cx = x + size / 2, cy = y + size / 2, r = Math.max(2, size / 2 - t);
		stroke(g, cx, cy - r, cx, cy + r - t, t, argb);
		stroke(g, cx - r, cy, cx + r - t, cy, t, argb);
		int d = (int) (r * 0.7);
		stroke(g, cx - d, cy - d, cx + d - t, cy + d - t, t, argb);
		stroke(g, cx + d - t, cy - d, cx - d, cy + d - t, t, argb);
	}

	// ---- baked rounded-box masks (small-panel fast path, ported from 1.21.11) ----
	private static final int MASK_MAX = 96;
	private static final int MASK_CACHE_MAX = 32;

	/** (w,h,r,guiScale) -> one texture: fill mask on the top half, 1px border ring on
	 *  the bottom half, so a panel is two blits into the same texture. LRU-capped. */
	private static final java.util.LinkedHashMap<Long, Identifier> BOX_MASKS =
			new java.util.LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(java.util.Map.Entry<Long, Identifier> eldest) {
					if (size() <= MASK_CACHE_MAX) {
						return false;
					}
					if (eldest.getValue() != null) {
						try {
							net.minecraft.client.Minecraft.getInstance().getTextureManager().release(eldest.getValue());
						} catch (Throwable ignored) {
						}
					}
					return true;
				}
			};

	// PHYSICAL pixels per GUI pixel. Masks bake at physical res and blit back to GUI
	// size so the anti-aliasing is per SCREEN pixel — crisp corners at any GUI scale.
	private static int guiScale() {
		try {
			return Math.max(1, Math.min(8, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
		} catch (Throwable t) {
			return 1;
		}
	}

	private static Identifier boxMask(int w, int h, int r) {
		int s = guiScale();
		long key = ((long) w << 44) | ((long) h << 20) | ((long) r << 4) | s;
		if (BOX_MASKS.containsKey(key)) {
			return BOX_MASKS.get(key);
		}
		Identifier id = null;
		try {
			int pw = w * s, ph = h * s, pr = r * s;
			NativeImage img = new NativeImage(NativeImage.Format.RGBA, pw, ph * 2, false);
			for (int py = 0; py < ph; py++) {
				for (int px = 0; px < pw; px++) {
					double cov = boxCoverage(px, py, pw, ph, pr, 0);
					double ring = boxCoverage(px, py, pw, ph, pr, s);
					img.setPixel(px, py, alphaWhite(cov));
					img.setPixel(px, py + ph, alphaWhite(ring));
				}
			}
			String name = "origin_box_" + w + "x" + h + "_" + r + "_s" + s;
			id = Identifier.fromNamespaceAndPath("originclient", "textures/ui/" + name);
			net.minecraft.client.Minecraft.getInstance().getTextureManager()
					.register(id, new DynamicTexture(() -> name, img));
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Origin: box mask " + w + "x" + h + " r=" + r + " failed to bake", t);
		}
		BOX_MASKS.put(key, id);
		return id;
	}

	// Exact rounded-box signed-distance coverage; `inset` > 0 returns the border ring.
	private static double boxCoverage(int px, int py, int w, int h, int r, int inset) {
		double hw = w / 2.0, hh = h / 2.0;
		double qx = Math.abs(px + 0.5 - hw) - (hw - r);
		double qy = Math.abs(py + 0.5 - hh) - (hh - r);
		double outX = Math.max(qx, 0), outY = Math.max(qy, 0);
		double d = Math.sqrt(outX * outX + outY * outY) + Math.min(Math.max(qx, qy), 0) - r;
		double outer = clamp01(0.5 - d);
		if (inset <= 0) {
			return outer;
		}
		return outer - clamp01(0.5 - (d + inset));
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	private static int alphaWhite(double cov) {
		int a = cov <= 0.001 ? 0 : (int) Math.round(255 * Math.min(1.0, cov));
		return (a << 24) | 0xFFFFFF;
	}

	/**
	 * Rounded-box toggle (C4): a rectangular track with curved corners, a knob
	 * that slides LEFT = off / RIGHT = on, green when on and red when off. Built
	 * from the shared rounded-rect masks so it stays crisp at any size. Same
	 * signature/geometry as before (wDisp x wDisp*8/15) so existing layouts and
	 * hit-tests are unchanged. Returns knob progress 0..1.
	 */
	public static float switchAt(GuiGraphicsExtractor g, String id, int x, int y, int wDisp, boolean on, boolean enabled) {
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

	/** Hi-res icon from the baked atlas, tinted. */
	public static void icon(GuiGraphicsExtractor g, String name, int x, int y, int size, int argb) {
		// Real Minecraft item icons (the same set every 1.21.x version uses) for any id
		// ModIcons knows — mod cards, the settings tabs, and "@search". Only names it
		// doesn't cover fall through to the legacy vector atlas below.
		if (ModIcons.has(name)) {
			ModIcons.draw(g, name, x, y, size, ((argb >>> 24) & 0xFF) / 255f);
			return;
		}
		ensureLoaded();
		int[] uv = ok ? ICONS.get(name) : null;
		if (uv == null) {
			g.fill(x + 2, y + 2, x + size - 2, y + size - 2, argb);
			return;
		}
		// region iconCell x iconCell at (uv) -> dest size x size, tinted argb.
		g.blit(RenderPipelines.GUI_TEXTURED, iconsTex, x, y, (float) uv[0], (float) uv[1],
				size, size, iconCell, iconCell, atlasW, atlasH, argb);
	}

	/** Soft radial glow centered at (cx,cy). */
	public static void glow(GuiGraphicsExtractor g, double cx, double cy, int diameter, float alpha) {
		ensureLoaded();
		if (!ok) {
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, glowTex, (int) (cx - diameter / 2.0), (int) (cy - diameter / 2.0),
				0f, 0f, diameter, diameter, 512, 512, 512, 512, alpha(alpha));
	}

	/** The 3-ring Origin mark (brand geometry: one ellipse at 0/60/120 deg). */
	public static void mark(GuiGraphicsExtractor g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok) {
			return;
		}
		Matrix3x2fStack pose = g.pose();
		for (int i = 0; i < 3; i++) {
			pose.pushMatrix();
			pose.translate((float) cx, (float) cy);
			pose.rotate((float) Math.toRadians(i * 60.0));
			float s = size / 768f * 1.1f;
			pose.scale(s, s);
			pose.translate(-384f, -384f);
			g.blit(RenderPipelines.GUI_TEXTURED, ringTex, 0, 0, 0f, 0f, 768, 768, 768, 768, alpha(alpha));
			pose.popMatrix();
		}
	}

	/** The exact brand mark (baked from the website nav-mark geometry — three
	 *  nested ellipses at 0/60/120°), drawn smooth at any size. */
	public static void logo(GuiGraphicsExtractor g, double cx, double cy, int size, float alpha) {
		ensureLoaded();
		if (!ok || logoTex == null) {
			mark(g, cx, cy, size, alpha); // fallback to the procedural rings
			return;
		}
		g.blit(RenderPipelines.GUI_TEXTURED, logoTex, (int) (cx - size / 2.0), (int) (cy - size / 2.0),
				0f, 0f, size, size, 256, 256, 256, 256, alpha(alpha));
	}

	/** Rounded pill slider: track + fill + a ball knob that stays vertically
	 *  centered and sits flush at both ends (its travel is inset by the knob
	 *  radius). `y` is the pill top; value 0..1. Returns the knob center x. */
	public static int slider(GuiGraphicsExtractor g, int x, int y, int w, double value, boolean active) {
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
			g.blit(RenderPipelines.GUI_TEXTURED, knobTex, kx - kd / 2, cy - kd / 2, 0f, 0f, kd, kd, 72, 72, 72, 72, WHITE);
		} else {
			g.fill(kx - kd / 2, cy - kd / 2, kx + kd / 2, cy + kd / 2, 0xFFE8E8E8);
		}
		return kx;
	}

	// ---- internals ----

	// 9-slice a baked mask tinted to `argb`, region-scaled corner/edge/center.
	private static void nine(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, int cd, int argb) {
		int c = panelCorner, t = panelTexSize, mid = t - 2 * c, mw = w - 2 * cd, mh = h - 2 * cd;
		blit9(g, tex, x, y, 0, 0, c, c, cd, cd, t, argb);
		blit9(g, tex, x + w - cd, y, t - c, 0, c, c, cd, cd, t, argb);
		blit9(g, tex, x, y + h - cd, 0, t - c, c, c, cd, cd, t, argb);
		blit9(g, tex, x + w - cd, y + h - cd, t - c, t - c, c, c, cd, cd, t, argb);
		if (mw > 0) {
			blit9(g, tex, x + cd, y, c, 0, mid, c, mw, cd, t, argb);
			blit9(g, tex, x + cd, y + h - cd, c, t - c, mid, c, mw, cd, t, argb);
		}
		if (mh > 0) {
			blit9(g, tex, x, y + cd, 0, c, c, mid, cd, mh, t, argb);
			blit9(g, tex, x + w - cd, y + cd, t - c, c, c, mid, cd, mh, t, argb);
		}
		if (mw > 0 && mh > 0) {
			blit9(g, tex, x + cd, y + cd, c, c, mid, mid, mw, mh, t, argb);
		}
	}

	private static void blit9(GuiGraphicsExtractor g, Identifier tex, int x, int y,
							  int u, int v, int srcW, int srcH, int dstW, int dstH, int t, int argb) {
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, (float) u, (float) v, dstW, dstH, srcW, srcH, t, t, argb);
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
			JsonObject icons = readJson("/assets/originclient/textures/ui/mod_icons.json");
			iconCell = icons.get("cell").getAsInt();
			JsonObject list = icons.getAsJsonObject("icons");
			for (String k : list.keySet()) {
				JsonObject o = list.getAsJsonObject(k);
				ICONS.put(k, new int[]{o.get("x").getAsInt(), o.get("y").getAsInt()});
			}
			iconsTex = reg(mc, "ui_mod_icons", "/assets/originclient/textures/ui/mod_icons.png");
			try (InputStream in = OriginUi.class.getResourceAsStream("/assets/originclient/textures/ui/mod_icons.png")) {
				NativeImage img = NativeImage.read(in);
				atlasW = img.getWidth();
				atlasH = img.getHeight();
				img.close();
			}
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

	private static Identifier reg(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = OriginUi.class.getResourceAsStream(path)) {
			image = NativeImage.read(in);
		}
		Identifier id = Identifier.fromNamespaceAndPath("originclient", name);
		DynamicTexture tex = new DynamicTexture(() -> name, image);
		mc.getTextureManager().register(id, tex);
		return id;
	}
}
