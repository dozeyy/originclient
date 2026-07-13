package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
public final class OriginButtonRenderer {
	private static final Gson GSON = new Gson();

	private static final int FILL_NORMAL = 0x07FFFFFF;
	private static final int FILL_HOVER = 0x0FFFFFFF;
	private static final int BORDER_NORMAL = 0x1CFFFFFF;
	// Hover brightens the outline to a MUCH lighter gray (A2) — one shared token
	// so every hovered Origin box reads the same, here and in OriginUi panels.
	private static final int BORDER_HOVER = OriginTheme.STROKE_HOVER;
	private static final int LABEL_COLOR = OriginTheme.TEXT;
	// Disabled (active=false, e.g. Telemetry Data): same shape, clearly dimmed.
	private static final int FILL_DISABLED = 0x04FFFFFF;
	private static final int BORDER_DISABLED = 0x10FFFFFF;
	private static final int LABEL_DISABLED = OriginTheme.MUTED;
	private static final int CORNER_DISPLAY = 6;
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
	private static ResourceLocation fillTex, borderTex;

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
	public static boolean render(GuiGraphics guiGraphics, AbstractButton button) {
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

	private static void render0(GuiGraphics guiGraphics, AbstractButton button) {
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

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		int cd = Math.min(CORNER_DISPLAY, Math.min(w, h) / 2);
		shaderColor(fill);
		nineSlice(guiGraphics, fillTex, x, drawY, w, h, cd);
		shaderColor(border);
		nineSlice(guiGraphics, borderTex, x, drawY, w, h, cd);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

		drawLabel(guiGraphics, cx, cy, h, button.getMessage(), labelColor);
	}

	/**
	 * Origin-styled navigation tab (the Game/World/More header on CreateWorld and
	 * friends). TabButton isn't an AbstractButton, so it can't go through
	 * render(): selected tabs read like a resting-hovered button with a bright
	 * bottom accent; unselected tabs are faint with a muted label and brighten on
	 * hover. `key` is the widget instance, used for the shared hover easing.
	 * Returns true only if it drew (callers cancel vanilla on true).
	 */
	public static boolean renderTab(GuiGraphics guiGraphics, Object key, int x, int y, int w, int h,
									Component label, boolean selected, boolean hovered) {
		if (broken) {
			return false;
		}
		try {
			renderTab0(guiGraphics, key, x, y, w, h, label, selected, hovered);
			return true;
		} catch (Throwable t) {
			return fail(t);
		}
	}

	private static void renderTab0(GuiGraphics guiGraphics, Object key, int x, int y, int w, int h,
								   Component label, boolean selected, boolean hovered) {
		ensureLoaded();
		double hv = hoverEase(key, hovered);
		// Selected reads as a lit button; unselected is faint and brightens on hover.
		int fill = selected ? FILL_HOVER : OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv);
		int border = selected ? BORDER_HOVER : OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv);
		int labelColor = selected ? LABEL_COLOR : OriginTheme.lerpColor(OriginTheme.MUTED, OriginTheme.TEXT, hv);

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		int cd = Math.min(CORNER_DISPLAY, Math.min(w, h) / 2);
		if (assetsOk) {
			shaderColor(fill);
			nineSlice(guiGraphics, fillTex, x, y, w, h, cd);
			shaderColor(border);
			nineSlice(guiGraphics, borderTex, x, y, w, h, cd);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		} else {
			drawFallback(guiGraphics, x, y, w, h, fill, border, labelColor, label);
		}
		// Selected accent: a bright underline centered along the bottom edge.
		if (selected) {
			int uw = Math.max(16, Math.min(w - 8, (int) Math.round(w * 0.55)));
			int ux = x + (w - uw) / 2;
			guiGraphics.fill(ux, y + h - 2, ux + uw, y + h - 1, OriginTheme.TEXT);
		}
		drawLabel(guiGraphics, x + w / 2.0, y + h / 2.0, h, label, labelColor);
	}

	/**
	 * Origin slider: the button shell, a faint fill up to the current value
	 * (reads like the loading bar), a bright accent handle at the value
	 * position, and the label ("FOV: 90") centered on top. Vanilla's
	 * drag/click logic is untouched -- this only redraws, and reads `value`
	 * live each frame, so dragging stays exactly as responsive as vanilla.
	 */
	public static boolean renderSlider(GuiGraphics guiGraphics, AbstractSliderButton slider, double value) {
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

	private static void renderSlider0(GuiGraphics guiGraphics, AbstractSliderButton slider, double value) {
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

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		int cd = Math.min(CORNER_DISPLAY, Math.min(w, h) / 2);

		// Shell -- identical to a resting button.
		if (assetsOk) {
			shaderColor(fill);
			nineSlice(guiGraphics, fillTex, x, y, w, h, cd);
		} else {
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
			guiGraphics.fill(x, y, x + w, y + h, fill);
		}
		// CRITICAL: reset the shader tint before the handle's guiGraphics.fill(),
		// or it gets multiplied by the shell's faint tint (near-invisible).
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

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
		// CRITICAL: fill() above flushes through a render type whose teardown
		// DISABLES blending -- without re-enabling it, the border texture's
		// alpha is ignored and it draws as a fully-opaque THICK WHITE RING
		// (the exact bug Will saw on every slider box, and only on sliders,
		// because only sliders fill() between the two texture passes).
		if (assetsOk) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			shaderColor(border);
			nineSlice(guiGraphics, borderTex, x, y, w, h, cd);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		}

		drawLabel(guiGraphics, x + w / 2.0, y + h / 2.0, h, slider.getMessage(), labelColor);
	}

	/**
	 * Origin checkbox: a small rounded shell with an accent inner square when
	 * selected, label to the right in vanilla font. Click handling untouched.
	 */
	public static boolean renderCheckbox(GuiGraphics guiGraphics, Checkbox checkbox) {
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

	private static void renderCheckbox0(GuiGraphics guiGraphics, Checkbox checkbox) {
		ensureLoaded();
		int x = checkbox.getX(), y = checkbox.getY(), h = checkbox.getHeight();
		boolean enabled = checkbox.active;
		double hv = hoverEase(checkbox, enabled && checkbox.isHovered());

		int fill = enabled ? OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv) : FILL_DISABLED;
		int border = enabled ? OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv) : BORDER_DISABLED;
		int labelColor = enabled ? LABEL_COLOR : LABEL_DISABLED;

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		int box = h;
		int cd = Math.min(4, box / 3);
		if (assetsOk) {
			shaderColor(fill);
			nineSlice(guiGraphics, fillTex, x, y, box, box, cd);
			shaderColor(border);
			nineSlice(guiGraphics, borderTex, x, y, box, box, cd);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		} else {
			guiGraphics.fill(x, y, x + box, y + box, fill);
		}

		if (checkbox.selected()) {
			int inset = Math.max(3, box / 5);
			// Gray fill (not stark white), matching the slider knob, still
			// clearly visible against the faint shell; brightens on hover.
			guiGraphics.fill(x + inset, y + inset, x + box - inset, y + box - inset,
					enabled ? OriginTheme.lerpColor(0xFFB4B4B4, 0xFFD8D8D8, hv) : 0x669A9A9A);
		}

		Font font = Minecraft.getInstance().font;
		String text = cleanLabel(checkbox.getMessage().getString());
		guiGraphics.drawString(font, text, x + box + 5, y + (box - 8) / 2 + 1, labelColor, false);
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

	private static void drawLabel(GuiGraphics guiGraphics, double cx, double cy, int h, Component message, int labelColor) {
		// One text pipeline for EVERY label (Will: "every text needs to be the
		// same"): the default game font, drawn without shadow. Baked label
		// textures and the Inter TTF override were both tried and retired --
		// Will settled on default Minecraft text everywhere, so consistency
		// comes free. Trailing ellipsis/dots stripped (Will: no dots on
		// "Options...").
		String text = cleanLabel(message.getString());
		Font font = Minecraft.getInstance().font;
		int tw = font.width(text);
		guiGraphics.drawString(font, text, (int) (cx - tw / 2.0), (int) (cy - 4), labelColor, false);
	}

	private static String cleanLabel(String raw) {
		String s = raw.replace("…", "").trim();
		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1);
		}
		return s.trim();
	}

	private static void drawFallback(GuiGraphics guiGraphics, int x, int y, int w, int h, int fill, int border, int labelColor, Component message) {
		guiGraphics.fill(x, y, x + w, y + h, fill);
		guiGraphics.fill(x, y, x + w, y + 1, border);
		guiGraphics.fill(x, y + h - 1, x + w, y + h, border);
		guiGraphics.fill(x, y, x + 1, y + h, border);
		guiGraphics.fill(x + w - 1, y, x + w, y + h, border);
		Font font = Minecraft.getInstance().font;
		int tw = font.width(message);
		guiGraphics.drawString(font, message, x + (w - tw) / 2, y + (h - 8) / 2, labelColor, false);
	}

	private static void nineSlice(GuiGraphics g, ResourceLocation tex, int x, int y, int w, int h, int cd) {
		int c = CORNER;
		int t = TEX;
		int mid = t - 2 * c;
		int mw = w - 2 * cd;
		int mh = h - 2 * cd;
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

	private static void shaderColor(int argb) {
		float a = ((argb >>> 24) & 0xFF) / 255f;
		float r = ((argb >> 16) & 0xFF) / 255f;
		float g = ((argb >> 8) & 0xFF) / 255f;
		float b = (argb & 0xFF) / 255f;
		RenderSystem.setShaderColor(r, g, b, a);
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

	private static ResourceLocation register(Minecraft mc, String name, String path) throws Exception {
		NativeImage image;
		try (InputStream in = open(path)) {
			image = NativeImage.read(in);
		}
		ResourceLocation id = new ResourceLocation("originclient", name);
		DynamicTexture texture = new DynamicTexture(image);
		texture.setFilter(true, false);
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
