package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.ext.ItemPhysicsState;
import com.origin.client.client.mods.ItemPhysics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Item Physics: re-poses a dropped item right before vanilla draws its copies.
// PER-VERSION (1.21.2-1.21.4, verified against the decompiled 1.21.4
// ItemEntityRenderer): rendering is split into
//   extractRenderState(ItemEntity, ItemEntityRenderState, float) -- has the entity:
//       records whether it is resting and the item's easing memory
//       (ItemPhysicsState), the inputs the state lacks (it already carries
//       ageInTicks, bobOffset, count, the model);
//   render(ItemEntityRenderState, PoseStack, MultiBufferSource, int) -- push,
//       translate(bob), rotate(spin), renderMultipleFromCount(...), pop.
// Injecting before that static call sees vanilla's bob + spin on the pose, which
// ItemPhysics undoes and replaces -- the same point 1.21.1 hooked. Everything
// stays inside vanilla's own push/pop, and Item Size's scale (pushed at HEAD by
// ItemEntityScaleMixin) still applies on top. Full descriptors keep both hooks
// off the generic EntityRenderer bridge methods.
@Mixin(ItemEntityRenderer.class)
public class ItemPhysicsMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"))
	private void originclient$captureResting(ItemEntity entity, ItemEntityRenderState state, float partialTick,
											 CallbackInfo ci) {
		ItemPhysicsState ext = (ItemPhysicsState) state;
		ext.originclient$setResting(entity.onGround() || entity.isInWater());
		// The state object is reused for every dropped item, so the item's own
		// easing memory rides along with it from here to render().
		try {
			ext.originclient$setAnim(ItemPhysics.animFor(entity));
		} catch (Throwable t) {
			ext.originclient$setAnim(null);   // vanilla float + spin this frame
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;renderMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;)V"))
	private void originclient$physics(ItemEntityRenderState state, PoseStack poseStack, MultiBufferSource buffers,
									  int packedLight, CallbackInfo ci) {
		try {
			ItemPhysics.apply(state, poseStack);
		} catch (Throwable ignored) {
			// worst case the item keeps vanilla's float + spin this frame
		}
	}
}
