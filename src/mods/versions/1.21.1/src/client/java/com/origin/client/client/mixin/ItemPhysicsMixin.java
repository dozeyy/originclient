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
// 1.21.1 ItemEntityRenderer.render (source-verified): push, translate(bob),
// rotate(spin), renderMultipleFromCount(...), pop, super.render -- so injecting
// before that static call sees vanilla's bob + spin on the pose, which
// ItemPhysics undoes and replaces. Everything stays inside vanilla's own
// push/pop, and Item Size's scale (pushed at HEAD) still applies on top.
@Mixin(ItemEntityRenderer.class)
public class ItemPhysicsMixin {
	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/entity/ItemEntityRenderer;renderMultipleFromCount(Lnet/minecraft/client/renderer/entity/ItemRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/BakedModel;ZLnet/minecraft/util/RandomSource;)V"))
	private void originclient$physics(ItemEntity entity, float yaw, float partialTick, PoseStack poseStack,
									  MultiBufferSource buffers, int packedLight, CallbackInfo ci) {
		try {
			ItemPhysics.apply(entity, partialTick, poseStack);
		} catch (Throwable ignored) {
			// worst case the item keeps vanilla's float + spin this frame
		}
	}
}
