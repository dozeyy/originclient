package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.cosmetics.CapePhysics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cape physics for Origin capes. CapePhysics.render draws the whole cape
 * itself (a spring-chain of cloth segments, see its class doc) and returns
 * true, in which case vanilla's stiff version is cancelled. For vanilla /
 * Mojang capes, the "Cape physics" setting off, elytra, an invisible wearer
 * or ANY failure it returns false (or throws, which is swallowed here) and
 * vanilla CapeLayer.render runs untouched.
 *
 * 1.21.1 CapeLayer.render(PoseStack, MultiBufferSource, int, AbstractClientPlayer,
 * float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
 * float netHeadYaw, float headPitch) -- the same shape as the RenderLayer.render
 * override in cosmetics/CosmeticHeadLayer.
 */
@Mixin(CapeLayer.class)
public abstract class CapePhysicsMixin {
	@Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
			at = @At("HEAD"), cancellable = true)
	private void originclient$capePhysics(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch,
			CallbackInfo ci) {
		try {
			if (CapePhysics.render(pose, buffers, light, player, partialTick, ageInTicks)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// vanilla draws the cape instead
		}
	}
}
