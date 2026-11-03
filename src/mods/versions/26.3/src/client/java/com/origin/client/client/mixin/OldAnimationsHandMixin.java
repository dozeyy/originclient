package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations, first person, draw half. 26.3 removed ItemInHandRenderer; the hands are drawn by
// FirstPersonHandsAndItemsRenderer (javap/decompile-verified):
//   private void submitArmWithItem(PlayerRenderState, FirstPersonHandsAndItemsRenderState,
//       float partial, float xRot, InteractionHand, float attack, ItemStack, float inverseArmHeight,
//       PoseStack, SubmitNodeCollector, int light)
//     attack = avatarRenderState.swingAnimation (0 for the hand not swinging), inverseArmHeight =
//     swapScale * (1 - lerp(oldHandHeight, handHeight)); same hand chain and constants as 26.2,
//     and the item is the state's pre-resolved FIRST_PERSON ItemStackRenderState (submit(ps,
//     collector, light, NO_OVERLAY, 0)).
//     -> HEAD, cancellable: OldAnimations draws flat items, the sword block and the eat/bow poses
//        itself and cancels; anything it doesn't handle falls through to vanilla untouched.
// The tick half (No Hit Dip) is OldAnimationsHandTickMixin. If this stops applying, the hand
// simply looks vanilla (defaultRequire 0, fail-soft).
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class OldAnimationsHandMixin {

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void originclient$oldHand(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state,
									  float partial, float xRot, InteractionHand hand, float swing, ItemStack stack,
									  float equip, PoseStack ps, SubmitNodeCollector collector, int light,
									  CallbackInfo ci) {
		try {
			if (state.isScoping) {
				return; // vanilla draws nothing while scoping either
			}
			if (OldAnimations.renderFirstPerson(
					hand == InteractionHand.MAIN_HAND ? state.mainHandRenderState : state.offHandRenderState,
					Minecraft.getInstance().player, partial, hand, swing, stack, equip, ps, collector, light)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			OldAnimations.fail("first-person hand", t);
		}
	}
}
