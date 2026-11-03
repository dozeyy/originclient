package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Third Person Block, item half. 1.16.5 ItemInHandLayer (javap-verified; TransformType where 1.21.1 has ItemDisplayContext):
//   private void renderArmWithItem(LivingEntity, ItemStack, ItemTransforms.TransformType, HumanoidArm,
//       PoseStack, MultiBufferSource, int light)
//     body: pushPose; ((ArmedModel) getParentModel()).translateToHand(arm, ps); RX-90 RY180 ...
//     -> HEAD: the off-hand shield next to a sword isn't drawn (1.7 had no shield).
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body.
// 1.16.5 has no PlayerItemInHandLayer (the spyglass override came in 1.17); players use this layer directly.
// If either stops applying: the shield shows and the sword is held like vanilla.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void originclient$hideShield(LivingEntity entity, ItemStack stack, ItemTransforms.TransformType ctx,
										 HumanoidArm arm, PoseStack ps, MultiBufferSource buffers, int light,
										 CallbackInfo ci) {
		try {
			if (OldAnimations.hideThirdPerson(entity, stack)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person shield", t);
		}
	}

	@Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/ArmedModel;translateToHand(Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
			shift = At.Shift.AFTER))
	private void originclient$blockTurn(LivingEntity entity, ItemStack stack, ItemTransforms.TransformType ctx,
										HumanoidArm arm, PoseStack ps, MultiBufferSource buffers, int light,
										CallbackInfo ci) {
		try {
			OldAnimations.thirdPersonBlock(entity, stack, arm, ps);
		} catch (Throwable t) {
			OldAnimations.fail("third-person sword", t);
		}
	}
}
