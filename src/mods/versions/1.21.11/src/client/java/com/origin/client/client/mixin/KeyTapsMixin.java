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
// vanilla's key handling is untouched. Target (javap 1.21.10 / 1.21.11, 2026-11-04):
// private void keyPress(long window, int action, KeyEvent event).
@Mixin(KeyboardHandler.class)
public abstract class KeyTapsMixin {
	@Inject(method = "keyPress", at = @At("HEAD"))
	private void originclient$countTap(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (Minecraft.getInstance().screen == null) {
			KeyTaps.onKey(event.key(), action);
		}
	}
}
