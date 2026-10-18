package com.origin.client.client.mixin.social;

import com.origin.client.client.cosmetics.CapePhysics;
import com.origin.client.client.cosmetics.Cosmetics;
import com.origin.client.client.cosmetics.CosmeticsService;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cape physics for Origin capes. CapePhysics.render draws the whole cape
 * itself (a spring-chain of cloth segments, see its class doc) and returns
 * true, in which case vanilla's stiff version is cancelled. For vanilla /
 * Mojang / OptiFine capes, the "Cape physics" setting off, an invisible wearer
 * or ANY failure it returns false (or throws, which is swallowed here) and
 * vanilla LayerCape.doRenderLayer runs untouched.
 *
 * 1.8.9: LayerCape.doRenderLayer(AbstractClientPlayer, 7 floats) -- the full
 * descriptor, because the class also carries the generic bridge
 * doRenderLayer(EntityLivingBase, ...) (javap, ref-189 render lookup). HEAD and
 * RETURN only, in case OptiFine ever replaces the class. Registered in
 * originclient.social.mixins.json (defaultRequire 0: a failed apply leaves the
 * vanilla cape).
 */
@Mixin(LayerCape.class)
public abstract class CapePhysicsMixin {
	private static final String RENDER = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V";

	@Inject(method = RENDER, at = @At("HEAD"), cancellable = true)
	private void originclient$capePhysics(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
			float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
		CapePhysics.offsetPushed = false;
		try {
			CosmeticsService.Look look = CosmeticsService.lookOf(player.getUniqueID());
			if (look.hidesCape()) {
				ci.cancel();   // wings / a backpack replace the cape, whichever cape it is
				return;
			}
			if (CapePhysics.render(player, partialTicks, ageInTicks)) {
				ci.cancel();
				return;
			}
			float off = look.capeOffset();
			// the player's height tweak, only for an Origin cape (model y is down)
			float up = look.cape() != null ? look.adjust(Cosmetics.Slot.CAPE) : 0f;
			if (off > 0f || up != 0f) {
				// Vanilla draws the cape: move it back for items worn on the back, and up / down
				// by the player's tweak (undone at RETURN).
				GlStateManager.pushMatrix();
				GlStateManager.translate(0f, -up / 16f, off / 16f);
				CapePhysics.offsetPushed = true;
			}
		} catch (Throwable t) {
			// vanilla draws the cape instead
		}
	}

	@Inject(method = RENDER, at = @At("RETURN"))
	private void originclient$capeOffsetDone(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
			float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
		if (CapePhysics.offsetPushed) {
			CapePhysics.offsetPushed = false;
			GlStateManager.popMatrix();
		}
	}
}
