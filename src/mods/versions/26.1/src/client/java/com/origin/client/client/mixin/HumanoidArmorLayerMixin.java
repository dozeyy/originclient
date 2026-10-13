package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HitColor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hit Color -> Color Armor, half 1 of 2. 1.21.11 shape (source-verified against
// decompiled HumanoidArmorLayer):
//   renderArmorPiece(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot,
//                    int light, S state)  -> EquipmentLayerRenderer.renderLayers(...)
// The render state already says whether the wearer is mid-flash
// (LivingEntityRenderState.hasRedOverlay = hurtTime > 0 || deathTime > 0), so
// this records it for the duration of the piece; EquipmentLayerTintMixin then
// tints the armour model's colour as it is submitted. S erases to
// HumanoidRenderState. The flag is cleared at RETURN so no other equipment
// layer (horse armour, wolf armour) ever sees it.
@Mixin(HumanoidArmorLayer.class)
public class HumanoidArmorLayerMixin {

	@Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
			at = @At("HEAD"))
	private void originclient$recordHurt(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack,
										 EquipmentSlot slot, int light, HumanoidRenderState state, CallbackInfo ci) {
		HitColor.armorHurt = state.hasRedOverlay;
	}

	@Inject(method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
			at = @At("RETURN"))
	private void originclient$clearHurt(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack,
										EquipmentSlot slot, int light, HumanoidRenderState state, CallbackInfo ci) {
		HitColor.armorHurt = false;
	}
}
