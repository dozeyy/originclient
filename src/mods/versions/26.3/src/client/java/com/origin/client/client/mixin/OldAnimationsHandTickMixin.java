package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// 1.7 Animations -> No Hit Dip. 26.3 moved the hand-height ticker out of ItemInHandRenderer into
// FirstPersonHandsAndItems (javap-verified):
//   public void tick(LocalPlayer): float attackAnim = player.getItemSwapScale(1.0F)
//     [INVOKEVIRTUAL LocalPlayer.getItemSwapScale(F)F]; mainHandHeight eases to attackAnim^3.
//   A hit/miss still calls Player.resetAttackStrengthTicker(), which zeroes attackStrengthTicker
//   AND itemSwapTicker (javap), so the hand dips after every swing like 26.2.
//   -> forced to 1 for No Hit Dip (1.7 had no attack cooldown, so the hand never dipped).
// If this stops applying, the hand dips like vanilla (defaultRequire 0).
@Mixin(FirstPersonHandsAndItems.class)
public class OldAnimationsHandTickMixin {

	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
	private float originclient$noHitDip(float strength) {
		return OldAnimations.on("noHitDip") ? 1.0F : strength;
	}
}
