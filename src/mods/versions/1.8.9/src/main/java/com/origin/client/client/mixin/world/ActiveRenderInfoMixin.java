package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Freelook, particles: ActiveRenderInfo.updateRenderInfo(player, mirrored) caches
// the camera matrices AND the billboard rotation every particle faces with, from
// the player's rotation. While freelook is held the camera's rotation is swapped
// in for this call too (same swap as FreelookCameraMixin), so particles keep
// facing the freelook camera instead of where the body looks.
@Mixin(ActiveRenderInfo.class)
public abstract class ActiveRenderInfoMixin {
	@Inject(method = "updateRenderInfo", at = @At("HEAD"))
	private static void originclient$swapIn(EntityPlayer player, boolean mirrored, CallbackInfo ci) {
		try {
			WorldHooks.beginCameraSwap(player);
		} catch (Throwable ignored) {
			// vanilla orientation
		}
	}

	@Inject(method = "updateRenderInfo", at = @At("RETURN"))
	private static void originclient$swapOut(EntityPlayer player, boolean mirrored, CallbackInfo ci) {
		WorldHooks.endCameraSwap();
	}
}
