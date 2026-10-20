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
import com.mojang.math.Matrix4f;

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
//  - enableScissor/disableScissor: GuiComponent's statics exist on 1.19.2 but
//    NOT on 1.19 (javap-verified against both mapped jars), and this jar's
//    declared floor is 1.19 — so the GUI-space rectangle is converted to
//    window pixels here (the exact math GuiComponent/GuiGraphics use) and fed
//    to RenderSystem.enableScissor, which every 1.19.x has. One code path for
//    the whole range instead of a floor-only NoSuchMethodError.
//  - renderItem/renderItemDecorations: 1.19.2's ItemRenderer has NO PoseStack
//    overloads — the no-pose variants draw through the global modelview stack,
//    so this pose's transform is multiplied into the modelview around the call
//    (or a scaled HUD editor would misplace every armor icon).
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

	/** Atlas-sprite blit (potion effect icons); binds the sprite's own atlas.
	 *  1.19.2 has atlas().location(), not the later atlasLocation(). */
	public void blit(int x, int y, int blitOffset, int w, int h, TextureAtlasSprite sprite) {
		RenderSystem.setShaderTexture(0, sprite.atlas().location());
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
		var window = Minecraft.getInstance().getWindow();
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

	// 1.19.2's GUI item draws (renderAndDecorateItem / renderGuiItemDecorations)
	// have no PoseStack parameter — they position through the GLOBAL modelview
	// stack. Multiplying this wrapper's pose into the modelview around the call
	// keeps HUD-editor scale/offset transforms working exactly like the
	// GuiGraphics-era overloads did. Latched fail-soft like everything else:
	// one throw flips item drawing off for the session instead of erroring
	// every frame.
	private static volatile boolean itemDrawBroken = false;

	public void renderItem(ItemStack stack, int x, int y) {
		if (itemDrawBroken) {
			return;
		}
		try {
			PoseStack modelView = RenderSystem.getModelViewStack();
			modelView.pushPose();
			modelView.mulPoseMatrix(pose.last().pose());
			RenderSystem.applyModelViewMatrix();
			try {
				Minecraft.getInstance().getItemRenderer().renderAndDecorateItem(stack, x, y);
			} finally {
				modelView.popPose();
				RenderSystem.applyModelViewMatrix();
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
			PoseStack modelView = RenderSystem.getModelViewStack();
			modelView.pushPose();
			modelView.mulPoseMatrix(pose.last().pose());
			RenderSystem.applyModelViewMatrix();
			try {
				Minecraft.getInstance().getItemRenderer().renderGuiItemDecorations(font, stack, x, y);
			} finally {
				modelView.popPose();
				RenderSystem.applyModelViewMatrix();
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

	// ---- Minecraft's own button art (GuiGraphics.blitSprite("widget/button") twin) ----

	/** Vanilla's button sheet this era: widgets.png, rows of 200x20 at v = 46 + 20*state. */
	private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");

	/**
	 * A stock Minecraft button background at (x, y, w, h). {@code state}: 0 disabled,
	 * 1 normal, 2 highlighted -- the same row AbstractButton.getTextureY picks. Pre-1.20.2
	 * there are no GUI sprites: the 200x20 strip is drawn as four corners (left/right
	 * halves, top/bottom halves), the way vanilla's own button stretches to any size
	 * up to 400 x 40, so a 20x20 square and a 100x20 chip both keep the bevelled edge.
	 */
	public void vanillaButton(int x, int y, int w, int h, int state) {
		if (w <= 0 || h <= 0) {
			return;
		}
		int v = 46 + Math.max(0, Math.min(2, state)) * 20;
		int lw = w / 2, rw = w - lw;
		int th = h / 2, bh = h - th;
		RenderSystem.setShaderTexture(0, WIDGETS);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		GuiComponent.blit(pose, x, y, 0f, (float) v, lw, th, 256, 256);
		GuiComponent.blit(pose, x + lw, y, (float) (200 - rw), (float) v, rw, th, 256, 256);
		GuiComponent.blit(pose, x, y + th, 0f, (float) (v + 20 - bh), lw, bh, 256, 256);
		GuiComponent.blit(pose, x + lw, y + th, (float) (200 - rw), (float) (v + 20 - bh), rw, bh, 256, 256);
	}

	/** A player head from a skin texture (face + hat layer). 1.19.x
	 *  PlayerFaceRenderer draws the BOUND texture, so bind it first. */
	public void face(ResourceLocation skin, int x, int y, int size) {
		RenderSystem.setShaderTexture(0, skin);
		net.minecraft.client.gui.components.PlayerFaceRenderer.draw(pose, x, y, size);
	}
}
