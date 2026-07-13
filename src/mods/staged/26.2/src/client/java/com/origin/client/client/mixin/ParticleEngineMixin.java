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

// Particle Changer spawn filter. createParticle returning null is the vanilla
// "didn't spawn" path, so cancelling here is safe: "Off"/"Hide All" suppress
// every spawn, "Reduced" only the expensive/chaotic categories (explosions,
// potion clouds, crits, firework spam), plus the per-type row toggles.
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
}
