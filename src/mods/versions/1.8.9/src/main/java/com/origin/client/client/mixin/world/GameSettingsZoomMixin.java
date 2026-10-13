package com.origin.client.client.mixin.world;

import com.origin.client.client.hooks.WorldHooks;
import net.minecraft.client.settings.GameSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// While Origin's zoom shares OptiFine's zoom key, WorldHooks parks OptiFine's
// binding on "none" so only one zoom fires. Every options save puts the player's
// real OptiFine key back for the write (HEAD) and parks it again after (RETURN),
// so options.txt never records the parked value -- removing Origin leaves
// OptiFine's zoom exactly as the player set it. GameSettings is OptiFine-replaced:
// HEAD/RETURN only.
@Mixin(GameSettings.class)
public abstract class GameSettingsZoomMixin {
	@Inject(method = "saveOptions", at = @At("HEAD"))
	private void originclient$realZoomKey(CallbackInfo ci) {
		try {
			WorldHooks.beforeOptionsSave((GameSettings) (Object) this);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "saveOptions", at = @At("RETURN"))
	private void originclient$parkZoomKey(CallbackInfo ci) {
		try {
			WorldHooks.afterOptionsSave((GameSettings) (Object) this);
		} catch (Throwable ignored) {
		}
	}
}
