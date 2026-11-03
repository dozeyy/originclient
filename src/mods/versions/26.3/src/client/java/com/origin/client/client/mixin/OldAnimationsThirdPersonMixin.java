package com.origin.client.client.mixin;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.7 Animations -> Third Person Block, arm half. 26.3 AvatarRenderer (javap-verified, same as 26.2; 1.21.1's
// PlayerRenderer became AvatarRenderer, render-state era):
//   private static HumanoidModel.ArmPose getArmPose(Avatar, ItemStack, InteractionHand)
//     called twice by getArmPose(Avatar, HumanoidArm) from extractRenderState, which stores the
//     result in AvatarRenderState.right/leftArmPose. Two overloads, so the full descriptor is given.
//     -> HEAD: the sword hand gets BLOCK during the looks-only sword block (HumanoidModel's
//        BLOCK angles are 1.7's sword-block arm). A raised shield keeps vanilla's pose.
// BLOCK isn't two-handed, so the caller's isTwoHanded() fix-up never overrides the off hand.
// The item half is OldAnimationsHandLayerMixin. If this stops applying: vanilla arm in F5.
@Mixin(AvatarRenderer.class)
public class OldAnimationsThirdPersonMixin {
	@Inject(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
			at = @At("HEAD"), cancellable = true)
	private static void originclient$oldArmPose(Avatar avatar, ItemStack stack, InteractionHand hand,
												CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
		try {
			HumanoidModel.ArmPose pose = OldAnimations.armPose(avatar, hand);
			if (pose != null) {
				cir.setReturnValue(pose);
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person arm", t);
		}
	}
}
