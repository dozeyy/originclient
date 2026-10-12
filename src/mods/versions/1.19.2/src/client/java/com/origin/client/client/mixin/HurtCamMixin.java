package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.Era;
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
// The body below is vanilla's (same private bobHurt(PoseStack, float) on every
// pre-1.20 version -- decompiled 1.19.4 / 1.19.3 / 1.19.1 / 1.18.1 verified; the
// era bits -- the hurt-direction getter vs field, the Damage Tilt slider that
// only exists from 1.19.4, the rotation axis class -- go through Era) with
// Lunar's three knobs:
//   Disable Hurt Cam     -- skip the damage shake (the death roll stays: that's
//                           how vanilla shows you died, not a hit effect)
//   Hurt Shake Intensity -- scales the 14-degree tilt, on top of vanilla's own
//                           Damage Tilt accessibility slider
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
			Era.rotZ(poseStack, 40.0F - 8000.0F / (death + 200.0F));
		}
		float hurt = (float) living.hurtTime - partialTick;
		if (hurt < 0.0F || Mods.bool("hurtcam", "disableHurtCam") || living.hurtDuration <= 0) {
			return;
		}
		hurt /= (float) living.hurtDuration;
		hurt = Mth.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
		float dir = Mods.bool("hurtcam", "oldTilt") ? 0.0F : Era.hurtDir(living);
		Era.rotY(poseStack, -dir);
		double strength = Era.damageTilt(mc.options) * Mods.num("hurtcam", "intensity");
		Era.rotZ(poseStack, (float) (-hurt * 14.0 * strength));
		Era.rotY(poseStack, dir);
	}
}
