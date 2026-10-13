package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hurt Cam: replaces GameRenderer.bobHurt(PoseStack, float) while the mod is on.
// The body below is vanilla's (1.21.11 source-verified; unchanged since 1.21.1) with Lunar's three knobs:
//   Disable Hurt Cam     -- skip the damage shake (the death roll stays: that's
//                           how vanilla shows you died, not a hit effect)
//   Hurt Shake Intensity -- scales the 14-degree tilt, on top of vanilla's own
//                           Damage Tilt accessibility slider
//   Old Camera Tilt      -- 1.8 behaviour: a fixed direction (hurt dir 0)
//                           instead of tilting away from the attacker
// Mod off = vanilla's method runs untouched.
@Mixin(GameRenderer.class)
public class HurtCamMixin {

	// 26.2: bobHurt(CameraRenderState, PoseStack) reads the camera entity's
	// extracted CameraEntityRenderState (hurtTime/deathTime are already
	// partial-tick interpolated there), not the live entity.
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void originclient$hurtCam(CameraRenderState camera, PoseStack poseStack, CallbackInfo ci) {
		if (!Mods.on("hurtcam")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		CameraEntityRenderState living = camera.entityRenderState;
		if (living == null || !living.isLiving) {
			return;   // vanilla does nothing for a non-living camera either
		}
		ci.cancel();
		if (living.isDeadOrDying) {
			float death = Math.min(living.deathTime, 20.0F);
			poseStack.rotateDegrees(Axis.ZP, 40.0F - 8000.0F / (death + 200.0F));
		}
		float hurt = living.hurtTime;
		if (hurt < 0.0F || Mods.bool("hurtcam", "disableHurtCam") || living.hurtDuration <= 0) {
			return;
		}
		hurt /= (float) living.hurtDuration;
		hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
		float dir = Mods.bool("hurtcam", "oldTilt") ? 0.0F : living.hurtDir;
		poseStack.rotateDegrees(Axis.YP, -dir);
		double strength = mc.options.damageTiltStrength().get() * Mods.num("hurtcam", "intensity");
		poseStack.rotateDegrees(Axis.ZP, (float) (-hurt * 14.0 * strength));
		poseStack.rotateDegrees(Axis.YP, dir);
	}
}
