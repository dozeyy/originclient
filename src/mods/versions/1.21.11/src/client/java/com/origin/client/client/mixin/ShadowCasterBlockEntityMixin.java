package com.origin.client.client.mixin;

import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > Performance > Limit Far Shadows, block-entity half (render/ShadowCasterLimit).
// Target: BlockEntityRenderDispatcher.submit(BlockEntityRenderState, PoseStack, SubmitNodeCollector,
//   CameraRenderState)V at HEAD (javap of the 1.21.11 Mojmap jar 2026-10-20; render(...) is gone since
//   the extract/submit split -- BlockEntityRenderDispatcherMixin hooks the same method for Tile Entity
//   Distance). The state is the only argument read, picked by type (@Local argsOnly), so the handler
//   does not restate Minecraft's argument list.
// Why: with a shader pack, Iris 1.10.7's shadow pass extracts the block entities of every section in
//   Sodium 0.8.12's shadow render list and submits each one here; Sodium's path has no distance cull.
//   Cancelling at HEAD skips queueing the chest/sign/banner geometry. Outside the shadow pass
//   ShadowCasterLimit always answers "keep".
// What breaks if it stops applying: nothing visible -- far block entities keep casting shadows.
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ShadowCasterBlockEntityMixin {
	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void originclient$farShadowCaster(CallbackInfo ci,
			@com.llamalad7.mixinextras.sugar.Local(argsOnly = true) BlockEntityRenderState state) {
		if (ShadowCasterLimit.skipBlockEntity(state.blockPos)) {
			ci.cancel();
		}
	}
}
