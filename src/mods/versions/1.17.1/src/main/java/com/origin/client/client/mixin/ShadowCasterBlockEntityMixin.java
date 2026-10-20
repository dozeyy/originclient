package com.origin.client.client.mixin;

import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > Performance > Limit Far Shadows, block-entity half (render/ShadowCasterLimit).
// Target: BlockEntityRenderDispatcher.render(BlockEntity, float, PoseStack, MultiBufferSource)V at HEAD
//   (vanilla 1.17.1 Mojmap; BlockEntityRenderDispatcherMixin already hooks the same method).
// Why: with a shader pack, Iris 1.2.7 ShadowRenderer.renderBlockEntities runs every block entity of the
//   shadow walk (ShadowRenderer.visibleBlockEntities, captured from Sodium's walk) through this call
//   (javap 2026-10-20). Cancelling at HEAD skips the chest/sign/banner geometry build. Outside the
//   shadow pass ShadowCasterLimit always answers "keep".
// Only the BlockEntity argument is needed, so it is the only one taken (@Local argsOnly).
// What breaks if it stops applying: nothing visible -- far block entities keep casting shadows.
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ShadowCasterBlockEntityMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$farShadowCaster(CallbackInfo ci,
			@com.llamalad7.mixinextras.sugar.Local(argsOnly = true) BlockEntity blockEntity) {
		if (ShadowCasterLimit.skipBlockEntity(blockEntity)) {
			ci.cancel();
		}
	}
}
