package com.origin.client.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.util.ArrayList;
import java.util.List;

// The drawing shim every Origin surface draws through -- same public shape as
// 1.16.5's Gfx (which itself mirrors 1.20's GuiGraphics), so the render core
// ports by a type swap. Every era difference is centralized HERE.
//
// 1.8.9 is FIXED-FUNCTION GL with no matrix objects: the "pose" is the GL
// model-view stack itself (see Pose), so every draw below simply emits vertices
// and the current GL matrix places them. Era notes:
//  - fill / fillGradient: our own POSITION_COLOR quads (vertex colours, so the
//    global GL colour is never consumed). After a vertex-colour draw the GL
//    "current colour" is undefined by spec while GlStateManager still caches the
//    old value, so resetColor() + white puts cache and GL back in agreement --
//    otherwise the next untinted blit could come out tinted.
//  - ALPHA TEST: 1.8.9 draws the GUI with the alpha test on at GREATER 0.1 (as
//    1.16.5 did), which throws away every faint pixel -- software-AA corners,
//    glows, hover washes, panels mid-fade. Each Gfx draw runs with the cut-off
//    at 0 and puts vanilla's 0.1 back afterwards (1.16.5's softAlpha lesson).
//  - blit: TextureManager.bindTexture + Gui's static custom-size blits
//    (drawModalRectWithCustomSizedTexture / drawScaledCustomSizeModalRect);
//    tints are the global GL colour (GlStateManager.color), as on 1.16.5.
//  - text: FontRenderer draws immediately with the GLOBAL colour and leaves it
//    set; Gfx turns blending on for it (translucent text) and resets the colour
//    to white afterwards so text never tints the next draw. IChatComponent
//    overloads draw getFormattedText().
//  - scissor: GL11 directly, GUI rect -> window px with ScaledResolution's
//    factor and a bottom-left origin (the same math later versions use).
//  - items: RenderItem.renderItemAndEffectIntoGUI / renderItemOverlays under
//    GUI item lighting; they draw under the current GL matrix, so a scaled HUD
//    editor pose applies with no extra work. RenderItem leaves lighting on and
//    the alpha test off; both are put back.
// Construct one per draw boundary (`new Gfx()` in drawScreen / a Forge render
// event); it is stateless beyond its Pose, so it is free to create per frame.
public final class Gfx {
	private final Pose pose;

	public Gfx(Pose pose) {
		this.pose = pose == null ? new Pose() : pose;
	}

	/** 1.8.9: vanilla hands no matrix object to draw paths, so most callers start a fresh face. */
	public Gfx() {
		this(new Pose());
	}

	public Pose pose() {
		return pose;
	}

	public int guiWidth() {
		return new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth();
	}

	public int guiHeight() {
		return new ScaledResolution(Minecraft.getMinecraft()).getScaledHeight();
	}

	// ---- 1.8.9 helpers for code that asked the Window (no Window object here) ----

	/** Minecraft's GUI scale factor (window px per GUI px): Window.getGuiScale() on later versions. */
	public static int guiScale() {
		return Math.max(1, new ScaledResolution(Minecraft.getMinecraft()).getScaleFactor());
	}

	/** Framebuffer width in window px: Window.getWidth() on later versions. */
	public static int windowWidth() {
		return Math.max(1, Minecraft.getMinecraft().displayWidth);
	}

	/** Framebuffer height in window px: Window.getHeight() on later versions. */
	public static int windowHeight() {
		return Math.max(1, Minecraft.getMinecraft().displayHeight);
	}

	/** RenderSystem.enableBlend() + defaultBlendFunc() in one call. */
	public static void defaultBlend() {
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
	}

	/** RenderSystem.color4f from one ARGB int. */
	public static void color(int argb) {
		GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
				(argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
	}

	/** Back to opaque white with GlStateManager's colour cache re-synced (see the class comment). */
	public static void resetColor() {
		GlStateManager.resetColor();
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	/** Alpha test cut-off at 0: only fully transparent fragments dropped. */
	public static void softAlpha() {
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.0F);
	}

	/** Vanilla's GUI alpha test (GREATER 0.1) -- what every Gfx draw restores. */
	public static void defaultAlpha() {
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
	}

	/** Binds a texture (TextureManager.bindTexture; 1.16.5's TextureManager.bind). */
	public static void bind(ResourceLocation tex) {
		Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
	}

	/**
	 * NativeImage + DynamicTexture + register, the 1.8.9 way: uploads `img` as a
	 * DynamicTexture under `id` and, when `linear`, switches it to GL_LINEAR
	 * (1.16.5's tex.setFilter(true, false)). DynamicTexture uploads NEAREST and
	 * only re-uploads on updateDynamicTexture(), so the filter set here sticks.
	 * Returns the texture (for later pixel updates) or null on failure.
	 */
	public static net.minecraft.client.renderer.texture.DynamicTexture registerTexture(
			ResourceLocation id, java.awt.image.BufferedImage img, boolean linear) {
		return registerTexture(id, img, linear, false);
	}

	/**
	 * As above; `mipmap` also builds a mip chain (GL_LINEAR_MIPMAP_LINEAR) for big
	 * baked art drawn small (the 192 px icon cells at 14 px, the 512 px glow): plain
	 * GL_LINEAR samples only 4 texels there, so thin strokes break up (1.16.5 lost
	 * its "@plus" glyph to exactly that). Needs GL 3.0 or EXT_framebuffer_object's
	 * glGenerateMipmap; without either it stays plain linear. Never call
	 * updateDynamicTexture() on a mipmapped texture (it would drop the chain).
	 */
	public static net.minecraft.client.renderer.texture.DynamicTexture registerTexture(
			ResourceLocation id, java.awt.image.BufferedImage img, boolean linear, boolean mipmap) {
		try {
			net.minecraft.client.renderer.texture.DynamicTexture tex =
					new net.minecraft.client.renderer.texture.DynamicTexture(img);
			Minecraft.getMinecraft().getTextureManager().loadTexture(id, tex);
			if (linear) {
				GlStateManager.bindTexture(tex.getGlTextureId());
				int min = GL11.GL_LINEAR;
				if (mipmap) {
					try {
						// TextureUtil allocates DynamicTextures with MAX_LEVEL = MAX_LOD = 0;
						// open the range FIRST (glGenerateMipmap only fills base+1..MAX_LEVEL).
						GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 1000);
						GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 1000f);
						org.lwjgl.opengl.ContextCapabilities caps = org.lwjgl.opengl.GLContext.getCapabilities();
						if (caps.OpenGL30) {
							org.lwjgl.opengl.GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
							min = GL11.GL_LINEAR_MIPMAP_LINEAR;
						} else if (caps.GL_EXT_framebuffer_object) {
							org.lwjgl.opengl.EXTFramebufferObject.glGenerateMipmapEXT(GL11.GL_TEXTURE_2D);
							min = GL11.GL_LINEAR_MIPMAP_LINEAR;
						}
					} catch (Throwable t) {
						min = GL11.GL_LINEAR;
					}
					if (min == GL11.GL_LINEAR) {
						GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
						GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 0f);
					}
				}
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, min);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			}
			return tex;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Reads a PNG from the mod jar (`/assets/...` path) -- NativeImage.read's stand-in. */
	public static java.awt.image.BufferedImage readImage(String classpathPath) throws java.io.IOException {
		try (java.io.InputStream in = Gfx.class.getResourceAsStream(classpathPath)) {
			if (in == null) {
				throw new java.io.FileNotFoundException(classpathPath);
			}
			return net.minecraft.client.renderer.texture.TextureUtil.readBufferedImage(in);
		}
	}

	// ---- solid fills ----

	public void fill(int x1, int y1, int x2, int y2, int argb) {
		fillF(x1, y1, x2, y2, argb);
	}

	/** {@link #fill} with float edges (sub-pixel placement under a scaled pose). */
	public void fillF(float x1, float y1, float x2, float y2, int argb) {
		if (x1 > x2) {
			float t = x1;
			x1 = x2;
			x2 = t;
		}
		if (y1 > y2) {
			float t = y1;
			y1 = y2;
			y2 = t;
		}
		int a = (argb >>> 24) & 0xFF;
		if (a == 0 || x1 == x2 || y1 == y2) {
			return;
		}
		int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		GlStateManager.disableTexture2D();
		defaultBlend();
		softAlpha();
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
		wr.pos(x1, y2, 0).color(r, g, b, a).endVertex();
		wr.pos(x2, y2, 0).color(r, g, b, a).endVertex();
		wr.pos(x2, y1, 0).color(r, g, b, a).endVertex();
		wr.pos(x1, y1, 0).color(r, g, b, a).endVertex();
		tess.draw();
		GlStateManager.disableBlend();
		GlStateManager.enableTexture2D();
		defaultAlpha();
		resetColor();
	}

	/** Vertical gradient (colorFrom at the top, colorTo at the bottom) -- GuiGraphics.fillGradient's
	 *  quad; smooth shading on so the vertex colours interpolate. */
	public void fillGradient(int x1, int y1, int x2, int y2, int colorFrom, int colorTo) {
		float a1 = (colorFrom >>> 24 & 0xFF) / 255f;
		float r1 = (colorFrom >> 16 & 0xFF) / 255f;
		float g1 = (colorFrom >> 8 & 0xFF) / 255f;
		float b1 = (colorFrom & 0xFF) / 255f;
		float a2 = (colorTo >>> 24 & 0xFF) / 255f;
		float r2 = (colorTo >> 16 & 0xFF) / 255f;
		float g2 = (colorTo >> 8 & 0xFF) / 255f;
		float b2 = (colorTo & 0xFF) / 255f;
		GlStateManager.disableTexture2D();
		defaultBlend();
		GlStateManager.disableAlpha();
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
		wr.pos(x1, y1, 0).color(r1, g1, b1, a1).endVertex();
		wr.pos(x1, y2, 0).color(r2, g2, b2, a2).endVertex();
		wr.pos(x2, y2, 0).color(r2, g2, b2, a2).endVertex();
		wr.pos(x2, y1, 0).color(r1, g1, b1, a1).endVertex();
		tess.draw();
		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.disableBlend();
		GlStateManager.enableAlpha();
		GlStateManager.enableTexture2D();
		resetColor();
	}

	// ---- textured blits (each overload binds its texture first) ----

	/** blit(tex, x, y, u, v, w, h, texW, texH) -- drawn at w x h. */
	public void blit(ResourceLocation tex, int x, int y, float u, float v, int w, int h, int texW, int texH) {
		bind(tex);
		softAlpha();
		Gui.drawModalRectWithCustomSizedTexture(x, y, u, v, w, h, texW, texH);
		defaultAlpha();
	}

	/** blit(tex, x, y, w, h, u, v, uW, vH, texW, texH) -- region uW x vH stretched to w x h. */
	public void blit(ResourceLocation tex, int x, int y, int w, int h, float u, float v, int uW, int vH, int texW, int texH) {
		bind(tex);
		softAlpha();
		// 1.8.9 argument order: (x, y, u, v, uWidth, vHeight, width, height, texW, texH)
		Gui.drawScaledCustomSizeModalRect(x, y, u, v, uW, vH, w, h, texW, texH);
		defaultAlpha();
	}

	/** blit(tex, x, y, u, v, w, h) -- the 256x256-sheet shorthand (vanilla's icons.png, widgets.png). */
	public void blit(ResourceLocation tex, int x, int y, int u, int v, int w, int h) {
		blit(tex, x, y, (float) u, (float) v, w, h, 256, 256);
	}

	/** Float-geometry blit of the BOUND texture: (x, y, w, h) in caller units, UVs 0..1.
	 *  1.8.9 helper (no 1.16.5 equivalent): Origin's own atlases place glyphs at sub-pixel
	 *  positions under scaled poses. */
	public void blitBound(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
		softAlpha();
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
		wr.pos(x, y + h, 0).tex(u0, v1).endVertex();
		wr.pos(x + w, y + h, 0).tex(u1, v1).endVertex();
		wr.pos(x + w, y, 0).tex(u1, v0).endVertex();
		wr.pos(x, y, 0).tex(u0, v0).endVertex();
		tess.draw();
		defaultAlpha();
	}

	/** Atlas-sprite blit; binds the block/item atlas (1.8.9 has one: TextureMap.locationBlocksTexture). */
	public void blit(int x, int y, int blitOffset, int w, int h, TextureAtlasSprite sprite) {
		if (sprite == null) {
			return;
		}
		bind(TextureMap.locationBlocksTexture);
		softAlpha();
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
		wr.pos(x, y + h, blitOffset).tex(sprite.getMinU(), sprite.getMaxV()).endVertex();
		wr.pos(x + w, y + h, blitOffset).tex(sprite.getMaxU(), sprite.getMaxV()).endVertex();
		wr.pos(x + w, y, blitOffset).tex(sprite.getMaxU(), sprite.getMinV()).endVertex();
		wr.pos(x, y, blitOffset).tex(sprite.getMinU(), sprite.getMinV()).endVertex();
		tess.draw();
		defaultAlpha();
	}

	// ---- text (GuiGraphics semantics: default shadow ON) ----

	public int drawString(FontRenderer font, String text, int x, int y, int color) {
		return drawString(font, text, x, y, color, true);
	}

	public int drawString(FontRenderer font, String text, int x, int y, int color, boolean shadow) {
		return drawStringF(font, text, x, y, color, shadow);
	}

	/** Float-position text (1.16.5's Font.draw took floats). */
	public int drawStringF(FontRenderer font, String text, float x, float y, int color, boolean shadow) {
		if (font == null || text == null) {
			return (int) x;
		}
		defaultBlend();
		int end = font.drawString(text, x, y, color, shadow);
		GlStateManager.disableBlend();
		resetColor();
		return end;
	}

	public int drawString(FontRenderer font, IChatComponent text, int x, int y, int color, boolean shadow) {
		return drawString(font, text == null ? "" : text.getFormattedText(), x, y, color, shadow);
	}

	public int drawString(FontRenderer font, IChatComponent text, int x, int y, int color) {
		return drawString(font, text, x, y, color, true);
	}

	/** GuiGraphics.drawStringWithBackdrop. 1.8.9 has no "text background opacity" option
	 *  (1.14+), which on later versions defaults to no backdrop outside chat anyway: plain
	 *  shadowed text. */
	public void drawStringWithBackdrop(FontRenderer font, IChatComponent text, int x, int y, int width, int color) {
		drawString(font, text, x, y, color, true);
	}

	public void drawStringWithBackdrop(FontRenderer font, String text, int x, int y, int width, int color) {
		drawString(font, text, x, y, color, true);
	}

	// ---- scissor (GUI-space rectangle, like GuiGraphics) ----

	// GL scissor is window-pixel, bottom-left origin; GUI space is scaled,
	// top-left origin.
	public void enableScissor(int x1, int y1, int x2, int y2) {
		int scale = guiScale();
		scissorWindow(x1 * scale, windowHeight() - y2 * scale,
				Math.max(0, (x2 - x1) * scale), Math.max(0, (y2 - y1) * scale));
	}

	public void disableScissor() {
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
	}

	/** Scissor straight in window px, bottom-left origin (RenderSystem.enableScissor). */
	public static void scissorWindow(int x, int y, int w, int h) {
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		GL11.glScissor(x, y, Math.max(0, w), Math.max(0, h));
	}

	// ---- items ----

	// Latched fail-soft: one throw flips item drawing off for the session instead
	// of erroring every frame (a modded item renderer can throw).
	private static volatile boolean itemDrawBroken = false;

	public void renderItem(ItemStack stack, int x, int y) {
		if (itemDrawBroken || stack == null) {
			return;
		}
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		try {
			// 1.8.9: GUI items need item lighting + the depth test (3D block models
			// otherwise draw their faces in submission order).
			RenderHelper.enableGUIStandardItemLighting();
			GlStateManager.enableDepth();
			Minecraft.getMinecraft().getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
		} catch (Throwable t) {
			itemDrawBroken = true;
		} finally {
			afterItem(depth);
		}
	}

	public void renderItemDecorations(FontRenderer font, ItemStack stack, int x, int y) {
		if (itemDrawBroken || stack == null) {
			return;
		}
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		try {
			Minecraft.getMinecraft().getRenderItem().renderItemOverlays(font, stack, x, y);
		} catch (Throwable t) {
			itemDrawBroken = true;
		} finally {
			afterItem(depth);
		}
	}

	// RenderItem leaves lighting ON, the alpha test OFF and depth ON: put back the GUI's state.
	private static void afterItem(boolean depthWasOn) {
		RenderHelper.disableStandardItemLighting();
		GlStateManager.disableLighting();
		GlStateManager.enableAlpha();
		if (!depthWasOn) {
			GlStateManager.disableDepth();
		}
		resetColor();
	}

	/** GuiGraphics.renderFakeItem: a GUI item with no owning entity -- the same call here. */
	public void renderFakeItem(ItemStack stack, int x, int y) {
		renderItem(stack, x, y);
	}

	// ---- tooltips ----

	/** GuiGraphics.renderTooltip(font, text, mx, my). */
	public void renderTooltip(FontRenderer font, IChatComponent text, int mouseX, int mouseY) {
		List<String> one = new ArrayList<>();
		one.add(text == null ? "" : text.getFormattedText());
		drawTooltip(font, one, mouseX, mouseY);
	}

	public void renderTooltip(FontRenderer font, String text, int mouseX, int mouseY) {
		List<String> one = new ArrayList<>();
		one.add(text == null ? "" : text);
		drawTooltip(font, one, mouseX, mouseY);
	}

	/** GuiGraphics.renderComponentTooltip(font, lines, mx, my). */
	public void renderComponentTooltip(FontRenderer font, List<IChatComponent> lines, int mouseX, int mouseY) {
		List<String> out = new ArrayList<>();
		for (IChatComponent c : lines) {
			out.add(c == null ? "" : c.getFormattedText());
		}
		drawTooltip(font, out, mouseX, mouseY);
	}

	/**
	 * Vanilla's tooltip box (GuiScreen.drawHoveringText's geometry and colours),
	 * drawn here rather than through the open screen: 1.8.9's drawHoveringText is
	 * protected, and this works with no screen open (HUD) too.
	 */
	public void drawTooltip(FontRenderer font, List<String> lines, int mouseX, int mouseY) {
		if (font == null || lines == null || lines.isEmpty()) {
			return;
		}
		int w = 0;
		for (String s : lines) {
			w = Math.max(w, font.getStringWidth(s));
		}
		int x = mouseX + 12, y = mouseY - 12;
		int h = 8 + (lines.size() > 1 ? 2 + (lines.size() - 1) * 10 : 0);
		int sw = guiWidth(), sh = guiHeight();
		if (x + w > sw) {
			x -= 28 + w;
		}
		if (y + h + 6 > sh) {
			y = sh - h - 6;
		}
		boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		GlStateManager.disableDepth();
		pose.pushPose();
		pose.translate(0, 0, 300);
		int bg = 0xF0100010, b1 = 0x505000FF, b2 = (b1 & 0xFEFEFE) >> 1 | b1 & 0xFF000000;
		fillGradient(x - 3, y - 4, x + w + 3, y - 3, bg, bg);
		fillGradient(x - 3, y + h + 3, x + w + 3, y + h + 4, bg, bg);
		fillGradient(x - 3, y - 3, x + w + 3, y + h + 3, bg, bg);
		fillGradient(x - 4, y - 3, x - 3, y + h + 3, bg, bg);
		fillGradient(x + w + 3, y - 3, x + w + 4, y + h + 3, bg, bg);
		fillGradient(x - 3, y - 3 + 1, x - 3 + 1, y + h + 3 - 1, b1, b2);
		fillGradient(x + w + 2, y - 3 + 1, x + w + 3, y + h + 3 - 1, b1, b2);
		fillGradient(x - 3, y - 3, x + w + 3, y - 3 + 1, b1, b1);
		fillGradient(x - 3, y + h + 2, x + w + 3, y + h + 3, b2, b2);
		int ty = y;
		for (int i = 0; i < lines.size(); i++) {
			drawString(font, lines.get(i), x, ty, 0xFFFFFFFF, true);
			ty += i == 0 ? 12 : 10;
		}
		pose.popPose();
		if (depth) {
			GlStateManager.enableDepth();
		}
	}
}
