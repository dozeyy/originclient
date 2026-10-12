package com.origin.client.client.mixin;

import com.origin.client.client.mods.ParticleFilter;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particle Changer spawn filter. Every rule lives in ParticleFilter (shared) so
// this hook can't drift from the other versions:
//   HEAD   — drop the spawn when the filter says hidden ("Off"/"Hide All", the
//            per-type rows, Show on Self/Players/Entities, Multiplier<1).
//            Returning null is the vanilla "didn't spawn" path.
//   RETURN — per-type Scale, Play Sound, and Multiplier>1 extra copies.
//
// 26.2 note: ParticleEngine.destroy(BlockPos,BlockState)/crack(BlockPos,Direction)
// — the old block-break-burst hooks — were removed here (that spawn path moved),
// so "Hide Block-Breaking Particle" only covers block particles that route
// through createParticle for now; the break-burst hook is a follow-up.
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void originclient$filterParticles(ParticleOptions options, double x, double y, double z,
											  double xSpeed, double ySpeed, double zSpeed,
											  CallbackInfoReturnable<Particle> cir) {
		if (ParticleFilter.hidden(options, x, y, z)) {
			cir.setReturnValue(null);
		}
	}

	@Inject(method = "createParticle", at = @At("RETURN"))
	private void originclient$tweakSpawn(ParticleOptions options, double x, double y, double z,
										 double xSpeed, double ySpeed, double zSpeed,
										 CallbackInfoReturnable<Particle> cir) {
		ParticleFilter.afterSpawn((ParticleEngine) (Object) this, cir.getReturnValue(), options,
				x, y, z, xSpeed, ySpeed, zSpeed);
	}
}
