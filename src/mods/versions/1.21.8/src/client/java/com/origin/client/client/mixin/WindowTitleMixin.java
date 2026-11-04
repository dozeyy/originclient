package com.origin.client.client.mixin;

import com.origin.client.client.util.WindowTitles;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The game window's title names Origin and the signed-in player (G121-12, util/WindowTitles):
// "Minecraft* 1.21.1 - Singleplayer" becomes "Origin 1.21.1 - Steve - Singleplayer", so
// several accounts' windows can be told apart. Target (javap 1.20 to 1.21.11, 2026-11-04):
// private String createTitle(), which vanilla's updateTitle() hands to the window on start,
// world join and world leave. Fail-soft: any surprise keeps vanilla's title.
@Mixin(Minecraft.class)
public abstract class WindowTitleMixin {
	@Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	private void originclient$originTitle(CallbackInfoReturnable<String> cir) {
		try {
			String name = ((Minecraft) (Object) this).getUser().getName();
			cir.setReturnValue(WindowTitles.origin(cir.getReturnValue(), name));
		} catch (Throwable ignored) {
			// keep vanilla's title
		}
	}
}
