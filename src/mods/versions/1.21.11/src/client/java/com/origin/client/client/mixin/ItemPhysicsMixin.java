package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.ext.ItemPhysicsState;
import com.origin.client.client.mods.ItemPhysics;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Item Physics: re-poses a dropped item right before vanilla submits its copies.
// 1.21.11 ItemEntityRenderer (source-verified):
//   extractRenderState(ItemEntity, ItemEntityRenderState, float) -- has the entity;
//     here ItemPhysics advances the item's own easing state (resting on the
//     ground / floating on water vs airborne) and stores the resulting pose on
//     the state (ItemPhysicsState duck);
//   submit(state, PoseStack, SubmitNodeCollector, CameraRenderState): push,
//     translate(bob + model-box lift), rotate(spin), submitMultipleFromCount(...,
//     AABB), pop, super.submit.
// Injecting before that static call sees vanilla's bob + spin on the pose, which
// ItemPhysics undoes and replaces. Everything stays inside vanilla's own
// push/pop, and Item Size's scale (pushed at HEAD) still applies on top.
@Mixin(ItemEntityRenderer.class)
public class ItemPhysicsMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;F)V",
			at = @At("TAIL"))
	private void originclient$capturePose(ItemEntity entity, ItemEntityRenderState state, float partialTick,
										  CallbackInfo ci) {
		try {
			ItemPhysics.extract(entity, state, (ItemPhysicsState) state);
		} catch (Throwable ignored) {
			((ItemPhysicsState) state).originclient$setPose(false, 0f, 0f, 0f);   // vanilla this frame
		}
	}

	@Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;submitMultipleFromCount(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/ItemClusterRenderState;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/phys/AABB;)V"))
	private void originclient$physics(ItemEntityRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
									  CameraRenderState camera, CallbackInfo ci) {
		try {
			ItemPhysics.apply(state, (ItemPhysicsState) state, poseStack);
		} catch (Throwable ignored) {
			// worst case the item keeps vanilla's float + spin this frame
		}
	}
}
