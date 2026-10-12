package com.origin.client.client.mixin;

import com.origin.client.client.cosmetics.CosmeticHeadLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts the head-cosmetic layer (halos, crown) on every player renderer. 1.21.1
 * and 1.17.1 do this through Fabric's LivingEntityFeatureRendererRegistrationCallback;
 * Fabric API 0.42.0+1.16 has no such event (it arrived with 1.17), so the layer
 * is added the way vanilla adds its own: addLayer at the end of PlayerRenderer's
 * (dispatcher, slim) constructor -- the one both skin models go through (the
 * one-argument constructor delegates to it). If this fails to apply, players
 * simply show no head cosmetics; capes are unaffected (CosmeticCapeMixin).
 */
@Mixin(PlayerRenderer.class)
public abstract class CosmeticHeadLayerMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
	protected CosmeticHeadLayerMixin(EntityRenderDispatcher dispatcher, PlayerModel<AbstractClientPlayer> model, float shadow) {
		super(dispatcher, model, shadow);
	}

	@Inject(method = "<init>(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;Z)V", at = @At("RETURN"))
	private void originclient$cosmeticHeadLayer(EntityRenderDispatcher dispatcher, boolean slim, CallbackInfo ci) {
		try {
			this.addLayer(new CosmeticHeadLayer(this));
		} catch (Throwable t) {
			// no head cosmetics on this renderer; the player still draws
		}
	}
}
