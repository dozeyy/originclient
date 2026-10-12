package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.ItemPhysics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Item Physics: re-poses a dropped item right before vanilla draws its copies.
// Pre-1.20.5 ItemEntityRenderer.render (decompiled 1.18.1 (the 1.18.2 code base)): push, translate(bob),
// mulPose(spin) -- its ONLY mulPose -- then the copies are drawn inline in a
// loop, pop, super.render. Injecting right AFTER that mulPose sees vanilla's bob +
// spin on the pose, which ItemPhysics undoes and replaces. Everything stays
// inside vanilla's own push/pop, and Item Size's scale (pushed at HEAD) still
// applies on top. Full descriptor so the synthetic Entity bridge isn't matched.
@Mixin(ItemEntityRenderer.class)
public class ItemPhysicsMixin {
	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lcom/mojang/math/Quaternion;)V",
					ordinal = 0, shift = At.Shift.AFTER))
	private void originclient$physics(ItemEntity entity, float yaw, float partialTick, PoseStack poseStack,
									  MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
		try {
			ItemPhysics.apply(entity, partialTick, poseStack);
		} catch (Throwable ignored) {
			// worst case the item keeps vanilla's float + spin this frame
		}
	}
}
