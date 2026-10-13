package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Freelook's camera feed. EntityRenderer.orientCamera(partialTicks) builds the
// whole view from the render-view entity's rotation: the F5 orbit distance
// (raytraced back along that rotation) and the final yaw/pitch. While freelook
// is held, the player's rotation reads as the freelook camera's for the length
// of this one method (WorldHooks.beginCameraSwap at HEAD, restored at RETURN),
// so vanilla's own code orbits exactly like F5 does. Forge's CameraSetup event
// can only turn the view, not move the orbit, hence the swap. The player's real
// rotation -- body, head, aim, what the server sees -- is never changed.
// HEAD/RETURN: EntityRenderer is OptiFine-replaced.
@Mixin(EntityRenderer.class)
public abstract class FreelookCameraMixin {
	@Inject(method = "orientCamera", at = @At("HEAD"))
	private void originclient$swapIn(float partialTicks, CallbackInfo ci) {
		try {
			WorldHooks.beginCameraSwap(Minecraft.getMinecraft().getRenderViewEntity());
		} catch (Throwable ignored) {
			// vanilla camera
		}
	}

	@Inject(method = "orientCamera", at = @At("RETURN"))
	private void originclient$swapOut(float partialTicks, CallbackInfo ci) {
		WorldHooks.endCameraSwap();
	}
}
