package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// SETTINGS > Performance > Limit Far Shadows, block-entity half (render/ShadowCasterLimit).
// Target: BlockEntityRenderDispatcher.tryExtractRenderState(BlockEntity, float, CrumblingOverlay, boolean),
//   its getRenderer(BlockEntity) call at offset 2, which the method null-checks at offset 9 and then
//   returns null ("no renderer, nothing to draw") -- javap of 26.2, 2026-10-20.
// Why: on 26.2 block entities are extracted into render states, then submitted. Sodium 0.9.2's
//   extractBlockEntity sends every block entity of every section in the SHADOW render list through this
//   method; answering "no renderer" there for far ones skips their extract and draw into the shadow map.
//   Outside Iris's shadow pass skipBlockEntity always answers false, so the normal view is untouched.
//   The block entity comes in through @Local(argsOnly = true), no declared argument list.
// What breaks if it stops applying: nothing visible -- far chests/signs keep casting shadows, the F3
//   line stays at 0 skipped. Coexists with BlockEntityRenderDispatcherMixin (submit HEAD).
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class ShadowCasterBlockEntityMixin {
	@ModifyExpressionValue(method = "tryExtractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;getRenderer(Lnet/minecraft/world/level/block/entity/BlockEntity;)Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;"))
	private BlockEntityRenderer<?, ?> originclient$farShadowCaster(BlockEntityRenderer<?, ?> renderer,
			@Local(argsOnly = true) BlockEntity be) {
		if (renderer != null && ShadowCasterLimit.skipBlockEntity(be)) {
			return null;
		}
		return renderer;
	}
}