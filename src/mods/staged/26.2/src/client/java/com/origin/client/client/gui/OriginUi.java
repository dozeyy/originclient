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
		ensureLoaded();
		if (!ok) {
			g.fill(x, y, x + w, y + h, fill);
			return;
		}
		int cd = Math.min(corner, Math.min(w, h) / 2);
		nine(g, fillTex, x, y, w, h, cd, fill);
		if (((border >>> 24) & 0xFF) > 0) {
			nine(g, borderTex, x, y, w, h, cd, border);
		}
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
		} catch (Exception e) {
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
