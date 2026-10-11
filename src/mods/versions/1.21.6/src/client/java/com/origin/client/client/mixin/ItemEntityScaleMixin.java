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
// size. Gameplay, hitbox and pickup are untouched -- this is render-only.
//
// PER-VERSION DELTA (1.21.6-1.21.8): 1.21.1 did the whole thing in one hook,
// because its render(ItemEntity, ...) still had the live entity. Here rendering is
// split: extractRenderState(ItemEntity, ItemEntityRenderState, float) has the
// entity, render(ItemEntityRenderState, PoseStack, MultiBufferSource, int) has only
// the baked state (shape jar-verified for 1.21.4-1.21.8, MEMORY "Item Size:
// attempted, reverted"). So the scale is resolved during extraction and stashed on
// the state through the ItemScaleState duck. Full descriptors pin the concrete
// methods, not the generic EntityRenderer bridges.
//
// The push/pop pair is UNCONDITIONAL (as on 1.21.11): toggling the mod between the
// HEAD and the RETURN of one call can then never unbalance the pose stack.
@Mixin(ItemEntityRenderer.class)
public class ItemEntityScaleMixin {

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"))
	private void originclient$captureSize(ItemEntity entity, ItemEntityRenderState state, float partialTick,
										  CallbackInfo ci) {
		float s = 1.0f;
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
		if (s != ItemSizes.DEFAULT) {
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
