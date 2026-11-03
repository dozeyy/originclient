package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations, first person. 26.1.2 shapes (javap/decompile-verified; 26.2 renamed the method
// submitArmWithItem, same parameters and body):
//   private void renderArmWithItem(AbstractClientPlayer, float partial, float xRot, InteractionHand,
//       float attack, ItemStack, float inverseArmHeight, PoseStack, SubmitNodeCollector, int light)
//     (1.21.1's name, already drawing through a SubmitNodeCollector)
//     -> HEAD, cancellable: OldAnimations draws flat items, the sword block and the eat/bow poses
//        itself and cancels; anything it doesn't handle falls through to vanilla untouched.
//   tick(): float attackAnim = player.getItemSwapScale(1.0F)  [INVOKEVIRTUAL LocalPlayer.getItemSwapScale(F)F]
//     mainHandHeight eases to attackAnim^3. 26.x split the swap ticker from the attack ticker, but
//     a hit/miss still calls Player.resetAttackStrengthTicker() (MultiPlayerGameMode.attack,
//     Minecraft.startAttack's miss), which zeroes BOTH tickers, so the hand still dips after a hit.
//     -> forced to 1 for No Hit Dip (1.7 had no attack cooldown, so the hand never dipped).
// If either stops applying, the hand simply looks vanilla (defaultRequire 0, fail-soft).
@Mixin(ItemInHandRenderer.class)
public class OldAnimationsHandMixin {

	@Inject(method = "renderArmWithItem", at = @At("HEAD"), cancellable = true)
	private void originclient$oldHand(AbstractClientPlayer player, float partial, float xRot, InteractionHand hand,
									  float swing, ItemStack stack, float equip, PoseStack ps,
									  SubmitNodeCollector collector, int light, CallbackInfo ci) {
		try {
			if (OldAnimations.renderFirstPerson((ItemInHandRenderer) (Object) this, player, partial, hand, swing,
					stack, equip, ps, collector, light)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			OldAnimations.fail("first-person hand", t);
		}
	}

	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getItemSwapScale(F)F"))
	private float originclient$noHitDip(float strength) {
		return OldAnimations.on("noHitDip") ? 1.0F : strength;
	}
}
