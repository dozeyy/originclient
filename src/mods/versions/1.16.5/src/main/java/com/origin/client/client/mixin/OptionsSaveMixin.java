package com.origin.client.client.mixin;

import com.origin.client.client.OriginClientMod;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: Options.save() (options.txt), HEAD and RETURN.
// Why: Origin's Chat mod borrows three of the player's own Minecraft chat
// settings while it is on (background opacity, text opacity, scale). Every
// write of options.txt -- vanilla's settings screens, OptionsAutosave, the exit
// flush -- swaps the player's values back in for the write and Origin's back
// after it, so options.txt only ever holds the player's own settings and
// turning the mod off (or uninstalling Origin) gives them back intact.
// If it stops applying: options.txt stores the Chat mod's values while it is
// on (the old bug); nothing crashes. The hooks are fail-soft no-ops when the
// Chat mod is off.
@Mixin(Options.class)
public class OptionsSaveMixin {

	@Inject(method = "save", at = @At("HEAD"))
	private void originclient$playerChatValues(CallbackInfo ci) {
		try {
			OriginClientMod.chatOptionsForSave((Options) (Object) this, true);
		} catch (Throwable ignored) {
			// never block a settings save
		}
	}

	@Inject(method = "save", at = @At("RETURN"))
	private void originclient$originChatValues(CallbackInfo ci) {
		try {
			OriginClientMod.chatOptionsForSave((Options) (Object) this, false);
		} catch (Throwable ignored) {
			// the next client tick re-applies them anyway
		}
	}
}
