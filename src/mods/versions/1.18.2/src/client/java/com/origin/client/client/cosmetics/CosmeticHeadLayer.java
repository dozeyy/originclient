package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

/**
 * Draws the player's equipped head cosmetic (halos, crown) on every player
 * model: other players in the world, you in third person, and the inventory
 * / Cosmetics screen previews. Registered on the player renderer through
 * Fabric's LivingEntityFeatureRendererRegistrationCallback (OriginClientMod).
 */
public final class CosmeticHeadLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	private static boolean warned = false;

	public CosmeticHeadLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
		super(parent);
	}

	@Override
	public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
		if (player.isInvisible()) {
			return;
		}
		String id = CosmeticsService.lookOf(player.getUUID()).head();
		if (id == null) {
			return;
		}
		pose.pushPose();
		try {
			// Follow the head (turning, nodding, sneaking), then switch to the
			// website model's units: 1/16 block per pixel, y up, face to +z
			// (Minecraft's model space is y down with the face to -z), and the
			// neck at y=24.
			getParentModel().head.translateAndRotate(pose);
			pose.scale(1 / 16f, -1 / 16f, -1 / 16f);
			pose.translate(0, -24, 0);
			HeadModels.render(id, pose, buffers, light, ageInTicks / 20f);
		} catch (Throwable t) {
			if (!warned) {
				warned = true;
				OriginClient.LOGGER.warn("Origin cosmetics: head cosmetic failed to draw", t);
			}
		} finally {
			pose.popPose();
		}
	}
}
