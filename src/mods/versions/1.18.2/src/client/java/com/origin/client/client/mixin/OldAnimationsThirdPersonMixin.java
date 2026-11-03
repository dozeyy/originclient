package com.origin.client.client.mixin;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.7 Animations -> Third Person Block, arm half. 1.18.2 PlayerRenderer (javap-verified, same as 1.21.1):
//   private static HumanoidModel.ArmPose getArmPose(AbstractClientPlayer, InteractionHand)
//     -> HEAD: the sword hand gets BLOCK during the looks-only sword block (HumanoidModel's
//        BLOCK angles are 1.7's sword-block arm). A raised shield keeps vanilla's pose.
// The item half is OldAnimationsHandLayerMixin. If this stops applying: vanilla arm in F5.
@Mixin(PlayerRenderer.class)
public class OldAnimationsThirdPersonMixin {
	@Inject(method = "getArmPose", at = @At("HEAD"), cancellable = true)
	private static void originclient$oldArmPose(AbstractClientPlayer player, InteractionHand hand,
												CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
		try {
			HumanoidModel.ArmPose pose = OldAnimations.armPose(player, hand);
			if (pose != null) {
				cir.setReturnValue(pose);
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person arm", t);
		}
	}
}
