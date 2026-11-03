package com.origin.client.client.mixin;

import com.origin.client.client.OptionsAutosave;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Target: Options.save() (public void save(), every Fabric module 1.20 - 26.3), HEAD and RETURN.
// Why: Origin's Chat mod rides three vanilla chat options (text background opacity, chat text
// opacity, chat scale) for as long as it is on. options.txt must only ever hold the player's own
// values: Settings Sync copies that file between versions, and turning the mod off (or playing
// without Origin) must give the player back the chat they set. Every writer goes through save()
// -- Origin's autosave, vanilla's settings screens, quitting -- so the player's values are swapped
// in for exactly the length of the write (OptionsAutosave.Swap) and Origin's put back after.
// Both hooks catch everything (OptionsAutosave.swap), so a failure never stops the save.
// If it stops applying: options.txt keeps the Chat mod's values again while it is on (the bug it
// fixes); nothing crashes.
@Mixin(Options.class)
public class OptionsSaveMixin {

	@Inject(method = "save", at = @At("HEAD"))
	private void originclient$playerValuesIn(CallbackInfo ci) {
		OptionsAutosave.beforeSave((Options) (Object) this);
	}

	@Inject(method = "save", at = @At("RETURN"))
	private void originclient$originValuesBack(CallbackInfo ci) {
		OptionsAutosave.afterSave((Options) (Object) this);
	}
}
