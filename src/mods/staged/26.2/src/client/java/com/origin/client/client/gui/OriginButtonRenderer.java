package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.util.Map;
import java.util.WeakHashMap;

import com.origin.client.client.theme.OriginTheme;

// Draws vanilla buttons/sliders/checkboxes in the Origin style: a 9-sliced
// rounded-rect fill + hairline border (baked alpha masks tinted to theme
// colors) and the label in the default Minecraft font. Hover is communicated by
// the border brightening only -- no vertical lift (Will). Eased on wall-clock
// time via OriginTheme. Called from the widget mixins, which cancel
// the vanilla drawing on every screen, so no widgets are added or removed --
// the existing widgets keep their positions, actions, and clicks. Per-widget
// hover animation state is kept in a WeakHashMap keyed by the widget.
//
// 26.2 render port: immediate-mode GuiGraphics -> retained-mode
// GuiGraphicsExtractor. Texture tint is folded into the blit color arg (there is
// no RenderSystem.setShaderColor in 26.2); blend state is per-RenderPipeline.
public final class OriginButtonRenderer {
	private static final Gson GSON = new Gson();

	// White texture tint (draw the mask at full color; the per-call argb carries
	// the real theme color/alpha).
	private static final int WHITE = 0xFFFFFFFF;

	// Frost palette, matched to 1.21.1 (Will): a translucent DARK fill with a
	// near-black hairline border — not the old faint-white fill + light outline,
	// which read as a thick bright ring around every button.
	private static final int FILL_NORMAL = 0x59161616;
	private static final int FILL_HOVER = 0x99303030;
	private static final int BORDER_NORMAL = 0xF00A0A0A;
	// Hover brightens the outline to a MUCH lighter gray (A2) — one shared token
	// so every hovered Origin box reads the same, here and in OriginUi panels.
	private static final int BORDER_HOVER = OriginTheme.STROKE_HOVER;
	private static final int LABEL_COLOR = OriginTheme.TEXT;
	// Disabled (active=false, e.g. Telemetry Data): same shape, clearly dimmed.
	private static final int FILL_DISABLED = 0x40101010;
	private static final int BORDER_DISABLED = 0x99080808;
	private static final int LABEL_DISABLED = OriginTheme.MUTED;
	// 3px angled CUT (bevel) corners, matching 1.21.1's Frost box — not the old 6px
	// rounded texture corner.
	private static final int CORNER_DISPLAY = 3;
	// Short + eased = the website's snappy hover; no per-button glow (the
	// cursor-follow glow in OriginScreenRenderer blooms on hover instead).
	private static final double HOVER_MS = 90.0;

	// Fail-soft master switch, mirroring OriginScreenRenderer: if any Origin
	// widget draw throws (e.g. a GUI API that changed shape in a different
	// game version), widget rendering flips back to vanilla permanently for
	// this session instead of crashing. The widget mixins only ci.cancel()
	// the vanilla draw when these entry points return true.
	private static volatile boolean broken = false;

	private static volatile boolean loaded = false;
	private static boolean assetsOk = false;
	private static int TEX, CORNER;
	private static Identifier fillTex, borderTex;

	// Keyed by widget (buttons, sliders, checkboxes all share hover easing).
	private static final Map<Object, State> STATE = new WeakHashMap<>();

	private static final class State {
		double hover = 0.0;
		long lastNanos = 0L;
	}

	private OriginButtonRenderer() {
	}

	private static boolean fail(Throwable t) {
		broken = true;
		com.origin.client.OriginClient.LOGGER.error(
				"Origin widget rendering failed; falling back to vanilla widgets for this session", t);
		return false;
	}

	/** Origin-styled button. Returns true only if it drew (callers cancel vanilla on true). */
	public static boolean render(GuiGraphicsExtractor guiGraphics, AbstractButton button) {
		if (broken) {
			return false;
		}
		try {
			render0(guiGraphics, button);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void render0(GuiGraphicsExtractor guiGraphics, AbstractButton button) {
		ensureLoaded();
		int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();
		boolean enabled = button.active;
		double hv = hoverEase(button, enabled && button.isHovered());

		// No hover lift (Will): the box stays put; hover reads through the border
		// brightening only.
		int drawY = y;
		double cx = x + w / 2.0;
		double cy = drawY + h / 2.0;

		int fill = enabled ? OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv) : FILL_DISABLED;
		int border = enabled ? OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv) : BORDER_DISABLED;
		int labelColor = enabled ? LABEL_COLOR : LABEL_DISABLED;

		if (!assetsOk) {
			drawFallback(guiGraphics, x, drawY, w, h, fill, border, labelColor, button.getMessage());
			return;
		}

		// One choke point (OriginUi.panel), exactly like 1.21.1 — so buttons are
		// visually identical to the mod-menu / HUD surfaces by construction, and they
		// pick up the fast baked-mask path instead of an 18-blit nine-slice.
		OriginUi.panel(guiGraphics, x, drawY, w, h, CORNER_DISPLAY, fill, border);

		drawLabel(guiGraphics, cx, cy, w, button.getMessage(), labelColor);
	}

	/**
	 * Origin slider: the button shell, a faint fill up to the current value
	 * (reads like the loading bar), a bright accent handle at the value
	 * position, and the label ("FOV: 90") centered on top. Vanilla's
	 * drag/click logic is untouched -- this only redraws, and reads `value`
	 * live each frame, so dragging stays exactly as responsive as vanilla.
	 */
	public static boolean renderSlider(GuiGraphicsExtractor guiGraphics, AbstractSliderButton slider, double value) {
		if (broken) {
			return false;
		}
		try {
			renderSlider0(guiGraphics, slider, value);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void renderSlider0(GuiGraphicsExtractor guiGraphics, AbstractSliderButton slider, double value) {
		ensureLoaded();
		int x = slider.getX(), y = slider.getY(), w = slider.getWidth(), h = slider.getHeight();
		boolean enabled = slider.active;
		double v = Math.max(0.0, Math.min(1.0, value));
		double hv = hoverEase(slider, enabled && slider.isHovered());

		// The shell always uses the RESTING gray-box look (identical to an
		// un-hovered button) -- Will: the slider box must match the other gray
		// boxes, with no bright/white outline even when it's the focused/hovered
		// widget. All hover feedback lives on the handle instead.
		int fill = enabled ? FILL_NORMAL : FILL_DISABLED;
		int border = enabled ? BORDER_NORMAL : BORDER_DISABLED;
		int labelColor = enabled ? LABEL_COLOR : LABEL_DISABLED;

		// Shell -- identical to a resting button (fill only; the border goes on top
		// of the handle below, so the two halves are drawn separately).
		OriginUi.panel(guiGraphics, x, y, w, h, CORNER_DISPLAY, fill, 0);

		// Just the draggable handle: a thin vertical gray bar at the value
		// position -- no horizontal center groove line (Will). Gray, clearly
		// visible on the dark shell, brightening a touch on hover.
		int inset = 6;
		int handleW = 4;
		int handleH = Math.max(6, h - 8);
		int handleY = y + (h - handleH) / 2;
		int travel = Math.max(0, w - 2 * inset - handleW);
		int handleX = x + inset + (int) Math.round(travel * v);
		guiGraphics.fill(handleX, handleY, handleX + handleW, handleY + handleH,
				enabled ? OriginTheme.lerpColor(0xFFB4B4B4, 0xFFD8D8D8, hv) : 0x66808080);

		// Border on top -- resting gray, matching the other boxes.
		OriginUi.panel(guiGraphics, x, y, w, h, CORNER_DISPLAY, 0, border);

		drawLabel(guiGraphics, x + w / 2.0, y + h / 2.0, w, slider.getMessage(), labelColor);
	}

	/**
	 * Origin checkbox: a small rounded shell with an accent inner square when
	 * selected, label to the right in vanilla font. Click handling untouched.
	 */
	public static boolean renderCheckbox(GuiGraphicsExtractor guiGraphics, Checkbox checkbox) {
		if (broken) {
			return false;
		}
		try {
			renderCheckbox0(guiGraphics, checkbox);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void renderCheckbox0(GuiGraphicsExtractor guiGraphics, Checkbox checkbox) {
		ensureLoaded();
		int x = checkbox.getX(), y = checkbox.getY(), h = checkbox.getHeight();
		boolean enabled = checkbox.active;
		double hv = hoverEase(checkbox, enabled && checkbox.isHovered());

		int fill = enabled ? OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv) : FILL_DISABLED;
		int border = enabled ? OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv) : BORDER_DISABLED;
		int labelColor = enabled ? LABEL_COLOR : LABEL_DISABLED;

		int box = h;
		int cd = Math.min(3, box / 3);
		OriginUi.panel(guiGraphics, x, y, box, box, cd, fill, border);

		if (checkbox.selected()) {
			int inset = Math.max(3, box / 5);
			// Gray fill (not stark white), matching the slider knob, still
			// clearly visible against the faint shell; brightens on hover.
			guiGraphics.fill(x + inset, y + inset, x + box - inset, y + box - inset,
					enabled ? OriginTheme.lerpColor(0xFFB4B4B4, 0xFFD8D8D8, hv) : 0x669A9A9A);
		}

		Font font = Minecraft.getInstance().font;
		String text = cleanLabel(checkbox.getMessage().getString());
		guiGraphics.text(font, text, x + box + 5, y + (box - 8) / 2 + 1, labelColor, false);
	}

	/** Shared eased hover progress (0..1) for any widget. */
	private static double hoverEase(Object widget, boolean hovered) {
		State st = STATE.computeIfAbsent(widget, k -> new State());
		long now = System.nanoTime();
		double dtMs = st.lastNanos == 0 ? 0 : (now - st.lastNanos) / 1_000_000.0;
		st.lastNanos = now;
		double target = hovered ? 1.0 : 0.0;
		if (st.hover < target) {
			st.hover = Math.min(target, st.hover + dtMs / HOVER_MS);
		} else if (st.hover > target) {
			st.hover = Math.max(target, st.hover - dtMs / HOVER_MS);
		}
		return OriginTheme.easeOut(st.hover);
	}

	private static void drawLabel(GuiGraphicsExtractor guiGraphics, double cx, double cy, int w, Component message, int labelColor) {
		// One text pipeline for EVERY label (Will: "every text needs to be the
		// same"): the default game font, drawn without shadow. Trailing
		// ellipsis/dots stripped (Will: no dots on "Options...").
		String text = cleanLabel(message.getString());
		Font font = Minecraft.getInstance().font;
		// Clip to the button width so 26.2's compact pause-menu buttons (Feedback,
		// Report Bugs, ...) don't overflow and collide with their neighbours.
		int maxW = w - 6;
		if (maxW > 4 && font.width(text) > maxW) {
			text = font.plainSubstrByWidth(text, maxW - font.width("…")) + "…";
		}
		int tw = font.width(text);
		guiGraphics.text(font, text, (int) (cx - tw / 2.0), (int) (cy - 4), labelColor, false);
	}

	private static String cleanLabel(String raw) {
		String s = raw.replace("…", "").trim();
		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1);
		}
		return s.trim();
	}

	private static void drawFallback(GuiGraphicsExtractor guiGraphics, int x, int y, int w, int h, int fill, int border, int labelColor, Component message) {
		guiGraphics.fill(x, y, x + w, y + h, fill);
		guiGraphics.fill(x, y, x + w, y + 1, border);
		guiGraphics.fill(x, y + h - 1, x + w, y + h, border);
		guiGraphics.fill(x, y, x + 1, y + h, border);
		guiGraphics.fill(x + w - 1, y, x + w, y + h, border);
		Font font = Minecraft.getInstance().font;
		int tw = font.width(message);
		guiGraphics.text(font, message, x + (w - tw) / 2, y + (h - 8) / 2, labelColor, false);
	}

	// 9-slice a baked alpha mask, tinted to `argb`. Corner/edge regions are
	// region-scaled (source c px -> display cd px), so this uses the
	// region-scaling blit overload (u,v, srcW,srcH, dstW,dstH, texW,texH, color).
	private static void nineSlice(GuiGraphicsExtractor g, Identifier tex, int x, int y, int w, int h, int cd, int argb) {
		int c = CORNER;
		int t = TEX;
		int mid = t - 2 * c;
		int mw = w - 2 * cd;
		int mh = h - 2 * cd;
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

	// One region-scaled, tinted blit (source (u,v)+(srcW,srcH) -> dest (x,y)+(dstW,dstH)).
	private static void blit9(GuiGraphicsExtractor g, Identifier tex, int x, int y,
							  int u, int v, int srcW, int srcH, int dstW, int dstH, int t, int argb) {
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, (float) u, (float) v, dstW, dstH, srcW, srcH, t, t, argb);
	}

	// Volatile fast-path: this runs once per widget per frame, so the
	// already-loaded case must avoid acquiring the class monitor.
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
			JsonObject btn;
			try (InputStream in = open("/assets/originclient/textures/ui/buttons.json")) {
				btn = GSON.fromJson(readAll(in), JsonObject.class);
			}
			TEX = btn.get("texSize").getAsInt();
			CORNER = btn.get("corner").getAsInt();
			fillTex = register(mc, "button_fill", "/assets/originclient/textures/ui/button_fill.png");
			borderTex = register(mc, "button_border", "/assets/originclient/textures/ui/button_border.png");
			assetsOk = true;
		} catch (Exception e) {
			assetsOk = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin button assets failed to load; using fallback drawing", e);
		}
	}

	private static String readAll(InputStream in) throws Exception {
		return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
	}

	private static Identifier register(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		Identifier id = Identifier.fromNamespaceAndPath("originclient", name);
		DynamicTexture texture = new DynamicTexture(() -> name, image);
		mc.getTextureManager().register(id, texture);
		return id;
	}

	private static InputStream open(String classpathResource) throws Exception {
		InputStream in = OriginButtonRenderer.class.getResourceAsStream(classpathResource);
		if (in == null) {
			throw new java.io.FileNotFoundException("Missing Origin button asset: " + classpathResource);
		}
		return in;
	}
}
