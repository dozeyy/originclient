package com.origin.client.client.mixin;

import com.origin.client.client.mods.ParticleFilter;

import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crash fix (2026-08-01): hiding firework particles used to kill the game.
 *
 * FireworkParticles$Starter is the invisible NO_RENDER particle that drives a
 * firework explosion. Vanilla spawns it directly through ParticleEngine.add, so
 * ParticleEngineMixin never sees it — and on every tick of the burst it does:
 *
 *     SparkParticle spark = (SparkParticle) engine.createParticle(FIREWORK, ...);
 *     spark.setTrail(...);            // <- no null check
 *
 * so the moment the Particles mod filtered a spark to null (Hide All Particles,
 * mode Off/Reduced, or the per-type firework row), the next firework anywhere
 * near the player crashed the client with "Ticking Particle" NPE. Root-caused
 * from Will's 1.21.11 server crash; the same latent crash was in every version.
 *
 * Fix: stop the starter before it asks for a spark it isn't allowed to get. The
 * explosion sound still plays — Starter.tick keeps running, only the spark spawn
 * is skipped — so hiding particles stays a purely visual change.
 *
 * This is the ONLY vanilla call site that dereferences the result of
 * ParticleEngine.createParticle (audited against this version's sources).
 */
@Mixin(targets = "net.minecraft.client.particle.FireworkParticles$Starter")
public class FireworkStarterMixin {

	// require = 1: if this ever stops matching, fail LOUDLY at launch instead of
	// silently going missing and bringing the crash back (the mixin config runs
	// with "defaultRequire": 0).
	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true, require = 1)
	private void originclient$skipFilteredSpark(double x, double y, double z,
												double xSpeed, double ySpeed, double zSpeed,
												int[] colors, int[] fadeColors,
												boolean trail, boolean twinkle, CallbackInfo ci) {
		if (ParticleFilter.hidden(ParticleTypes.FIREWORK, x, y, z)) {
			ci.cancel();
		}
	}
}
