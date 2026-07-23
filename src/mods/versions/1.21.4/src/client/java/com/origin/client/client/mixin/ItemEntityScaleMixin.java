package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.ext.ItemScaleState;
import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Item Size Customizer: scales a dropped item's render by the player's per-item
// size. 1.21.4 is the render-state era for item entities — render() no longer
// receives the live ItemEntity, so the scale is computed at extractRenderState
// time (which still has the entity) and stashed on the render state (ItemScaleState),
// then applied by push/pop around render(). Push at HEAD, pop at RETURN are ALWAYS
// balanced (unconditional) so they can never desync; scaling by the stashed factor
// (1.0 when the mod is off) is the only conditional part. Render-only — gameplay,
// hitbox and pickup are untouched.
@Mixin(ItemEntityRenderer.class)
public class ItemEntityScaleMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("RETURN"))
	private void originclient$extractSize(ItemEntity entity, ItemEntityRenderState state, float partialTick, CallbackInfo ci) {
		float s = ItemSizes.DEFAULT;
		if (Mods.on("itemsize")) {
			s = ItemSizes.get(BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()));
		}
		((ItemScaleState) state).originclient$setItemScale(s);
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"))
	private void originclient$pushSize(ItemEntityRenderState state, PoseStack poseStack,
									   MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		poseStack.pushPose();
		float s = ((ItemScaleState) state).originclient$getItemScale();
		if (s > 0f && s != ItemSizes.DEFAULT) {
			poseStack.scale(s, s, s);
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("RETURN"))
	private void originclient$popSize(ItemEntityRenderState state, PoseStack poseStack,
									  MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		poseStack.popPose();
	}
}
