package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

// The pre-1.20 rendering shim. GuiGraphics only exists since 1.20; this class
// wraps a PoseStack and exposes instance methods with the SAME shapes the
// Origin code called on GuiGraphics, so the whole render core ports by a type
// swap (GuiGraphics -> Gfx) instead of touching ~138 call sites. Every era
// difference is centralized HERE, never at a call site:
//  - blit: GuiComponent's statics draw with whatever texture is bound, so each
//    overload binds via RenderSystem.setShaderTexture(0, tex) first — matching
//    GuiGraphics, whose blits bind the texture themselves.
//  - drawString: GuiGraphics defaults to shadow ON; the 5-arg overload here
//    maps to Font.drawShadow and the boolean overload picks drawShadow/draw.
//  - fillGradient: GuiComponent's is protected static, so the vertical
//    two-color quad is re-implemented (same vertex layout vanilla uses).
//  - enableScissor/disableScissor: GuiComponent grew these statics in 1.19.4
//    with the same GUI-space rectangle semantics GuiGraphics has — direct
//    delegates. (Pre-1.19.4 ports must re-implement with gui-scale math.)
//  - renderItem/renderItemDecorations: 1.19.x ItemRenderer still takes the
//    PoseStack explicitly (renderAndDecorateItem / renderGuiItemDecorations).
// Construct one per draw boundary (mixin handler, Screen.render override,
// Fabric screen event) around the PoseStack vanilla hands us; the wrapper is
// stateless beyond the pose reference, so it is free to create per frame.
public final class Gfx {
	private final PoseStack pose;

	public Gfx(PoseStack pose) {
		this.pose = pose;
	}

	public PoseStack pose() {
		return pose;
	}

	/** GuiGraphics.flush() twin. Pre-1.20 every draw here is immediate (GuiComponent
	 *  statics and Tesselator end their own batch), so there is never anything
	 *  pending to push out — kept so ported 1.21.1 call sites read the same. */
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
		GuiComponent.fill(pose, x1, y1, x2, y2, argb);
	}

	/** Vertical gradient (colorFrom at the top, colorTo at the bottom) — the
	 *  same quad GuiGraphics.fillGradient draws; re-implemented because
	 *  GuiComponent's version is protected static. */
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
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		BufferBuilder buf = Tesselator.getInstance().getBuilder();
		buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		buf.vertex(mat, x1, y1, 0).color(r1, g1, b1, a1).endVertex();
		buf.vertex(mat, x1, y2, 0).color(r2, g2, b2, a2).endVertex();
		buf.vertex(mat, x2, y2, 0).color(r2, g2, b2, a2).endVertex();
		buf.vertex(mat, x2, y1, 0).color(r1, g1, b1, a1).endVertex();
		BufferUploader.drawWithShader(buf.end());
	}

	// ---- textured blits (each overload binds its texture first) ----

	/** blit(tex, x, y, u, v, w, h, texW, texH) — drawn at w x h. */
	public void blit(ResourceLocation tex, int x, int y, float u, float v, int w, int h, int texW, int texH) {
		RenderSystem.setShaderTexture(0, tex);
		GuiComponent.blit(pose, x, y, u, v, w, h, texW, texH);
	}

	/** blit(tex, x, y, w, h, u, v, uW, vH, texW, texH) — region uW x vH stretched to w x h. */
	public void blit(ResourceLocation tex, int x, int y, int w, int h, float u, float v, int uW, int vH, int texW, int texH) {
		RenderSystem.setShaderTexture(0, tex);
		GuiComponent.blit(pose, x, y, w, h, u, v, uW, vH, texW, texH);
	}

	/** Atlas-sprite blit (potion effect icons); binds the sprite's own atlas. */
	public void blit(int x, int y, int blitOffset, int w, int h, TextureAtlasSprite sprite) {
		RenderSystem.setShaderTexture(0, sprite.atlasLocation());
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

	public void enableScissor(int x1, int y1, int x2, int y2) {
		GuiComponent.enableScissor(x1, y1, x2, y2);
	}

	public void disableScissor() {
		GuiComponent.disableScissor();
	}

	// ---- items ----

	// The PoseStack overloads of renderAndDecorateItem/renderGuiItemDecorations
	// only exist since 1.19.4; on the range floor (1.19.3) they raise
	// NoSuchMethodError. The latch flips item drawing off for the session there
	// (armor HUD quietly absent) instead of throwing per frame — same fail-soft
	// posture as the rest of the module.
	private static volatile boolean itemDrawBroken = false;

	public void renderItem(ItemStack stack, int x, int y) {
		if (itemDrawBroken) {
			return;
		}
		try {
			Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(pose, stack, x, y);
		} catch (Throwable t) {
			itemDrawBroken = true;
		}
	}

	public void renderItemDecorations(Font font, ItemStack stack, int x, int y) {
		if (itemDrawBroken) {
			return;
		}
		try {
			Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(pose, font, stack, x, y);
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
		RenderSystem.setShaderTexture(0, tex);
		GuiComponent.blit(pose, x, y, (float) u, (float) v, w, h, 256, 256);
	}

	public int drawString(Font font, Component text, int x, int y, int color) {
		return font.drawShadow(pose, text, x, y, color);
	}

	/** GuiGraphics.renderFakeItem: a GUI item with no owning entity. Pre-1.20 the plain
	 *  item draw is the same call (the entity only seeds animated models), so one path. */
	public void renderFakeItem(ItemStack stack, int x, int y) {
		renderItem(stack, x, y);
	}

	/** GuiGraphics.renderTooltip(font, text, mx, my): before 1.20 the Screen draws its
	 *  own tooltips, so the open screen is handed the call (no screen -> nothing). */
	public void renderTooltip(Font font, Component text, int mouseX, int mouseY) {
		net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
		if (s != null) {
			s.renderTooltip(pose, text, mouseX, mouseY);
		}
	}

	/** GuiGraphics.renderComponentTooltip(font, lines, mx, my): handed to the open
	 *  screen like renderTooltip above (Screen.renderComponentTooltip draws it). */
	public void renderComponentTooltip(Font font, java.util.List<Component> lines, int mouseX, int mouseY) {
		net.minecraft.client.gui.screens.Screen s = Minecraft.getInstance().screen;
		if (s != null) {
			s.renderComponentTooltip(pose, lines, mouseX, mouseY);
		}
	}

	// ---- shapes the Lunar-parity mods call ----

	/** drawString for already laid-out text (a wrapped line of a pack description). */
	public int drawString(Font font, net.minecraft.util.FormattedCharSequence text, int x, int y, int color, boolean shadow) {
		return shadow ? font.drawShadow(pose, text, x, y, color) : font.draw(pose, text, x, y, color);
	}

	/** A player head from a skin texture (face + hat layer). 1.19.x
	 *  PlayerFaceRenderer draws the BOUND texture, so bind it first. */
	public void face(ResourceLocation skin, int x, int y, int size) {
		RenderSystem.setShaderTexture(0, skin);
		net.minecraft.client.gui.components.PlayerFaceRenderer.draw(pose, x, y, size);
	}
}
