package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.ext.OldAnimationsState;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Third Person Block, item half. 1.21.10 ItemInHandLayer (javap + decompiled):
//   protected void submitArmWithItem(S extends ArmedEntityRenderState, ItemStackRenderState,
//       HumanoidArm, PoseStack, SubmitNodeCollector, int light)
//     body: if !item.isEmpty(): pushPose; getParentModel().translateToHand(state, arm, ps)
//           [ArmedModel.translateToHand(EntityRenderState, HumanoidArm, PoseStack)]; RX-90 RY180 ...
//     -> right after translateToHand: 1.7's block turn so the sword lies across the body
//        (only during the looks-only sword block; a shield is never touched).
// The submit pass has no entity, so the answer comes from the flag AvatarRenderer.extractRenderState
// stored on the state (OldAnimationsState; only player states carry it). PlayerItemInHandLayer
// overrides this only for the spyglass-to-eye pose and calls super otherwise. If it stops
// applying: the sword is held like vanilla in F5.
@Mixin(ItemInHandLayer.class)
public class OldAnimationsHandLayerMixin {

	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/ArmedModel;translateToHand(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;)V",
			shift = At.Shift.AFTER))
	private void originclient$blockTurn(ArmedEntityRenderState state, ItemStackRenderState item, HumanoidArm arm,
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
