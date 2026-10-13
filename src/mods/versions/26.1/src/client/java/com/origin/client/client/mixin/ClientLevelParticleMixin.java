package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.21.10+: ParticleEngine.destroy()/crack() were REMOVED — block-break and
// block-hit particle bursts now spawn through ClientLevel.addDestroyBlockEffect
// (full break) and addBreakingBlockEffect (punch cracks). Those bursts create
// TerrainParticles directly (never through createParticle), so the
// ParticleEngineMixin filter can't see them — this mixin gates them at the
// source. Same rules as the old destroy/crack hooks: Hide All or Hide
// Block-Breaking Particles suppresses the burst.
@Mixin(ClientLevel.class)
public class ClientLevelParticleMixin {

	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void originclient$hideDestroyBurst(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (Mods.on("particles") && (Mods.bool("particles", "hideAll") || Mods.bool("particles", "hideBlockBreak"))) {
			ci.cancel();
		}
	}

	@Inject(method = "addBreakingBlockEffect", at = @At("HEAD"), cancellable = true)
	private void originclient$hideCrackBurst(BlockPos pos, Direction direction, CallbackInfo ci) {
		if (Mods.on("particles") && (Mods.bool("particles", "hideAll") || Mods.bool("particles", "hideBlockBreak"))) {
			ci.cancel();
		}
	}
}
