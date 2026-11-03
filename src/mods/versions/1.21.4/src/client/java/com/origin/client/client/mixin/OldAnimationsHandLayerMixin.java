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
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body
//        (only during the looks-only sword block; a shield is never touched).
// No player here, only its state: which arm to turn was decided at extract time
// (OldAnimationsThirdPersonMixin -> OldAnimationsState on PlayerRenderState).
// PlayerItemInHandLayer overrides this only for the spyglass and calls super otherwise.
// If it stops applying: the sword is held like vanilla in F5.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

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
