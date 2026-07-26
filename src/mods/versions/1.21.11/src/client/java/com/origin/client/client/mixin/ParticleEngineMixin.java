package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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

	// NOTE (1.21.11): the old destroy()/crack() injects that gated block-breaking
	// and block-hit bursts USED to live here. Both methods were removed from
	// ParticleEngine in this era — the bursts now spawn through
	// ClientLevel.addDestroyBlockEffect / addBreakingBlockEffect, and
	// ClientLevelParticleMixin already gates them there with the same rules. The
	// injects were left behind pointing at names that no longer exist, so (with
	// "defaultRequire": 0) they were silently skipped dead weight. Removed
	// 2026-07-26. Do not re-add them here; the ClientLevel hooks are the ones
	// that work on this version.
}
