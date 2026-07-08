package com.origin.client.client.gui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import com.origin.client.client.theme.OriginTheme;

// Draws a vanilla button in the Origin style: a 9-sliced rounded-rect fill +
// hairline border (baked alpha masks tinted to theme colors), a white Inter
// label, a soft glow that blooms on hover, and a couple-px hover lift -- eased
// on wall-clock time via OriginTheme, matching the website's button feel
// (DESIGN_SYSTEM.md §3/§6d). Called from AbstractButtonMixin (which cancels the
// vanilla button rendering on the title screen), so no widgets are added or
// removed -- the existing buttons keep their positions, actions, and clicks.
// Per-button hover animation state is kept in a WeakHashMap keyed by the button.
public final class OriginButtonRenderer {
	private static final Gson GSON = new Gson();

	private static final int FILL_NORMAL = 0x07FFFFFF;
	private static final int FILL_HOVER = 0x0FFFFFFF;
	private static final int BORDER_NORMAL = 0x1CFFFFFF;
	private static final int BORDER_HOVER = 0x4DFFFFFF;
	private static final int LABEL_COLOR = OriginTheme.TEXT;
	private static final int CORNER_DISPLAY = 6;
	private static final double LIFT_PX = 2.0;
	// Short + eased = the website's snappy hover; no per-button glow (the
	// cursor-follow glow in OriginScreenRenderer blooms on hover instead).
	private static final double HOVER_MS = 90.0;

	private static boolean loaded = false;
	private static boolean assetsOk = false;
	private static int TEX, CORNER, cellHeight;
	private static ResourceLocation fillTex, borderTex;
	private static final Map<String, LabelInfo> LABELS = new HashMap<>();

	private static final Map<AbstractButton, State> STATE = new WeakHashMap<>();

	private record LabelInfo(ResourceLocation tex, int width) {
	}

	private static final class State {
		double hover = 0.0;
		long lastNanos = 0L;
	}

	private OriginButtonRenderer() {
	}

	public static void render(GuiGraphics guiGraphics, AbstractButton button) {
		ensureLoaded();
		int x = button.getX(), y = button.getY(), w = button.getWidth(), h = button.getHeight();

		State st = STATE.computeIfAbsent(button, k -> new State());
		long now = System.nanoTime();
		double dtMs = st.lastNanos == 0 ? 0 : (now - st.lastNanos) / 1_000_000.0;
		st.lastNanos = now;
		double target = button.isHovered() ? 1.0 : 0.0;
		if (st.hover < target) {
			st.hover = Math.min(target, st.hover + dtMs / HOVER_MS);
		} else if (st.hover > target) {
			st.hover = Math.max(target, st.hover - dtMs / HOVER_MS);
		}
		double hv = OriginTheme.easeOut(st.hover);

		int drawY = (int) Math.round(y - LIFT_PX * hv);
		double cx = x + w / 2.0;
		double cy = drawY + h / 2.0;

		if (!assetsOk) {
			drawFallback(guiGraphics, x, drawY, w, h, hv, button.getMessage());
			return;
		}

		RenderSystem.enableBlend();

		int cd = Math.min(CORNER_DISPLAY, Math.min(w, h) / 2);
		shaderColor(OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv));
		nineSlice(guiGraphics, fillTex, x, drawY, w, h, cd);
		shaderColor(OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv));
		nineSlice(guiGraphics, borderTex, x, drawY, w, h, cd);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

		drawLabel(guiGraphics, cx, cy, h, button.getMessage());
	}

	private static void drawLabel(GuiGraphics guiGraphics, double cx, double cy, int h, Component message) {
		// Vanilla labels can carry a trailing ellipsis ("Options...") -- strip
		// it both for the baked-texture lookup and for what gets displayed
		// (Will: no dots), falling back to vanilla font only if the cleaned
		// string has no baked label at all.
		String text = cleanLabel(message.getString());
		LabelInfo li = LABELS.get(text);
		if (li != null) {
			double scale = (h * 0.62) / cellHeight;
			double dw = li.width() * scale;
			double dh = cellHeight * scale;
			shaderColor(LABEL_COLOR);
			guiGraphics.blit(li.tex(), (int) (cx - dw / 2.0), (int) (cy - dh / 2.0), (int) dw, (int) dh,
					0f, 0f, li.width(), cellHeight, li.width(), cellHeight);
			RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		} else {
			Font font = Minecraft.getInstance().font;
			int tw = font.width(text);
			guiGraphics.drawString(font, text, (int) (cx - tw / 2.0), (int) (cy - 4), LABEL_COLOR, false);
		}
	}

	private static String cleanLabel(String raw) {
		String s = raw.replace("…", "").trim();
		while (s.endsWith(".")) {
			s = s.substring(0, s.length() - 1);
		}
		return s.trim();
	}

	private static void drawFallback(GuiGraphics guiGraphics, int x, int y, int w, int h, double hv, Component message) {
		guiGraphics.fill(x, y, x + w, y + h, OriginTheme.lerpColor(FILL_NORMAL, FILL_HOVER, hv));
		int border = OriginTheme.lerpColor(BORDER_NORMAL, BORDER_HOVER, hv);
		guiGraphics.fill(x, y, x + w, y + 1, border);
		guiGraphics.fill(x, y + h - 1, x + w, y + h, border);
		guiGraphics.fill(x, y, x + 1, y + h, border);
		guiGraphics.fill(x + w - 1, y, x + w, y + h, border);
		Font font = Minecraft.getInstance().font;
		int tw = font.width(message);
		guiGraphics.drawString(font, message, x + (w - tw) / 2, y + (h - 8) / 2, LABEL_COLOR, false);
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

	private static synchronized void ensureLoaded() {
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

			JsonObject labelsRoot;
			try (InputStream in = open("/assets/originclient/textures/ui/labels.json")) {
				labelsRoot = GSON.fromJson(readAll(in), JsonObject.class);
			}
			cellHeight = labelsRoot.get("cellHeight").getAsInt();
			JsonObject map = labelsRoot.getAsJsonObject("labels");
			for (String key : map.keySet()) {
				JsonObject l = map.getAsJsonObject(key);
				String file = l.get("file").getAsString();
				ResourceLocation id = register(mc, file.replace(".png", ""), "/assets/originclient/textures/ui/" + file);
				LABELS.put(key, new LabelInfo(id, l.get("width").getAsInt()));
			}
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
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("originclient", name);
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
