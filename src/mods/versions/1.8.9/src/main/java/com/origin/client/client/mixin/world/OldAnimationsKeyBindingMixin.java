package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 1.7 Animations -> Swing While Using, the click half. 1.8.9 Minecraft.runTick (vineflower):
//   if (thePlayer.isUsingItem()) { ...; while (gameSettings.keyBindAttack.isPressed()) {} ... }
//   -> vanilla throws attack clicks away while eating/drawing/blocking. KeyBinding.isPressed()
//      (public boolean, pressTime > 0 -> --pressTime, true) at RETURN: a click on the attack key
//      that lands while the player is using an item swings the arm instead.
// Hooked here, not with an INVOKE point inside runTick, because Minecraft is partly OptiFine-
// patched; KeyBinding is not (the core KeyBindingMixin hooks its isKeyDown the same way). The
// isUsingItem test keeps this to that one loop: when not using, the clickMouse branch swings itself.
// Visual only (OldAnimations.visualSwing sends no packet). If it stops applying: clicks while
// using do nothing, as vanilla 1.8.
@Mixin(KeyBinding.class)
public abstract class OldAnimationsKeyBindingMixin {
	@Inject(method = "isPressed", at = @At("RETURN"))
	private void originclient$swingWhileUsing(CallbackInfoReturnable<Boolean> cir) {
		try {
			if (!cir.getReturnValueZ()) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			if (mc.gameSettings != null && (Object) this == mc.gameSettings.keyBindAttack && mc.thePlayer != null
					&& mc.thePlayer.isUsingItem() && OldAnimations.on("swingWhileUsing")) {
				OldAnimations.visualSwing(mc.thePlayer);
			}
		} catch (Throwable t) {
			OldAnimations.fail("swing while using", t);
		}
	}
}
