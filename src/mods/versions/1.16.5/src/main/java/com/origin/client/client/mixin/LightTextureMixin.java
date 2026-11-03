package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.LightTexture;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Full Bright: vanilla clamps the gamma option to [0,1], so setting it high did
// nothing (the reported "doesn't work"). 1.18.2 predates OptionInstance —
// Options.gamma is a plain public double field, and updateLightTexture reads it
// with a single GETFIELD (bytecode-verified: exactly one Options.gamma read in
// the method). We modify that field read and feed the Boost Factor (1..10)
// into the lightmap's gamma curve, which brightens the whole world. Off = the
// real option value, untouched. require=1 so a target drift is loud in dev
// rather than a silent no-op (mixin config is require 0).
// @ModifyExpressionValue, not @Redirect: fullbright/gamma mods hook this same
// read, and two @Redirects on one target cannot coexist (the second finds 0
// targets and, with require = 1, crashes the game at launch). Modifiers chain.
@Mixin(LightTexture.class)
public class LightTextureMixin {

	@ModifyExpressionValue(method = "updateLightTexture", require = 1,
			at = @At(value = "FIELD",
					target = "Lnet/minecraft/client/Options;gamma:D", opcode = Opcodes.GETFIELD))
	private double originclient$fullbrightGamma(double original) {
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
