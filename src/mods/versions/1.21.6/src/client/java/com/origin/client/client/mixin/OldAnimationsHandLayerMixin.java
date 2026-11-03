package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Third Person Block, item half. PER-VERSION 1.21.2+ ItemInHandLayer (javap):
//   protected void renderArmWithItem(S extends ArmedEntityRenderState, ItemStackRenderState,
//       HumanoidArm, PoseStack, MultiBufferSource, int light)
//     body: if item empty return; pushPose; ((ArmedModel) getParentModel()).translateToHand(arm, ps); ...
//     -> HEAD: the off-hand shield next to a sword isn't drawn (1.7 had no shield).
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body.
// No player here, only its state: which arm to hide / turn was decided at extract time
// (OldAnimationsThirdPersonMixin -> OldAnimationsState on PlayerRenderState).
// PlayerItemInHandLayer overrides this only for the spyglass and calls super otherwise.
// If either stops applying: the shield shows and the sword is held like vanilla.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void originclient$hideShield(ArmedEntityRenderState state, ItemStackRenderState item, HumanoidArm arm,
										 PoseStack ps, MultiBufferSource buffers, int light, CallbackInfo ci) {
		try {
			if (OldAnimations.hideThirdPerson(state, arm)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person shield", t);
		}
	}

	@Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/ArmedModel;translateToHand(Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
			shift = At.Shift.AFTER))
	private void originclient$blockTurn(ArmedEntityRenderState state, ItemStackRenderState item, HumanoidArm arm,
										PoseStack ps, MultiBufferSource buffers, int light, CallbackInfo ci) {
		try {
			OldAnimations.thirdPersonBlock(state, arm, ps);
		} catch (Throwable t) {
			OldAnimations.fail("third-person sword", t);
		}
	}
}
