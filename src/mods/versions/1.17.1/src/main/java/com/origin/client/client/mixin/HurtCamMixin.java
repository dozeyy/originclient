package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Vector3f;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hurt Cam: replaces GameRenderer.bobHurt(PoseStack, float) while the mod is on.
// The body below is vanilla's (1.16.4 and 1.18.1 decompiled source are identical
// here, so 1.17.1 is too) with Lunar's three knobs:
//   Disable Hurt Cam     -- skip the damage shake (the death roll stays: that's
//                           how vanilla shows you died, not a hit effect)
//   Hurt Shake Intensity -- scales the 14-degree tilt (1.17.1 has no Damage Tilt
//                           accessibility slider to stack it on; that's 1.19.4+)
//   Old Camera Tilt      -- 1.8 behaviour: a fixed direction (hurt dir 0)
//                           instead of tilting away from the attacker
// Mod off = vanilla's method runs untouched.
@Mixin(GameRenderer.class)
public class HurtCamMixin {

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void originclient$hurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (!Mods.on("hurtcam")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!(mc.getCameraEntity() instanceof LivingEntity living)) {
			return;   // vanilla does nothing for a non-living camera either
		}
		ci.cancel();
		if (living.isDeadOrDying()) {
			float death = Math.min((float) living.deathTime + partialTick, 20.0F);
			poseStack.mulPose(Vector3f.ZP.rotationDegrees(40.0F - 8000.0F / (death + 200.0F)));
		}
		float hurt = (float) living.hurtTime - partialTick;
		if (hurt < 0.0F || Mods.bool("hurtcam", "disableHurtCam") || living.hurtDuration <= 0) {
			return;
		}
		hurt /= (float) living.hurtDuration;
		hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
		float dir = Mods.bool("hurtcam", "oldTilt") ? 0.0F : living.hurtDir;
		poseStack.mulPose(Vector3f.YP.rotationDegrees(-dir));
		double strength = Mods.num("hurtcam", "intensity");
		poseStack.mulPose(Vector3f.ZP.rotationDegrees((float) (-hurt * 14.0 * strength)));
		poseStack.mulPose(Vector3f.YP.rotationDegrees(dir));
	}
}
