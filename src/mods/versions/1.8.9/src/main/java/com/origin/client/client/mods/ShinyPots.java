package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/**
 * Shinypots (Lunar parity): the old 1.7 look -- a potion in an inventory slot
 * shines with the enchantment glint over its whole square, not just its shape.
 *
 * ShinyPotsMixin calls draw() around every GUI item draw (RenderItem's
 * renderItemIntoGUI on 1.8.9 -- renderItemAndEffectIntoGUI, the hotbar and every
 * container slot funnel into it): before the item for "Render Glint Behind
 * Potion", after it otherwise. The shine is vanilla's own glint texture, scrolled
 * with the newer versions' glint timing (millis x 8, the same look on every
 * version) and blended additively like vanilla's glint.
 *   Colored Potions -- tint the shine with the potion's own colour
 *   Render Entire Slot -- the full 18x18 slot instead of the 16x16 item square
 *
 * 1.8.9: drawn straight away with fixed-function state in the GUI space the GL
 * model-view matrix already holds; z sits just in front of or behind the item
 * model (vanilla draws GUI items at 100 + RenderItem.zLevel). The alpha test is
 * off for the quad, as vanilla's glint pass runs without one.
 */
public final class ShinyPots {
	private static final String MOD = "shinypots";
	// Glint texture repeats per 4 slot widths (vanilla's item glint is ~1/4 of
	// the texture across a 16px item).
	private static final float UV_PER_PX = 0.25f / 16f;

	private ShinyPots() {
	}

	public static void draw(ItemStack stack, int x, int y, float blitOffset, boolean behindPhase) {
		if (stack == null || !(stack.getItem() instanceof ItemPotion) || !Mods.on(MOD)
				|| Mods.bool(MOD, "behind") != behindPhase) {
			return;
		}
		boolean slot = Mods.bool(MOD, "entireSlot");
		int x0 = slot ? x - 1 : x, y0 = slot ? y - 1 : y, size = slot ? 18 : 16;
		int tint = 0xFFFFFF;
		if (Mods.bool(MOD, "coloredPotions")) {
			// 1.8.9: the bottle liquid's colour is the potion item's layer-0 colour
			tint = stack.getItem().getColorFromItemStack(stack, 0) & 0xFFFFFF;
		}
		// In front of the item model (it spans roughly 92-108 + zLevel) or behind it.
		float z = blitOffset + (behindPhase ? 50f : 200f);

		// the newer versions' glint scroll: translate(-a, b) * rotateZ(10 deg) on the UVs
		long t = Minecraft.getSystemTime() * 8L;
		float a = (float) (t % 110000L) / 110000.0F;
		float b = (float) (t % 30000L) / 30000.0F;
		float cos = (float) Math.cos(0.17453292F), sin = (float) Math.sin(0.17453292F);
		float k = size * UV_PER_PX;

		Minecraft.getMinecraft().getTextureManager().bindTexture(GlintColorizer.VANILLA_GLINT);
		GlStateManager.enableTexture2D();
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GlStateManager.disableAlpha();
		GlStateManager.disableLighting();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_COLOR, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
		GlStateManager.depthMask(false);
		GlStateManager.color(1f, 1f, 1f, 1f);
		Tessellator tess = Tessellator.getInstance();
		WorldRenderer wr = tess.getWorldRenderer();
		wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
		corner(wr, x0, y0, z, 0, 0, a, b, k, cos, sin, tint);
		corner(wr, x0, y0 + size, z, 0, 1, a, b, k, cos, sin, tint);
		corner(wr, x0 + size, y0 + size, z, 1, 1, a, b, k, cos, sin, tint);
		corner(wr, x0 + size, y0, z, 1, 0, a, b, k, cos, sin, tint);
		tess.draw();
		GlStateManager.depthMask(true);
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.enableAlpha();
	}

	private static void corner(WorldRenderer wr, float x, float y, float z, float u, float v,
							   float a, float b, float k, float cos, float sin, int rgb) {
		float su = u * k, sv = v * k;
		float tu = -a + su * cos - sv * sin;
		float tv = b + su * sin + sv * cos;
		wr.pos(x, y, z).tex(tu, tv).color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255).endVertex();
	}
}
