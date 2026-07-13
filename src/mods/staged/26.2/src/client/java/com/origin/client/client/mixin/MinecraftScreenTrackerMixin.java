package com.origin.client.client.mixin;

import com.origin.client.client.OriginScreenState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Tracks the current screen for OriginScreenState: 26.2 removed the public
// Minecraft.screen field, so we capture every screen change from
// setScreenAndShow(Screen) (called with the new screen, or null when closing).
// required:false / fail-soft — if the target ever moves, gating just falls back
// to "no screen open" rather than crashing.
@Mixin(Minecraft.class)
public class MinecraftScreenTrackerMixin {

	@Inject(method = "setScreenAndShow", at = @At("HEAD"))
	private void originclient$trackScreen(Screen screen, CallbackInfo ci) {
		OriginScreenState.current = screen;
	}
}
