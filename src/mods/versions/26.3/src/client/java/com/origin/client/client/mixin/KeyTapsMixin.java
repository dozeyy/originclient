package com.origin.client.client.mixin;

import com.origin.client.client.util.KeyTaps;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Feeds util/KeyTaps (G121-7): every key PRESS made while no screen is open, so Origin's
// toggle keys catch a tap shorter than a client tick. Observes only (HEAD, no cancel):
// vanilla's key handling is untouched. Target: the 1.21.9+ shape
// private void keyPress(long window, int action, KeyEvent event) (javap 1.21.10 / 1.21.11,
// 2026-11-04), which 26.x keeps. A shape change fails the apply in boot-smoke, never the game.
// 26.3 (SDL3): event.key() is the same InputConstants code Mods.keyCode and isRawKeyDown
// use (SDL scancodes, all below KeyTaps.SIZE); a press is mapped from InputConstants.PRESS.
@Mixin(KeyboardHandler.class)
public abstract class KeyTapsMixin {
	private static boolean originclient$logged;

	@Inject(method = "keyPress", at = @At("HEAD"))
	private void originclient$countTap(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (!originclient$logged) {
			originclient$logged = true;   // one line: proof the hook is live (boot-smoke greps it)
			com.origin.client.OriginClient.LOGGER.info("Origin: key taps hook live");
		}
		if (Minecraft.getInstance().gui.screen() == null) {
			KeyTaps.onKey(event.key(), action == com.mojang.blaze3d.platform.InputConstants.PRESS ? 1 : 0);
		}
	}
}
