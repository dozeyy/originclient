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

// 1.7 Animations -> Third Person Block, arm half. 1.21 PlayerRenderer (javap-verified on this module's mapped jar):
//   private static HumanoidModel.ArmPose getArmPose(AbstractClientPlayer, InteractionHand)
//     -> HEAD: sword hand gets BLOCK while the off-hand shield is raised (HumanoidModel's BLOCK
//        angles are 1.7's sword-block arm), the shield hand hangs EMPTY (the shield isn't drawn).
// The item half is OldAnimationsHandLayerMixin. If this stops applying: vanilla shield pose.
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
