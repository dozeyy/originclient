package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Third Person Block, item half. 26.3 ItemInHandLayer (javap-verified, same as 26.2):
//   protected void submitArmWithItem(S state, ItemStackRenderState item, ItemStack, HumanoidArm,
//       PoseStack, SubmitNodeCollector, int light)      S erases to ArmedEntityRenderState
//     body: pushPose; ((ArmedModel) getParentModel()).translateToHand(state, arm, ps)
//           [INVOKEINTERFACE ArmedModel.translateToHand(EntityRenderState, HumanoidArm, PoseStack)];
//           RX-90 RY180 T(+-1/16, 2/16, -10/16) ... item.submit(...)
//     -> HEAD: a player's off-hand shield next to a sword isn't drawn (1.7 had no shield).
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body.
// The entity is gone in this era: both read the AvatarRenderState (mainArm, hand stacks,
// isUsingItem, useItemHand). PlayerItemInHandLayer overrides this only for items held to the
// eye (spyglass) and calls super otherwise. If either stops applying: the shield shows and the
// sword is held like vanilla.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void originclient$hideShield(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack,
										 HumanoidArm arm, PoseStack ps, SubmitNodeCollector collector, int light,
										 CallbackInfo ci) {
		try {
			if (OldAnimations.hideThirdPerson(state, stack, arm)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person shield", t);
		}
	}

	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/ArmedModel;translateToHand(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
			shift = At.Shift.AFTER))
	private void originclient$blockTurn(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack,
										HumanoidArm arm, PoseStack ps, SubmitNodeCollector collector, int light,
										CallbackInfo ci) {
		try {
			OldAnimations.thirdPersonBlock(state, arm, ps);
		} catch (Throwable t) {
			OldAnimations.fail("third-person sword", t);
		}
	}
}
