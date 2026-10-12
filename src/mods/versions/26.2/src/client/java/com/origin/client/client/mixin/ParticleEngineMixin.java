package com.origin.client.client.mixin;

import com.origin.client.client.mods.ParticleFilter;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particles mod, spawn side. Every rule lives in ParticleFilter so this hook and
// FireworkStarterMixin can't drift apart; here we only wire it in:
//   HEAD   — drop the spawn when the filter says hidden. Returning null is the
//            vanilla "didn't spawn" path, and every vanilla caller ignores the
//            return EXCEPT FireworkParticles$Starter, which dereferences it
//            unchecked — FireworkStarterMixin keeps that caller from ever
//            reaching this method when the spark is filtered (fixed 2026-08-01).
//   RETURN — per-type Scale, Play Sound, and Multiplier>1 extra copies.
//
// NOTE (1.21.11): the old destroy()/crack() injects that gated block-breaking
// and block-hit bursts USED to live here. Both methods were removed from
// ParticleEngine in this era — the bursts now spawn through
// ClientLevel.addDestroyBlockEffect / addBreakingBlockEffect, and
// ClientLevelParticleMixin already gates them there with the same rules. The
// injects were left behind pointing at names that no longer exist, so (with
// "defaultRequire": 0) they were silently skipped dead weight. Removed
// 2026-07-26. Do not re-add them here; the ClientLevel hooks are the ones
// that work on this version.
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
