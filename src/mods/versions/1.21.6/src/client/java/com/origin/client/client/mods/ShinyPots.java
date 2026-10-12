package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionContents;
import org.joml.Matrix3x2f;

/**
 * Shinypots (Lunar parity): the old 1.7 look -- a potion in an inventory slot
 * shines with the enchantment glint over its whole square, not just its shape.
 * (1.21.1 potions have no glint at all.)
 *
 * ShinyPotsMixin calls draw() around every GUI item draw (GuiGraphics' core
 * renderItem): before the item for "Render Glint Behind Potion", after it
 * otherwise. The shine is vanilla's own glint texture, scrolled with vanilla's
 * glint timing (RenderStateShard.setupGlintTexturing) and blended additively like
 * vanilla's glint -- so it honours the Glint Speed accessibility option.
 *   Colored Potions -- tint the shine with the potion's own colour
 *   Render Entire Slot -- the full 18x18 slot instead of the 16x16 item square
 *
 * PER-VERSION (1.21.6-1.21.8, deferred GUI): the shine is one GuiElementRenderState
 * (the shape of the module's ModIcons state) submitted into g.guiRenderState with
 * vanilla's glint blend (BlendFunction.GLINT = SRC_COLOR, ONE) on a GUI-textured
 * pipeline. Layering is by submission order + bounds, like everything in this
 * GUI: submitted before the item (Behind) the item lands on top of it, after the
 * item (default) it lands over the item -- the same two layers 1.21.1 built with z.
 */
public final class ShinyPots {
	private static final String MOD = "shinypots";
	// Glint texture repeats per 4 slot widths (vanilla's item glint is ~1/4 of
	// the texture across a 16px item).
	private static final float UV_PER_PX = 0.25f / 16f;

	private ShinyPots() {
	}

	public static void draw(GuiGraphics g, ItemStack stack, int x, int y, boolean behindPhase) {
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
		float[] uv = new float[8];
		int n = 0;
		for (float[] c : new float[][]{{0, 0}, {0, 1}, {1, 1}, {1, 0}}) {
			float su = c[0] * k, sv = c[1] * k;
			uv[n++] = -a + su * cos - sv * sin;
			uv[n++] = b + su * sin + sv * cos;
		}

		var tex = Minecraft.getInstance().getTextureManager().getTexture(ItemRenderer.ENCHANTED_GLINT_ITEM);
		tex.setFilter(true, false);
		GpuTextureView view = tex.getTextureView();
		if (view == null) {
			return;
		}
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		ScreenRectangle local = new ScreenRectangle(x0, y0, size, size).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(local) : local;
		if (bounds == null) {
			return;
		}
		g.guiRenderState.submitGuiElement(new ShineRenderState(TextureSetup.singleTexture(view), pose,
				x0, y0, x0 + size, y0 + size, uv, 0xFF000000 | tint, scissor, bounds));
	}

	/** Vanilla's GUI-textured pipeline with the item glint's additive blend. */
	private static final RenderPipeline SHINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/gui_shine"))
					.withBlend(BlendFunction.GLINT)
					.build());

	// One shine quad: rect + per-corner (rotated, scrolled) glint UVs + tint.
	private record ShineRenderState(TextureSetup textureSetup, Matrix3x2f pose, int x0, int y0, int x1, int y1,
									float[] uv, int color, ScreenRectangle scissorArea,
									ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer out, float depth) {
			out.addVertexWith2DPose(pose, x0, y0, depth).setUv(uv[0], uv[1]).setColor(color);
			out.addVertexWith2DPose(pose, x0, y1, depth).setUv(uv[2], uv[3]).setColor(color);
			out.addVertexWith2DPose(pose, x1, y1, depth).setUv(uv[4], uv[5]).setColor(color);
			out.addVertexWith2DPose(pose, x1, y0, depth).setUv(uv[6], uv[7]).setColor(color);
		}

		@Override
		public RenderPipeline pipeline() {
			return SHINE;
		}
	}
}
