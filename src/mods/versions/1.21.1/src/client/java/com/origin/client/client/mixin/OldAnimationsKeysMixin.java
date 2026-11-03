package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 1.7 Animations -> Swing While Using. 1.21.1 Minecraft (source-verified):
//   handleKeybinds(): if (player.isUsingItem()) { ... while (options.keyAttack.consumeClick()) {} ... }
//     -> vanilla throws attack clicks away while eating/drawing. Wrapping consumeClick, a click
//        that lands while using swings the arm instead. Never while a shield is raised: shields
//        stay exactly vanilla (Will, 2026-11-02).
//   continueAttack(boolean held): returns early while using
//     -> holding attack on a block while using loops the swing, 1.7's block-hit look.
// The swing is LivingEntity.swing(hand, false): only LocalPlayer.swing(hand) sends a packet,
// so this is visual only and servers see nothing new. If it stops applying: no swing, as vanilla.
@Mixin(Minecraft.class)
public class OldAnimationsKeysMixin {
	@Shadow public LocalPlayer player;
	@Shadow public HitResult hitResult;
	@Shadow @Final public Options options;

	@WrapOperation(method = "handleKeybinds",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
	private boolean originclient$swingWhileUsing(KeyMapping key, Operation<Boolean> original) {
		boolean clicked = original.call(key);
		try {
			if (clicked && key == options.keyAttack && player != null && player.isUsingItem()
					&& !OldAnimations.isShield(player.getUseItem()) && OldAnimations.on("swingWhileUsing")) {
				player.swing(InteractionHand.MAIN_HAND, false);
			}
		} catch (Throwable t) {
			OldAnimations.fail("swing while using", t);
		}
		return clicked;
	}

	@Inject(method = "continueAttack", at = @At("HEAD"))
	private void originclient$holdSwingWhileUsing(boolean held, CallbackInfo ci) {
		try {
			if (held && player != null && player.isUsingItem() && !OldAnimations.isShield(player.getUseItem())
					&& hitResult != null
					&& hitResult.getType() == HitResult.Type.BLOCK && OldAnimations.on("swingWhileUsing")) {
				player.swing(InteractionHand.MAIN_HAND, false);
			}
		} catch (Throwable t) {
			OldAnimations.fail("swing while using", t);
		}
	}
}
