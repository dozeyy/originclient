package com.origin.client.client.cosmetics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.Entity;

/**
 * Draws the player's equipped head cosmetic (halos, crown) on every player
 * model: other players in the world, you in third person, and the inventory
 * / Cosmetics screen previews. Registered on the player renderer through
 * Fabric's LivingEntityFeatureRendererRegistrationCallback (OriginClientMod).
 *
 * PER-VERSION (1.21.2-1.21.4 render-state era): layers draw from the extracted
 * PlayerRenderState (render(PoseStack, MultiBufferSource, int, S, float, float)),
 * which carries no UUID -- the state's entity id (PlayerRenderState.id) is looked
 * up in the client level, the way this module's LevelHeadMixin finds the tagged
 * player (the menu preview's state carries MenuPlayerPreview.STATE_ID instead
 * and names its head item itself). The animation clock is the state's ageInTicks, invisibility the
 * state's isInvisible, and PlayerModel is no longer generic. The head part is
 * already posed by the model's setupAnim(state) when layers run.
 */
public final class CosmeticHeadLayer extends RenderLayer<PlayerRenderState, PlayerModel> {
	private static boolean warned = false;

	public CosmeticHeadLayer(RenderLayerParent<PlayerRenderState, PlayerModel> parent) {
		super(parent);
	}

	@Override
	public void render(PoseStack pose, MultiBufferSource buffers, int light, PlayerRenderState state, float yRot, float xRot) {
		if (state.isInvisible) {
			return;
		}
		String id;
		if (state.id == MenuPlayerPreview.STATE_ID) {
			// the Cosmetics window's title-screen preview (no entity, maybe no level)
			id = MenuPlayerPreview.headId();
		} else {
			Minecraft mc = Minecraft.getInstance();
			if (mc.level == null) {
				return;
			}
			Entity player = mc.level.getEntity(state.id);
			if (player == null) {
				return;
			}
			id = CosmeticsService.lookOf(player.getUUID()).head();
		}
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
			HeadModels.render(id, pose, buffers, light, state.ageInTicks / 20f);
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
