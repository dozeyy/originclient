package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Particle Changer — "Hide Block-Breaking Particle" (and Hide All) for the
// block-break BURST. 26.2 moved that spawn off ParticleEngine.destroy onto
// ClientLevel.addDestroyBlockEffect(BlockPos, BlockState), so we gate it here
// instead. Cancelling only skips the cosmetic burst; the block still breaks.
@Mixin(ClientLevel.class)
public abstract class ClientLevelParticleMixin {
	@Inject(method = "addDestroyBlockEffect", at = @At("HEAD"), cancellable = true)
	private void originclient$hideBlockBreak(BlockPos pos, BlockState state, CallbackInfo ci) {
		if (Mods.on("particles") && (Mods.bool("particles", "hideAll") || Mods.bool("particles", "hideBlockBreak"))) {
			ci.cancel();
		}
	}
}
