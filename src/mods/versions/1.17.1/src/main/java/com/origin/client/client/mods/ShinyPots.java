package com.origin.client.client.mods;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;

/**
 * Shinypots (Lunar parity): the old 1.7 look -- a potion in an inventory slot
 * shines with the enchantment glint over its whole square, not just its shape.
 *
 * ShinyPotsMixin calls draw() around every GUI item draw (ItemRenderer's
 * renderGuiItem on 1.17.1): before the item for "Render Glint Behind Potion",
 * after it otherwise. The shine is vanilla's own glint texture, scrolled with
 * vanilla's glint timing (RenderStateShard.setupGlintTexturing: millis x 8 --
 * no Glint Speed option before 1.19) and blended additively like vanilla's glint.
 *   Colored Potions -- tint the shine with the potion's own colour
 *   Render Entire Slot -- the full 18x18 slot instead of the 16x16 item square
 *
 * 1.17.1: drawn straight away (the GUI isn't batched before 1.20), in the GUI
 * space the global model-view stack already holds; z sits just in front of or
 * behind the item model (vanilla draws GUI items at 100 + blitOffset).
 */
public final class ShinyPots {
	private static final String MOD = "shinypots";
	// Glint texture repeats per 4 slot widths (vanilla's item glint is ~1/4 of
	// the texture across a 16px item).
	private static final float UV_PER_PX = 0.25f / 16f;

	private ShinyPots() {
	}

	public static void draw(ItemStack stack, int x, int y, float blitOffset, boolean behindPhase) {
		if (stack.isEmpty() || !(stack.getItem() instanceof PotionItem) || !Mods.on(MOD)
				|| Mods.bool(MOD, "behind") != behindPhase) {
			return;
		}
		boolean slot = Mods.bool(MOD, "entireSlot");
		int x0 = slot ? x - 1 : x, y0 = slot ? y - 1 : y, size = slot ? 18 : 16;
		int tint = 0xFFFFFF;
		if (Mods.bool(MOD, "coloredPotions")) {
			tint = PotionUtils.getColor(stack) & 0xFFFFFF;
		}
		// In front of the item model (it spans roughly 92-108 + blitOffset) or behind it.
		float z = blitOffset + (behindPhase ? 50f : 200f);

		// vanilla's glint scroll: translate(-a, b) * rotateZ(10 deg) on the UVs
		long t = Util.getMillis() * 8L;
		float a = (float) (t % 110000L) / 110000.0F;
		float b = (float) (t % 30000L) / 30000.0F;
		float cos = (float) Math.cos(0.17453292F), sin = (float) Math.sin(0.17453292F);
		float k = size * UV_PER_PX;

		Matrix4f m = new com.mojang.blaze3d.vertex.PoseStack().last().pose();   // identity: model-view holds the GUI transform
		var tm = Minecraft.getInstance().getTextureManager();
		tm.getTexture(ItemRenderer.ENCHANT_GLINT_LOCATION).setFilter(true, false);
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, ItemRenderer.ENCHANT_GLINT_LOCATION);
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_COLOR, GlStateManager.DestFactor.ONE,
				GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
		RenderSystem.depthMask(false);
		BufferBuilder bb = Tesselator.getInstance().getBuilder();
		bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		corner(bb, m, x0, y0, z, 0, 0, a, b, k, cos, sin, tint);
		corner(bb, m, x0, y0 + size, z, 0, 1, a, b, k, cos, sin, tint);
		corner(bb, m, x0 + size, y0 + size, z, 1, 1, a, b, k, cos, sin, tint);
		corner(bb, m, x0 + size, y0, z, 1, 0, a, b, k, cos, sin, tint);
		bb.end();
		BufferUploader.end(bb);
		RenderSystem.depthMask(true);
		RenderSystem.defaultBlendFunc();
	}

	private static void corner(BufferBuilder bb, Matrix4f m, float x, float y, float z, float u, float v,
							   float a, float b, float k, float cos, float sin, int rgb) {
		float su = u * k, sv = v * k;
		float tu = -a + su * cos - sv * sin;
		float tv = b + su * sin + sv * cos;
		bb.vertex(m, x, y, z).uv(tu, tv).color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255).endVertex();
	}
}
