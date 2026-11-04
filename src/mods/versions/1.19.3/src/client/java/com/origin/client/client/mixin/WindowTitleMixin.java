package com.origin.client.client.mixin;

import com.origin.client.client.util.WindowTitles;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The game window's title names Origin and the signed-in player (G121-12, util/WindowTitles):
// "Minecraft* 1.19.3 - Singleplayer" becomes "Origin 1.19.3 - Steve - Singleplayer", so
// several accounts' windows can be told apart. Target: private String createTitle(), which
// vanilla's updateTitle() hands to the window on start, world join and world leave (same
// shape as 1.20 to 1.21.11). Logs the first title once (boot-smoke proof the hook is live).
// Fail-soft: any surprise keeps vanilla's title.
@Mixin(Minecraft.class)
public abstract class WindowTitleMixin {
	private static boolean originclient$logged;

	@Inject(method = "createTitle", at = @At("RETURN"), cancellable = true)
	private void originclient$originTitle(CallbackInfoReturnable<String> cir) {
		try {
			String name = ((Minecraft) (Object) this).getUser().getName();
			cir.setReturnValue(WindowTitles.origin(cir.getReturnValue(), name));
			if (!originclient$logged) {
				originclient$logged = true;
				com.origin.client.OriginClient.LOGGER.info("Origin: window title \"{}\"", cir.getReturnValue());
			}
		} catch (Throwable ignored) {
			// keep vanilla's title
		}
	}
}
