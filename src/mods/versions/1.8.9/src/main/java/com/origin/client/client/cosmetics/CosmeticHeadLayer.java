package com.origin.client.client.cosmetics;

import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

/**
 * Draws the player's equipped head cosmetic (halos, crown) on every player
 * model: other players in the world, you in third person, and the inventory
 * / Cosmetics screen previews.
 *
 * 1.8.9: a LayerRenderer added to both player renderers (RenderManager's skin
 * map: "default" and "slim") with RendererLivingEntity.addLayer, which is
 * public here -- no mixin. {@link #installAll} runs once, from SocialHooks'
 * first client tick (the RenderManager exists by then). The layer runs with
 * the GL matrix in model space, exactly where vanilla's LayerCustomHead (a
 * skull / pumpkin) starts.
 */
public final class CosmeticHeadLayer implements LayerRenderer<AbstractClientPlayer> {
	private static boolean warned = false;
	private static boolean installed = false;

	private final RenderPlayer renderer;

	public CosmeticHeadLayer(RenderPlayer renderer) {
		this.renderer = renderer;
	}

	/** Adds the layer to every player renderer, once. Client thread; fail-soft. */
	public static void installAll() {
		if (installed) {
			return;
		}
		installed = true;
		try {
			for (RenderPlayer r : Minecraft.getMinecraft().getRenderManager().getSkinMap().values()) {
				r.addLayer(new CosmeticHeadLayer(r));
			}
		} catch (Throwable t) {
			// no head cosmetics; players still draw
			OriginClient.LOGGER.warn("Origin cosmetics: head layer not installed", t);
		}
	}

	@Override
	public void doRenderLayer(AbstractClientPlayer player, float limbSwing, float limbSwingAmount, float partialTicks,
			float ageInTicks, float netHeadYaw, float headPitch, float scale) {
		if (player.isInvisible()) {
			return;
		}
		String id = CosmeticsService.lookOf(player.getUniqueID()).head();
		if (id == null) {
			return;
		}
		GlStateManager.pushMatrix();
		try {
			// Follow the head (turning, nodding, sneaking: LayerCustomHead's own
			// steps), then switch to the website model's units: 1/16 block per
			// pixel, y up, face to +z (Minecraft's model space is y down with the
			// face to -z), and the neck at y=24.
			if (player.isSneaking()) {
				GlStateManager.translate(0.0F, 0.2F, 0.0F);
			}
			renderer.getMainModel().bipedHead.postRender(0.0625F);
			GlStateManager.scale(1 / 16f, -1 / 16f, -1 / 16f);
			GlStateManager.translate(0.0F, -24.0F, 0.0F);
			HeadModels.render(id, true, ageInTicks / 20f);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: head cosmetic failed to draw", t);
			}
		} finally {
			GlStateManager.popMatrix();
		}
	}

	@Override
	public boolean shouldCombineTextures() {
		return false;
	}
}
