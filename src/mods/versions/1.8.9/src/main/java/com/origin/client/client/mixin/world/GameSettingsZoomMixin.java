package com.origin.client.client.mixin.world;

import com.origin.client.client.OriginClientMod;
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
// The same two hooks keep the Chat mod's scale out of options.txt: the player's
// own chat scale goes in for the write and the mod's comes back after
// (OriginClientMod.chatOptionsForSave). If this stops applying, options.txt
// stores the parked zoom key and the mod's chat scale; nothing crashes.
@Mixin(GameSettings.class)
public abstract class GameSettingsZoomMixin {
	@Inject(method = "saveOptions", at = @At("HEAD"))
	private void originclient$realZoomKey(CallbackInfo ci) {
		try {
			WorldHooks.beforeOptionsSave((GameSettings) (Object) this);
		} catch (Throwable ignored) {
		}
		try {
			OriginClientMod.chatOptionsForSave((GameSettings) (Object) this, true);
		} catch (Throwable ignored) {
		}
	}

	@Inject(method = "saveOptions", at = @At("RETURN"))
	private void originclient$parkZoomKey(CallbackInfo ci) {
		try {
			WorldHooks.afterOptionsSave((GameSettings) (Object) this);
		} catch (Throwable ignored) {
		}
		try {
			OriginClientMod.chatOptionsForSave((GameSettings) (Object) this, false);
		} catch (Throwable ignored) {
		}
	}
}
