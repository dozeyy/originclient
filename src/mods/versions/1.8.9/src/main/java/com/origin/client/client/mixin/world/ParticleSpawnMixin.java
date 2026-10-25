package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.ParticleFilter;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Particles mod, spawn side. Every rule lives in ParticleFilter so this hook
// matches the newer versions exactly:
//   HEAD   — drop the spawn when the filter says hidden ("Off"/"Hide All", the
//            per-type rows, Show on Self/Players/Entities, Multiplier<1).
//            Returning null is vanilla's own "no factory for that id" path.
//   RETURN — per-type Scale, Play Sound, and Multiplier>1 extra copies.
// 1.8.9: every world particle (RenderGlobal.spawnParticle) goes through
// EffectRenderer.spawnEffectParticle(id, x, y, z, vx, vy, vz, params...).
// Block-breaking / block-hit particles are spawned by addBlockDestroyEffects /
// addBlockHitEffects instead, so "Hide Block-Breaking Particle" (and Hide All)
// gates those directly -- including Forge's addBlockHitEffects(pos, MOP)
// overload (a Forge-added method: remap = false).
@Mixin(EffectRenderer.class)
public abstract class ParticleSpawnMixin {

	@Inject(method = "spawnEffectParticle", at = @At("HEAD"), cancellable = true)
	private void originclient$filterParticles(int particleId, double x, double y, double z,
											  double xSpeed, double ySpeed, double zSpeed, int[] params,
											  CallbackInfoReturnable<EntityFX> cir) {
		try {
			if (com.origin.client.client.BenchProf.ON) {
				com.origin.client.client.BenchProf.countParticleSpawn();
			}
			if (ParticleFilter.hidden(particleId, x, y, z)) {
				cir.setReturnValue(null);
			}
		} catch (Throwable ignored) {
			// the particle spawns as vanilla
		}
	}

	@Inject(method = "spawnEffectParticle", at = @At("RETURN"))
	private void originclient$tweakSpawn(int particleId, double x, double y, double z,
										 double xSpeed, double ySpeed, double zSpeed, int[] params,
										 CallbackInfoReturnable<EntityFX> cir) {
		try {
			ParticleFilter.afterSpawn((EffectRenderer) (Object) this, cir.getReturnValue(), particleId,
					x, y, z, xSpeed, ySpeed, zSpeed, params);
		} catch (Throwable ignored) {
			// no per-type extras this spawn
		}
	}

	@Inject(method = "addBlockDestroyEffects", at = @At("HEAD"), cancellable = true)
	private void originclient$destroy(BlockPos pos, IBlockState state, CallbackInfo ci) {
		if (ParticleFilter.hideBlockBreak()) {
			ci.cancel();
		}
	}

	@Inject(method = "addBlockHitEffects(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/EnumFacing;)V",
			at = @At("HEAD"), cancellable = true)
	private void originclient$crack(BlockPos pos, EnumFacing side, CallbackInfo ci) {
		if (ParticleFilter.hideBlockBreak()) {
			ci.cancel();
		}
	}

	@Inject(method = "addBlockHitEffects(Lnet/minecraft/util/BlockPos;Lnet/minecraft/util/MovingObjectPosition;)V",
			at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$crackForge(BlockPos pos, MovingObjectPosition target, CallbackInfo ci) {
		if (ParticleFilter.hideBlockBreak()) {
			ci.cancel();
		}
	}
}
