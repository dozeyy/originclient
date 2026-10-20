package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.mojang.math.Matrix4f;
import org.lwjgl.opengl.GL11;

// The pre-1.20 rendering shim. GuiGraphics only exists since 1.20; this class
// wraps a PoseStack and exposes instance methods with the SAME shapes the
// Origin code called on GuiGraphics, so the whole render core ports by a type
// swap (GuiGraphics -> Gfx) instead of touching ~138 call sites. Every era
// difference is centralized HERE, never at a call site.
//
// 1.16.5 is FIXED-FUNCTION GL: RenderSystem has NO setShader/setShaderTexture/
// setShaderColor (those are 1.17+). So:
//  - blit: bind the texture via TextureManager.bind(rl) (GuiComponent's static
//    blit draws with whatever texture is bound and modulates by the current GL
//    color); tints are the global GL color set through RenderSystem.color4f.
//  - fillGradient: GuiComponent's is protected static, so the vertical two-color
//    quad is re-implemented with the exact fixed-function sequence vanilla's own
//    fillGradient uses on 1.16.5 (disableTexture / shadeModel(SMOOTH) / a
//    POSITION_COLOR quad through the Tesselator / restore).
//  - drawString: GuiGraphics defaults to shadow ON; the 5-arg overload maps to
//    Font.drawShadow and the boolean overload picks drawShadow/draw.
//  - enableScissor/disableScissor: GuiComponent has no scissor statics pre-1.20,
//    so the GUI-space rectangle is converted to window pixels here (the exact
//    math the later GuiGraphics uses) and fed to RenderSystem.enableScissor.
//  - renderItem/renderItemDecorations: 1.16.5's ItemRenderer has NO PoseStack
//    overloads AND RenderSystem has no getModelViewStack (1.17+). The no-pose
//    variants draw through the LEGACY GL matrix stack, so this pose's transform
//    is multiplied into that stack (pushMatrix / multMatrix / popMatrix) around
//    the call, or a scaled HUD editor would misplace every armor icon.
// Construct one per draw boundary around the PoseStack vanilla hands us; the
// wrapper is stateless beyond the pose reference, so it is free to create per
// frame.
public final class Gfx {
	private final PoseStack pose;

	public Gfx(PoseStack pose) {
		this.pose = pose;
	}

	public PoseStack pose() {
		return pose;
	}

	/** GuiGraphics.flush() twin. Pre-1.20 every draw here is immediate (GuiComponent
	 *  statics and Tesselator end their own batch), so nothing is ever pending --
	 *  kept so the ported 1.21.1 call sites read the same. */
	public void flush() {
	}

	public int guiWidth() {
		return Minecraft.getInstance().getWindow().getGuiScaledWidth();
	}

	public int guiHeight() {
		return Minecraft.getInstance().getWindow().getGuiScaledHeight();
	}

	// ---- solid fills ----

	public void fill(int x1, int y1, int x2, int y2, int argb) {
		softAlpha();
		GuiComponent.fill(pose, x1, y1, x2, y2, argb);
		RenderSystem.defaultAlphaFunc();
	}

	// 1.16.5 fixed function: GuiComponent.blit force-enables the ALPHA TEST at its default
	// GREATER 0.1 cut-off (and fills inherit it), which throws away every faint pixel --
	// Origin's software anti-aliased corners, glows, hover washes and every panel mid-fade.
	// Each Gfx draw runs with the cut-off at 0 (only fully transparent pixels dropped) and
	// puts vanilla's default back afterwards.
	private static void softAlpha() {
		RenderSystem.alphaFunc(GL11.GL_GREATER, 0.0F);
	}

	/** Vertical gradient (colorFrom at the top, colorTo at the bottom) — the
	 *  same quad GuiGraphics.fillGradient draws; re-implemented because
	 *  GuiComponent's version is protected static. Fixed-function on 1.16.5:
	 *  texturing OFF + smooth shading so the vertex colors show. */
	public void fillGradient(int x1, int y1, int x2, int y2, int colorFrom, int colorTo) {
		float a1 = (colorFrom >>> 24 & 0xFF) / 255f;
		float r1 = (colorFrom >> 16 & 0xFF) / 255f;
		float g1 = (colorFrom >> 8 & 0xFF) / 255f;
		float b1 = (colorFrom & 0xFF) / 255f;
		float a2 = (colorTo >>> 24 & 0xFF) / 255f;
		float r2 = (colorTo >> 16 & 0xFF) / 255f;
		float g2 = (colorTo >> 8 & 0xFF) / 255f;
		float b2 = (colorTo & 0xFF) / 255f;
		Matrix4f mat = pose.last().pose();
		RenderSystem.disableTexture();
		RenderSystem.enableBlend();
		RenderSystem.disableAlphaTest();
		RenderSystem.defaultBlendFunc();
		RenderSystem.shadeModel(GL11.GL_SMOOTH);
		BufferBuilder buf = Tesselator.getInstance().getBuilder();
		// 1.16.5 BufferBuilder.begin takes an int GL mode (no VertexFormat.Mode enum).
		buf.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_COLOR);
		buf.vertex(mat, x1, y1, 0).color(r1, g1, b1, a1).endVertex();
		buf.vertex(mat, x1, y2, 0).color(r2, g2, b2, a2).endVertex();
		buf.vertex(mat, x2, y2, 0).color(r2, g2, b2, a2).endVertex();
		buf.vertex(mat, x2, y1, 0).color(r1, g1, b1, a1).endVertex();
		// Tesselator.end() uploads and draws with the current fixed-function state.
		Tesselator.getInstance().end();
		RenderSystem.shadeModel(GL11.GL_FLAT);
		RenderSystem.disableBlend();
		RenderSystem.enableAlphaTest();
		RenderSystem.enableTexture();
	}

	// ---- textured blits (each overload binds its texture first) ----

	/** blit(tex, x, y, u, v, w, h, texW, texH) — drawn at w x h. */
	public void blit(ResourceLocation tex, int x, int y, float u, float v, int w, int h, int texW, int texH) {
		Minecraft.getInstance().getTextureManager().bind(tex);
		softAlpha();
		GuiComponent.blit(pose, x, y, u, v, w, h, texW, texH);
		RenderSystem.defaultAlphaFunc();
	}

	/** blit(tex, x, y, w, h, u, v, uW, vH, texW, texH) — region uW x vH stretched to w x h. */
	public void blit(ResourceLocation tex, int x, int y, int w, int h, float u, float v, int uW, int vH, int texW, int texH) {
		Minecraft.getInstance().getTextureManager().bind(tex);
		softAlpha();
		GuiComponent.blit(pose, x, y, w, h, u, v, uW, vH, texW, texH);
		RenderSystem.defaultAlphaFunc();
	}

	/** Atlas-sprite blit (potion effect icons); binds the sprite's own atlas.
	 *  1.16.5 has atlas().location(), not the later atlasLocation(). */
	public void blit(int x, int y, int blitOffset, int w, int h, TextureAtlasSprite sprite) {
		Minecraft.getInstance().getTextureManager().bind(sprite.atlas().location());
		GuiComponent.blit(pose, x, y, blitOffset, w, h, sprite);
	}

	// ---- text (GuiGraphics semantics: default shadow ON) ----

	public int drawString(Font font, String text, int x, int y, int color) {
		return font.drawShadow(pose, text, x, y, color);
	}

	public int drawString(Font font, String text, int x, int y, int color, boolean shadow) {
		return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
	}

	public int drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
		return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
	}

	// ---- scissor (GUI-space rectangle, like GuiGraphics) ----

	// GL scissor is window-pixel, bottom-left origin; GUI space is scaled,
	// top-left origin — same conversion vanilla's own enableScissor does.
	public void enableScissor(int x1, int y1, int x2, int y2) {
		com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
		int windowHeight = window.getHeight();
		double scale = window.getGuiScale();
		RenderSystem.enableScissor(
				(int) (x1 * scale),
				(int) (windowHeight - y2 * scale),
				Math.max(0, (int) ((x2 - x1) * scale)),
				Math.max(0, (int) ((y2 - y1) * scale)));
	}

	public void disableScissor() {
		RenderSystem.disableScissor();
	}

	// ---- items ----

	// 1.16.5's GUI item draws (renderAndDecorateItem / renderGuiItemDecorations)
	// have no PoseStack parameter — they position through the LEGACY GL matrix
	// stack (RenderSystem has no getModelViewStack on 1.16.5). Multiplying this
	// wrapper's pose into that stack around the call keeps HUD-editor scale/offset
	// transforms working exactly like the GuiGraphics-era overloads did. Latched
	// fail-soft: one throw flips item drawing off for the session instead of
	// erroring every frame.
	private static volatile boolean itemDrawBroken = false;

	public void renderItem(ItemStack stack, int x, int y) {
		if (itemDrawBroken) {
			return;
		}
		try {
			RenderSystem.pushMatrix();
			RenderSystem.multMatrix(pose.last().pose());
			try {
				Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(stack, x, y);
			} finally {
				RenderSystem.popMatrix();
			}
		} catch (Throwable t) {
			itemDrawBroken = true;
		}
	}

	public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
		if (itemDrawBroken) {
			return;
		}
		try {
			RenderSystem.pushMatrix();
			RenderSystem.multMatrix(pose.last().pose());
			try {
				Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
			} finally {
				RenderSystem.popMatrix();
			}
		} catch (Throwable t) {
			itemDrawBroken = true;
		}
	}

	// ---- shapes 1.21.1's code calls that the first Gfx did not need ----

	/** blit(tex, x, y, u, v, w, h) — the 256x256-sheet shorthand (vanilla's icons.png).
	 *  GuiComponent's 7-arg blit is an INSTANCE method in this era (it reads the
	 *  component's blitOffset), so the static 9-arg overload with the 256x256 sheet
	 *  size spelled out is the callable equivalent. */
	public void blit(ResourceLocation tex, int x, int y, int u, int v, int w, int h) {
		blit(tex, x, y, (float) u, (float) v, w, h, 256, 256);
	}

	public int drawString(Font font, Component text, int x, int y, int color) {
		return font.drawShadow(pose, text, x, y, color);
	}

	/** GuiGraphics.renderFakeItem: a GUI item with no owning entity. Pre-1.20 the plain
	 *  item draw is the same call (the entity only seeds animated models), so one path. */
	public void renderFakeItem(ItemStack stack, int x, int y) {
		renderItem(stack, x, y);
	}

	public int drawString(Font font, net.minecraft.util.FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
	}

	/** GuiGraphics.drawStringWithBackdrop: the chat-opacity backdrop (vanilla's
	 *  Gui.drawBackdrop maths, multiplied by the text colour so it fades with it)
	 *  behind shadowed text. Pre-1.20 vanilla only has the private Gui helper. */
	public void drawStringWithBackdrop(Font font, Component text, int x, int y, int width, int color) {
		int bg = Minecraft.getInstance().options.getBackgroundColor(0.0F);
		if (bg != 0) {
			fill(x - 2, y - 2, x + width + 2, y + 9 + 2, multiply(bg, color));
		}
		drawString(font, text, x, y, color, true);
	}

	// Per-channel ARGB multiply (FastColor.ARGB32.multiply, spelled out here).
	private static int multiply(int a, int b) {
		int al = ((a >>> 24) * (b >>> 24)) / 255;
		int r = (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF)) / 255;
		int g = (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF)) / 255;
		int bl = ((a & 0xFF) * (b & 0xFF)) / 255;
		return (al << 24) | (r << 16) | (g << 8) | bl;
	}

	/** GuiGraphics.renderTooltip(font, text, mx, my): before 1.20 the Screen draws its
	 *  own tooltips, so the open screen is handed the call (no screen -> nothing). */
	public void renderTooltip(Font font, Component text, int mouseX, int mouseY) {
		net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
		if (s != null) {
			s.renderTooltip(pose, text, mouseX, mouseY);
		}
	}

	/** GuiGraphics.renderComponentTooltip(font, lines, mx, my): a multi-line tooltip,
	 *  drawn by the open Screen like renderTooltip above. */
	public void renderComponentTooltip(Font font, java.util.List<Component> lines, int mouseX, int mouseY) {
		net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
		if (s != null) {
			s.renderComponentTooltip(pose, lines, mouseX, mouseY);
		}
	}

	// ---- Minecraft's own button art (GuiGraphics.blitSprite("widget/button") twin) ----

	/** Vanilla's button sheet this era: widgets.png, rows of 200x20 at v = 46 + 20*state. */
	private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");

	/**
	 * A stock Minecraft button background at (x, y, w, h). {@code state}: 0 disabled,
	 * 1 normal, 2 highlighted -- the row AbstractWidget.getYImage picks. No GUI sprites
	 * before 1.20.2: the 200x20 strip is drawn as four quarters (left/right, top/bottom
	 * halves) so any size up to 400 x 40 keeps the bevelled edge.
	 */
	public void vanillaButton(int x, int y, int w, int h, int state) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int v = 46 + Math.max(0, Math.min(2, state)) * 20;
		int lw = w / 2, rw = w - lw;
		int th = h / 2, bh = h - th;
		Minecraft.getInstance().getTextureManager().bind(WIDGETS);   // fixed-function: no setShaderTexture
		RenderSystem.color4f(1f, 1f, 1f, 1f);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		GuiComponent.blit(pose, x, y, 0f, (float) v, lw, th, 256, 256);
		GuiComponent.blit(pose, x + lw, y, (float) (200 - rw), (float) v, rw, th, 256, 256);
		GuiComponent.blit(pose, x, y + th, 0f, (float) (v + 20 - bh), lw, bh, 256, 256);
		GuiComponent.blit(pose, x + lw, y + th, (float) (200 - rw), (float) (v + 20 - bh), rw, bh, 256, 256);
	}
}
