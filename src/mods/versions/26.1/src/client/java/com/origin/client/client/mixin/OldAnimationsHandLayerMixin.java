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

// 1.7 Animations -> Third Person Block, item half. 26.1.2 ItemInHandLayer (javap-verified, same as 26.2):
//   protected void submitArmWithItem(S state, ItemStackRenderState item, ItemStack, HumanoidArm,
//       PoseStack, SubmitNodeCollector, int light)      S erases to ArmedEntityRenderState
//     body: pushPose; ((ArmedModel) getParentModel()).translateToHand(state, arm, ps)
//           [INVOKEINTERFACE ArmedModel.translateToHand(EntityRenderState, HumanoidArm, PoseStack)];
//           RX-90 RY180 T(+-1/16, 2/16, -10/16) ... item.submit(...)
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body
//        (only during the looks-only sword block; a shield is never touched).
// The entity is gone in this era: it reads the AvatarRenderState (mainArm, id = the local
// player's). PlayerItemInHandLayer overrides this only for items held to the eye (spyglass)
// and calls super otherwise. If it stops applying: the sword is held like vanilla in F5.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

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
