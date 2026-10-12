package com.origin.client.client.mods;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import com.mojang.math.Matrix4f;

/**
 * Shinypots (Lunar parity): the old 1.7 look -- a potion in an inventory slot
 * shines with the enchantment glint over its whole square, not just its shape.
 * (1.19.4 potions have no glint at all; before 1.19.4 a potion with effects
 * glints only on its bottle shape.)
 *
 * ShinyPotsMixin calls draw() around every GUI slot item draw (ItemRenderer's
 * private tryRenderGuiItem, which every renderAndDecorate* call funnels into):
 * before the item for "Render Glint Behind Potion", after it otherwise. The
 * shine is vanilla's own item-glint texture (Era.glintTextures), scrolled with
 * vanilla's glint timing (RenderStateShard.setupGlintTexturing) and blended
 * additively like vanilla's glint -- so it honours the Glint Speed
 * accessibility option where the version has one (1.19.4).
 *
 * pose/zBase: where the item is. 1.19.4 hands its PoseStack through (zBase 0);
 * before it GUI items position through RenderSystem's model-view stack plus the
 * ItemRenderer's blitOffset, so the mixin passes an identity pose and that
 * offset -- the shader multiplies the current model-view in either way.
 *   Colored Potions -- tint the shine with the potion's own colour
 *   Render Entire Slot -- the full 18x18 slot instead of the 16x16 item square
 */
public final class ShinyPots {
	private static final String MOD = "shinypots";
	// Glint texture repeats per 4 slot widths (vanilla's item glint is ~1/4 of
	// the texture across a 16px item).
	private static final float UV_PER_PX = 0.25f / 16f;

	private ShinyPots() {
	}

	public static void draw(PoseStack pose, float zBase, ItemStack stack, int x, int y, boolean behindPhase) {
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
		// In front of the item (items sit at z 150-ish) or just behind it.
		float z = zBase + (behindPhase ? 100f : 250f);

		// vanilla's glint scroll: translate(-a, b) * rotateZ(10 deg) on the UVs
		long t = (long) (Util.getMillis() * Era.glintSpeed(Minecraft.getInstance().options) * 8.0);
		float a = (float) (t % 110000L) / 110000.0F;
		float b = (float) (t % 30000L) / 30000.0F;
		float cos = (float) Math.cos(0.17453292F), sin = (float) Math.sin(0.17453292F);
		float k = size * UV_PER_PX;

		ResourceLocation glint = Era.glintTextures()[0];
		var tm = Minecraft.getInstance().getTextureManager();
		tm.getTexture(glint).setFilter(true, false);
		RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
		RenderSystem.setShaderTexture(0, glint);
		var m = pose.last().pose();
		BufferBuilder bb = Tesselator.getInstance().getBuilder();
		bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		corner(bb, m, x0, y0, z, 0, 0, a, b, k, cos, sin, tint);
		corner(bb, m, x0, y0 + size, z, 0, 1, a, b, k, cos, sin, tint);
		corner(bb, m, x0 + size, y0 + size, z, 1, 1, a, b, k, cos, sin, tint);
		corner(bb, m, x0 + size, y0, z, 1, 0, a, b, k, cos, sin, tint);
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_COLOR, GlStateManager.DestFactor.ONE,
				GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
		RenderSystem.depthMask(false);
		Era.drawBuffer(bb);
		RenderSystem.depthMask(true);
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
	}

	// This era's builder wants the attributes in FORMAT order: position, uv, colour.
	private static void corner(BufferBuilder bb, Matrix4f m, float x, float y, float z, float u, float v,
							   float a, float b, float k, float cos, float sin, int rgb) {
		float su = u * k, sv = v * k;
		float tu = -a + su * cos - sv * sin;
		float tv = b + su * sin + sv * cos;
		bb.vertex(m, x, y, z).uv(tu, tv).color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255).endVertex();
	}
}
