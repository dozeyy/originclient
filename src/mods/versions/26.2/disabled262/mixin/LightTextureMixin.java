package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Full Bright: vanilla clamps the gamma option to [0,1], so setting it high did
// nothing (the reported "doesn't work"). updateLightTexture reads gamma via the
// SECOND OptionInstance.get() call (ordinal 1; the first is darknessEffectScale
// — verified against the 1.21.1 jar). We redirect just that read and feed the
// Boost Factor (1..10) into the lightmap's gamma curve, which brightens the
// whole world. Off = the real option value, untouched. require=1 so a target
// drift is loud in dev rather than a silent no-op (mixin config is require 0).
@Mixin(LightTexture.class)
public class LightTextureMixin {

	@Redirect(method = "updateLightTexture", require = 1,
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;", ordinal = 1))
	private Object originclient$fullbrightGamma(OptionInstance<Double> instance) {
		Object original = instance.get();
		if (Mods.on("fullbright")) {
			// Full Bright wins: flatten the lightmap to maximum brightness and
			// ignore Boost Factor entirely.
			if (Mods.bool("fullbright", "fullBright")) {
				return 15.0;
			}
			// Boost Factor is the fine control that only applies when Full Bright
			// is off; 1.0 = vanilla, higher pushes brightness up toward (but
			// below) full bright.
			double boost = Mods.num("fullbright", "gamma");
			if (boost > 1.0) {
				return boost;
			}
		}
		return original;
	}
}
