package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.theme.OriginTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

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

	private static ResourceLocation fillTex, borderTex, trackTex, knobTex, glowTex, ringTex, logoTex;
	private static int panelTexSize = 96, panelCorner = 24;

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
	 * A surface: flat fill plus a 1px border. Square corners, hard edges.
	 *
	 * This is the whole pixel-grid commit in one place. It used to 9-slice baked
	 * rounded-corner masks through GL_LINEAR, which is why every Origin surface
	 * read as a web panel dropped into Minecraft. Every surface in the mod menu,
	 * HUD editor, chips, tooltips and switches draws through here, so squaring it
	 * squares all of them at once and keeps them consistent by construction.
	 *
	 * `corner` is kept in the signature but deliberately ignored -- callers pass
	 * radii that no longer mean anything, and threading a removal through every
	 * call site would be churn for no gain. Two fills beat nine blits, too.
	 */
	public static void panel(GuiGraphics g, int x, int y, int w, int h, int corner, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		g.fill(x, y, x + w, y + h, fill);
		if (((border >>> 24) & 0xFF) > 0) {
			g.fill(x, y, x + w, y + 1, border);                 // top
			g.fill(x, y + h - 1, x + w, y + h, border);         // bottom
			g.fill(x, y + 1, x + 1, y + h - 1, border);         // left
			g.fill(x + w - 1, y + 1, x + w, y + h - 1, border); // right
		}
	}

	/**
	 * The BUTTON shape (Will, 2026-07-21): a small angled corner CUT — a 45°
	 * chamfer of `cut` px at each corner. Not a full round, not a plain right
	 * angle. Rows near the top/bottom edge inset linearly, which reads as a
	 * crisp diagonal notch; the 1px border traces the edges including the
	 * diagonals. Every button-like surface (mod menu buttons/cards/chips, the
	 * MODS button, the restyled vanilla widgets) draws through here so the cut
	 * stays consistent by construction.
	 */
	public static void bevelPanel(GuiGraphics g, int x, int y, int w, int h, int cut, int fill, int border) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int c = Math.max(0, Math.min(cut, Math.min(w, h) / 2 - 1));
		for (int i = 0; i < h; i++) {
			int d = Math.min(i, h - 1 - i);
			int inset = d < c ? c - d : 0;
			g.fill(x + inset, y + i, x + w - inset, y + i + 1, fill);
		}
		if (((border >>> 24) & 0xFF) == 0) {
			return;
		}
		for (int i = 0; i < h; i++) {
			int d = Math.min(i, h - 1 - i);
			int inset = d < c ? c - d : 0;
			g.fill(x + inset, y + i, x + inset + 1, y + i + 1, border);          // left edge / diagonal
			g.fill(x + w - inset - 1, y + i, x + w - inset, y + i + 1, border);  // right edge / diagonal
		}
		g.fill(x + c, y, x + w - c, y + 1, border);                              // top run
		g.fill(x + c, y + h - 1, x + w - c, y + h, border);                      // bottom run
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
			RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
			g.blit(ringTex, 0, 0, 0, 0, 768, 768, 768, 768);
			pose.popPose();
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		g.blit(logoTex, (int) (cx - size / 2.0), (int) (cy - size / 2.0), size, size, 0f, 0f, 256, 256, 256, 256);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
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
			g.blit(knobTex, kx - kd / 2, cy - kd / 2, kd, kd, 0f, 0f, 72, 72, 72, 72);
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
