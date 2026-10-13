package com.origin.client.client.mods;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.origin.client.client.render.GuiTris;
import com.origin.client.client.render.LunarPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Shinypots (Lunar parity): the old 1.7 look -- a potion in an inventory slot
 * shines with the enchantment glint over its whole square, not just its shape.
 * (Modern potions have no glint at all.)
 *
 * ShinyPotsMixin calls draw() around every GUI item draw (GuiGraphicsExtractor' core
 * renderItem): before the item for "Render Glint Behind Potion", after it
 * otherwise. The shine is vanilla's own glint texture, scrolled with vanilla's
 * glint timing (TextureTransform.setupGlintTexturing) and blended like vanilla's
 * glint (SRC_COLOR, ONE) -- so it honours the Glint Speed accessibility option.
 *   Colored Potions -- tint the shine with the potion's own colour
 *   Render Entire Slot -- the full 18x18 slot instead of the 16x16 item square
 *
 * PER-VERSION DELTA (1.21.11): the GUI is deferred, so the shine is a GuiTris
 * element on LunarPipelines.GUI_GLINT (the glint blend lives in the pipeline)
 * sampling the glint texture with a REPEAT sampler. "In front of" / "behind" the
 * item is decided by submission order: GuiRenderState puts an element that
 * overlaps something submitted earlier on a layer above it, so the shine
 * submitted after renderItem draws over the item, and before it, under.
 */
public final class ShinyPots {
	private static final String MOD = "shinypots";
	// Glint texture repeats per 4 slot widths (vanilla's item glint is ~1/4 of
	// the texture across a 16px item).
	private static final float UV_PER_PX = 0.25f / 16f;

	private ShinyPots() {
	}

	public static void draw(GuiGraphicsExtractor g, ItemStack stack, int x, int y, boolean behindPhase) {
		if (stack.isEmpty() || !(stack.getItem() instanceof PotionItem) || !Mods.on(MOD)
				|| Mods.bool(MOD, "behind") != behindPhase) {
			return;
		}
		boolean slot = Mods.bool(MOD, "entireSlot");
		int x0 = slot ? x - 1 : x, y0 = slot ? y - 1 : y, size = slot ? 18 : 16;
		int tint = 0xFFFFFF;
		if (Mods.bool(MOD, "coloredPotions")) {
			tint = stack.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getColor() & 0xFFFFFF;
		}

		// vanilla's glint scroll: translate(-a, b) * rotateZ(10 deg) on the UVs
		long t = (long) (Util.getMillis() * Minecraft.getInstance().options.glintSpeed().get() * 8.0);
		float a = (float) (t % 110000L) / 110000.0F;
		float b = (float) (t % 30000L) / 30000.0F;
		float cos = (float) Math.cos(0.17453292F), sin = (float) Math.sin(0.17453292F);
		float k = size * UV_PER_PX;

		var view = Minecraft.getInstance().getTextureManager().getTexture(net.minecraft.client.renderer.feature.ItemFeatureRenderer.ENCHANTED_GLINT_ITEM).getTextureView();
		GuiTris shine = GuiTris.textured(LunarPipelines.guiGlint(), view,
				RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR));
		float[] c00 = uv(0, 0, a, b, k, cos, sin), c01 = uv(0, 1, a, b, k, cos, sin);
		float[] c11 = uv(1, 1, a, b, k, cos, sin), c10 = uv(1, 0, a, b, k, cos, sin);
		int col = 0xFF000000 | tint;
		shine.tri(x0, y0, c00[0], c00[1], x0, y0 + size, c01[0], c01[1], x0 + size, y0 + size, c11[0], c11[1], col);
		shine.tri(x0, y0, c00[0], c00[1], x0 + size, y0 + size, c11[0], c11[1], x0 + size, y0, c10[0], c10[1], col);
		shine.submit(g);
	}

	private static float[] uv(float u, float v, float a, float b, float k, float cos, float sin) {
		float su = u * k, sv = v * k;
		return new float[]{-a + su * cos - sv * sin, b + su * sin + sv * cos};
	}
}
