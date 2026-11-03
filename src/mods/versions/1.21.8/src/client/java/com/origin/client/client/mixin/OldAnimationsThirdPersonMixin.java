package com.origin.client.client.mixin;

import com.origin.client.client.ext.OldAnimationsState;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.7 Animations -> Third Person Block, arm half + state. PER-VERSION 1.21.2+ PlayerRenderer
// (javap of this module's mapped jar):
//   private static ArmPose getArmPose(Player, ItemStack, InteractionHand)   <- per hand, hooked
//   private static ArmPose getArmPose(AbstractClientPlayer, HumanoidArm)     <- per arm, calls the above
//   public void extractRenderState(AbstractClientPlayer, PlayerRenderState, float)
//     -> getArmPose HEAD: the sword hand gets BLOCK during the looks-only sword block (HumanoidModel's
//        BLOCK angles are 1.7's sword-block arm). A raised shield keeps vanilla's pose.
//        1.21.1 hooked the (AbstractClientPlayer, InteractionHand) shape, which is gone.
//     -> extractRenderState TAIL: the sword-block arm goes on the state (OldAnimationsState) for
//        OldAnimationsHandLayerMixin, which no longer sees the player.
// Full descriptors keep the injectors off the other getArmPose and the synthetic bridges.
// If this stops applying: vanilla arm and sword in F5.
@Mixin(PlayerRenderer.class)
public class OldAnimationsThirdPersonMixin {
	@Inject(method = "getArmPose(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
			at = @At("HEAD"), cancellable = true)
	private static void originclient$oldArmPose(Player player, ItemStack stack, InteractionHand hand,
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

	@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V",
			at = @At("TAIL"))
	private void originclient$oldArmState(AbstractClientPlayer player, PlayerRenderState state, float partial,
										  CallbackInfo ci) {
		try {
			OldAnimations.extractThirdPerson(player, (OldAnimationsState) state);
		} catch (Throwable t) {
			OldAnimations.fail("third-person state", t);
		}
	}
}
