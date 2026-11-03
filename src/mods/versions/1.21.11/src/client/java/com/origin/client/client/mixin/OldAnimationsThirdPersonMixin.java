package com.origin.client.client.mixin;

import com.origin.client.client.ext.OldAnimationsState;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.7 Animations -> Third Person Block, arm half + state. 1.21.11 AvatarRenderer (javap +
// decompiled client; 1.21.9 renamed PlayerRenderer to AvatarRenderer, players and mannequins):
//   private static ArmPose getArmPose(Avatar, ItemStack, InteractionHand)   (the per-hand overload;
//       getArmPose(Avatar, HumanoidArm) calls it for both hands, then the two-handed check)
//     -> HEAD: the sword hand gets BLOCK during the looks-only sword block (HumanoidModel's
//        BLOCK angles are 1.7's sword-block arm). A raised shield keeps vanilla's pose.
//        BLOCK isn't two-handed, so the follow-up check leaves it alone.
//   public void extractRenderState(Avatar, AvatarRenderState, float)  (descriptor pinned: the class
//       also has the bridge overloads (LivingEntity, LivingEntityRenderState, F) / (Entity, ...))
//     -> TAIL: the flag the submit pass needs (OldAnimationsState), because ItemInHandLayer
//        only gets the render state in this era.
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

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void originclient$oldAnimState(Avatar avatar, AvatarRenderState state, float partial, CallbackInfo ci) {
		try {
			// Always written (the renderer reuses one state object), so a stale flag never survives.
			((OldAnimationsState) state).originclient$setOldBlock(OldAnimations.blockTurnThirdPerson(avatar));
		} catch (Throwable t) {
			OldAnimations.fail("third-person state", t);
		}
	}
}
