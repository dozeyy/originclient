package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.ItemSizes;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Item Size Customizer: scales a dropped item's render by the player's per-item
// size. Push a scaled matrix at the HEAD of the item render and pop it at RETURN
// so the transform is perfectly balanced (we always push/pop while the mod is on,
// even at 1.0, so the two injections can never get out of sync). Gameplay, hitbox
// and pickup are untouched — this is render-only.
@Mixin(ItemEntityRenderer.class)
public class ItemEntityScaleMixin {

	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("HEAD"))
	private void originclient$pushSize(ItemEntity entity, float yaw, float partialTick, PoseStack poseStack,
									   MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		if (!Mods.on("itemsize")) {
			return;
		}
		poseStack.pushPose();
		float s = ItemSizes.get(BuiltInRegistries.ITEM.getKey(entity.getItem().getItem()));
		if (s != ItemSizes.DEFAULT) {
			poseStack.scale(s, s, s);
		}
	}

	@Inject(method = "render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
			at = @At("RETURN"))
	private void originclient$popSize(ItemEntity entity, float yaw, float partialTick, PoseStack poseStack,
									  MultiBufferSource buffer, int packedLight, CallbackInfo ci) {
		if (!Mods.on("itemsize")) {
			return;
		}
		poseStack.popPose();
	}
}
