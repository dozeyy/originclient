package com.origin.client.client.mixin;

import com.origin.client.client.OptionsAutosave;
import java.util.Objects;
import net.minecraft.client.OptionInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Every vanilla setting except keybinds is an OptionInstance, and every change
// goes through set() -- so this one hook sees any change from any screen (see
// OptionsAutosave). Only a real change counts: Origin's Chat mod re-sets the
// same chat values every tick, which must not turn into a save every tick.
// Keybinds are covered by OptionsSetKeyMixin.
@Mixin(OptionInstance.class)
public class OptionsAutosaveMixin<T> {

	@Inject(method = "set", at = @At("HEAD"))
	private void originclient$saveSoon(T value, CallbackInfo ci) {
		if (!Objects.equals(((OptionInstance<?>) (Object) this).get(), value)) {
			OptionsAutosave.changed();
		}
	}
}
