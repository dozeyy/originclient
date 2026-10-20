package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > Performance > Limit Far Shadows, block-entity half (render/ShadowCasterLimit).
// Target: BlockEntityRenderDispatcher.render(BlockEntity, float, PoseStack, MultiBufferSource)V
//   at HEAD (javap, 1.21.6 and 1.21.8 Mojmap, unchanged from 1.21.1; generic <E extends BlockEntity>, erased).
// Why: with a shader pack, Iris draws block entities into the shadow map through Sodium 0.7.3
//   SodiumWorldRenderer.renderBlockEntity (bytecode offset 158) or, without Sodium, Iris's own
//   ShadowRenderer.renderBlockEntities (offset 232 in Iris 1.9.6+mc1.21.8) -- both end in this call, and Sodium's path
//   has no distance cull at all. Cancelling at HEAD skips the chest/sign/banner geometry build.
//   Outside the shadow pass ShadowCasterLimit always answers "keep".
// What breaks if it stops applying: nothing visible -- far block entities keep casting shadows.
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ShadowCasterBlockEntityMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void originclient$farShadowCaster(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
			MultiBufferSource bufferSource, CallbackInfo ci) {
		if (ShadowCasterLimit.skipBlockEntity(blockEntity)) {
			ci.cancel();
		}
	}
}
