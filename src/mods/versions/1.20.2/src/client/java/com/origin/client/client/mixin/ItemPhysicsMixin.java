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
// Pre-1.20.5 ItemEntityRenderer.render (source-verified against decompiled
// 1.20.1 / 1.20.2 / 1.20.4): push, translate(bob), mulPose(YP spin) -- the
// method's FIRST PoseStack.mulPose -- then the copy loop inline (1.20.5+ moved
// that loop into renderMultipleFromCount, 1.21.1's hook point). Injecting just
// AFTER that first mulPose is the same moment: vanilla's bob + spin are on the
// pose, ItemPhysics undoes and replaces them, and the flat-item stack offset
// and copies that follow apply on top exactly as in 1.21.1. Everything stays
// inside vanilla's own push/pop, and Item Size's scale (pushed at HEAD) still
// applies on top.
@Mixin(ItemEntityRenderer.class)
public class ItemPhysicsMixin {
	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/blaze3d/vertex/PoseStack;mulPose(Lorg/joml/Quaternionf;)V",
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
