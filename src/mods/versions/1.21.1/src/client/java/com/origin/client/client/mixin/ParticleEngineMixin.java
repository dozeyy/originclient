package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.ParticleFilter;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particles mod, spawn side. Every rule lives in ParticleFilter so this hook and
// the firework-starter guard can't drift apart; here we only wire it in:
//   HEAD   — drop the spawn when the filter says hidden. Returning null is the
//            vanilla "didn't spawn" path (every vanilla caller ignores the
//            return EXCEPT FireworkParticles$Starter, which is why that starter
//            has its own guard).
//   RETURN — per-type Scale, Play Sound, and Multiplier>1 extra copies, applied
//            to the particle that was actually created.
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

	// Block-breaking / block-hit particles are spawned via destroy()/crack(),
	// NOT createParticle — so "Hide Block-Breaking Particle" (and Hide All) has
	// to gate these directly. This is the root cause of that toggle doing nothing.
	@Inject(method = "destroy", at = @At("HEAD"), cancellable = true)
	private void originclient$destroy(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (Mods.on("particles") && (Mods.bool("particles", "hideAll") || Mods.bool("particles", "hideBlockBreak"))) {
			ci.cancel();
		}
	}

	@Inject(method = "crack", at = @At("HEAD"), cancellable = true)
	private void originclient$crack(BlockPos pos, Direction direction, CallbackInfo ci) {
		if (Mods.on("particles") && (Mods.bool("particles", "hideAll") || Mods.bool("particles", "hideBlockBreak"))) {
			ci.cancel();
		}
	}
}
