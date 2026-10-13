package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Freelook, terrain: 1.8.9's RenderGlobal.setupTerrain re-walks its visible
// chunk list only when the VIEW ENTITY moves or turns (lastViewEntityYaw/Pitch),
// and aims the walk with the entity's view vector. Freelook turns only the
// camera, so without this the chunks behind you stayed undrawn while you looked
// round. The camera's rotation is swapped in for the call (the same swap as
// FreelookCameraMixin), so the walk sees the camera turn. HEAD/RETURN: RenderGlobal
// is OptiFine-replaced.
@Mixin(RenderGlobal.class)
public abstract class FreelookTerrainMixin {
	@Inject(method = "setupTerrain", at = @At("HEAD"))
	private void originclient$swapIn(Entity viewEntity, double partialTicks, ICamera camera, int frameCount,
									 boolean playerSpectator, CallbackInfo ci) {
		try {
			WorldHooks.beginCameraSwap(viewEntity);
		} catch (Throwable ignored) {
			// vanilla walk
		}
	}

	@Inject(method = "setupTerrain", at = @At("RETURN"))
	private void originclient$swapOut(Entity viewEntity, double partialTicks, ICamera camera, int frameCount,
									  boolean playerSpectator, CallbackInfo ci) {
		WorldHooks.endCameraSwap();
	}
}
