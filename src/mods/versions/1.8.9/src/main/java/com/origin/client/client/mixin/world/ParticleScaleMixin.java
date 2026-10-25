package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.ParticleFilter;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Particle Changer — global Scale slider. 1.8.9 has no getQuadSize: every
// textured particle sizes its quad from EntityFX.particleScale inside
// renderParticle. The slider multiplies that field for the duration of the draw
// (HEAD) and puts it back (RETURN), so it previews live while you drag and never
// accumulates. Subclasses that override renderParticle WITHOUT calling super
// (the few special ones: footsteps, the large explosion, item pickup) keep
// their vanilla size.
@Mixin(EntityFX.class)
public abstract class ParticleScaleMixin {
	@Shadow
	protected float particleScale;

	private float originclient$savedScale;
	private boolean originclient$scaled;

	@Inject(method = "renderParticle", at = @At("HEAD"))
	private void originclient$scaleIn(WorldRenderer wr, Entity viewer, float partialTicks, float rotX, float rotZ,
									  float rotYZ, float rotXY, float rotXZ, CallbackInfo ci) {
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countParticleRender();
		}
		float s = ParticleFilter.globalScale();
		if (s != 1f) {
			originclient$savedScale = particleScale;
			originclient$scaled = true;
			particleScale *= s;
		}
	}

	@Inject(method = "renderParticle", at = @At("RETURN"))
	private void originclient$scaleOut(WorldRenderer wr, Entity viewer, float partialTicks, float rotX, float rotZ,
									   float rotYZ, float rotXY, float rotXZ, CallbackInfo ci) {
		if (originclient$scaled) {
			particleScale = originclient$savedScale;
			originclient$scaled = false;
		}
	}
}
