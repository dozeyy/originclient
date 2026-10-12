package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Draws the player's equipped head cosmetic (halos, crown) on every player
 * model: other players in the world, you in third person, and the inventory
 * / Cosmetics screen previews. Registered on the player renderer through
 * Fabric's LivingEntityFeatureRendererRegistrationCallback (OriginClientMod).
 *
 * PER-VERSION DELTA (1.21.11, extract/submit era; the shape of Fabric API's
 * own 1.21.11 FeatureRendererTest): the player renderer is AvatarRenderer and
 * layers get the extracted AvatarRenderState in
 * submit(PoseStack, SubmitNodeCollector, int, S, float, float). The state has
 * no UUID, so the wearer is found by the state's entity id in the client
 * level -- exactly how LevelHeadMixin finds the tagged player. The geometry is
 * submitted as custom-geometry nodes (see HeadModels). PlayerModel moved to
 * model.player and is no longer generic.
 */
public final class CosmeticHeadLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
	private static boolean warned = false;

	public CosmeticHeadLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state,
			float yRot, float xRot) {
		if (state.isInvisible) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Entity player = mc.level == null ? null : mc.level.getEntity(state.id);
		if (player == null) {
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
			// neck at y=24. The model root goes first, as vanilla's own head
			// layers do since 1.21.2.
			getParentModel().root().translateAndRotate(pose);
			getParentModel().head.translateAndRotate(pose);
			pose.scale(1 / 16f, -1 / 16f, -1 / 16f);
			pose.translate(0, -24, 0);
			HeadModels.render(id, pose, collector, light, state.ageInTicks / 20f);
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
