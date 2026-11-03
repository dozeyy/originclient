package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.ext.OldAnimationsState;
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

// 1.7 Animations -> Third Person Block, item half. 1.21.11 ItemInHandLayer (javap + decompiled):
//   protected void submitArmWithItem(S extends ArmedEntityRenderState, ItemStackRenderState,
//       ItemStack, HumanoidArm, PoseStack, SubmitNodeCollector, int light)   (1.21.11 added the
//       ItemStack, used for the spear's STAB pose; 1.21.10 had no stack here)
//     body: if !item.isEmpty(): pushPose; getParentModel().translateToHand(state, arm, ps)
//           [ArmedModel.translateToHand(EntityRenderState, HumanoidArm, PoseStack)]; RX-90 RY180 ...
//     -> HEAD: the off-hand shield next to a sword isn't drawn (1.7 had no shield).
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body.
// The submit pass has no entity (and the stack alone can't tell blocking), so both answers come from the flags
// AvatarRenderer.extractRenderState stored on the state (OldAnimationsState; only player
// states carry them). PlayerItemInHandLayer overrides this only for the spyglass-to-eye pose
// and calls super otherwise. If either stops applying: the shield shows and the sword is held
// like vanilla.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void originclient$hideShield(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
										 PoseStack ps, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		try {
			if (arm != state.mainArm && state instanceof OldAnimationsState s && s.originclient$hideOffhand()) {
				ci.cancel();
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person shield", t);
		}
	}

	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/ArmedModel;translateToHand(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
			shift = At.Shift.AFTER))
	private void originclient$blockTurn(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm,
										PoseStack ps, SubmitNodeCollector collector, int light, CallbackInfo ci) {
		try {
			if (arm == state.mainArm && state instanceof OldAnimationsState s && s.originclient$blockTurn()) {
				OldAnimations.thirdPersonBlock(arm, ps);
			}
		} catch (Throwable t) {
			OldAnimations.fail("third-person sword", t);
		}
	}
}
