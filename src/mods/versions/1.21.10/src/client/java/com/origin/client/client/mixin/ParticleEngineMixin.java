package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particles mod: "Off" suppresses all particle spawns, "Reduced" only the
// expensive/chaotic categories (explosions, potion clouds, crits, firework
// spam) — the moments where particles actually cost frames. createParticle
// returning null is the vanilla "didn't spawn" path, so this is safe.
@Mixin(ParticleEngine.class)
public class ParticleEngineMixin {

	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void originclient$filterParticles(ParticleOptions options, double x, double y, double z,
											  double xSpeed, double ySpeed, double zSpeed,
											  CallbackInfoReturnable<Particle> cir) {
		if (!Mods.on("particles")) {
			return;
		}
		if (Mods.bool("particles", "hideAll")) {
			cir.setReturnValue(null);
			return;
		}
		// Hide particles that spawn right next to you in first person.
		if (Mods.bool("particles", "hideFirstPerson")) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.options.getCameraType().isFirstPerson()
					&& mc.player.getEyePosition().distanceToSqr(x, y, z) < 4.0) {
				cir.setReturnValue(null);
				return;
			}
		}
		var typeKey = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType());
		if (typeKey != null) {
			String path = typeKey.getPath();
			// per-particle-type controls: master row toggle off, or its Hide flag
			// (only for types that actually have a row — unknown types pass through)
			if (Mods.hasOption("particles", "p_" + path) && !Mods.bool("particles", "p_" + path)) {
				cir.setReturnValue(null);
				return;
			}
			if (Mods.bool("particles", "p_" + path + "_hide")) {
				cir.setReturnValue(null);
				return;
			}
			if (path.equals("block") && Mods.bool("particles", "hideBlockBreak")) {
				cir.setReturnValue(null);
				return;
			}
		}
		String mode = Mods.mode("particles", "mode");
		if (mode.equals("Off")) {
			cir.setReturnValue(null);
			return;
		}
		if (mode.equals("Reduced")) {
			var t = options.getType();
			if (t == ParticleTypes.EXPLOSION || t == ParticleTypes.EXPLOSION_EMITTER
					|| t == ParticleTypes.POOF || t == ParticleTypes.CRIT
					|| t == ParticleTypes.ENCHANTED_HIT || t == ParticleTypes.EFFECT
					|| t == ParticleTypes.ENTITY_EFFECT || t == ParticleTypes.FIREWORK
					|| t == ParticleTypes.LARGE_SMOKE) {
				cir.setReturnValue(null);
			}
		}
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
