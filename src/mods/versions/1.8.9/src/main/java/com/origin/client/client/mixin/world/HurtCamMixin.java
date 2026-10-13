package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hurt Cam: replaces EntityRenderer.hurtCameraEffect(float) while the mod is on.
// The body below is vanilla 1.8.9's (rotations on the GL model-view) with Lunar's
// three knobs:
//   Disable Hurt Cam     -- skip the damage shake (the death roll stays: that's
//                           how vanilla shows you died, not a hit effect)
//   Hurt Shake Intensity -- scales the 14-degree tilt
//   Old Camera Tilt      -- a fixed direction (hurt dir 0) instead of tilting
//                           away from the attacker (1.8.9 servers rarely send a
//                           direction, so this mostly matches vanilla here)
// Mod off = vanilla's method runs untouched. HEAD cancel: robust in OptiFine's
// replacement EntityRenderer.
@Mixin(EntityRenderer.class)
public abstract class HurtCamMixin {

	@Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
	private void originclient$hurtCam(float partialTicks, CallbackInfo ci) {
		if (!Mods.on("hurtcam")) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (!(mc.getRenderViewEntity() instanceof EntityLivingBase)) {
			return;   // vanilla does nothing for a non-living camera either
		}
		EntityLivingBase living = (EntityLivingBase) mc.getRenderViewEntity();
		ci.cancel();
		try {
			if (living.getHealth() <= 0.0F) {
				float death = (float) living.deathTime + partialTicks;
				GlStateManager.rotate(40.0F - 8000.0F / (death + 200.0F), 0.0F, 0.0F, 1.0F);
			}
			float hurt = (float) living.hurtTime - partialTicks;
			if (hurt < 0.0F || Mods.bool("hurtcam", "disableHurtCam") || living.maxHurtTime <= 0) {
				return;
			}
			hurt /= (float) living.maxHurtTime;
			hurt = MathHelper.sin(hurt * hurt * hurt * hurt * (float) Math.PI);
			float dir = Mods.bool("hurtcam", "oldTilt") ? 0.0F : living.attackedAtYaw;
			GlStateManager.rotate(-dir, 0.0F, 1.0F, 0.0F);
			double strength = Mods.num("hurtcam", "intensity");
			GlStateManager.rotate((float) (-hurt * 14.0 * strength), 0.0F, 0.0F, 1.0F);
			GlStateManager.rotate(dir, 0.0F, 1.0F, 0.0F);
		} catch (Throwable ignored) {
			// no shake this frame
		}
	}
}
