package com.origin.client.client.mixin;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Instant Sneak. 1.21 Camera.tick() (javap-verified on this module's mapped jar):
//   eyeHeightOld = eyeHeight; eyeHeight += (entity.getEyeHeight() - eyeHeight) * 0.5F;
// so the view glides down over a few ticks when sneaking. At TAIL both fields snap to the
// target, so setup()'s lerp(eyeHeightOld, eyeHeight) is flat: the camera drops at once like 1.7.
// If the shadows stop matching, the mixin fails to apply and sneaking glides like vanilla.
@Mixin(Camera.class)
public class OldAnimationsCameraMixin {
	@Shadow private Entity entity;
	@Shadow private float eyeHeight;
	@Shadow private float eyeHeightOld;

	@Inject(method = "tick", at = @At("TAIL"))
	private void originclient$instantSneak(CallbackInfo ci) {
		if (entity != null && OldAnimations.on("instantSneak")) {
			eyeHeight = entity.getEyeHeight();
			eyeHeightOld = eyeHeight;
		}
	}
}
