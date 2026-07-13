package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particle Changer — global Scale slider. getQuadSize is the render size every
// textured particle uses, so multiplying it here scales all particles at once
// (the schema's Scale was never wired to anything before).
@Mixin(SingleQuadParticle.class)
public class SingleQuadParticleMixin {

	@Inject(method = "getQuadSize", at = @At("RETURN"), cancellable = true)
	private void originclient$scale(float partialTick, CallbackInfoReturnable<Float> cir) {
		if (Mods.on("particles")) {
			double s = Mods.num("particles", "scale");
			if (s > 0 && Math.abs(s - 1.0) > 0.001) {
				cir.setReturnValue((float) (cir.getReturnValueF() * s));
			}
		}
	}
}
