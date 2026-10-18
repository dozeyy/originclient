package com.origin.client.client.mixin;

import com.origin.client.client.OptionsAutosave;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Rebinding a key in Controls goes through Options.setKey: save it at once like
// every other setting (OptionsAutosave).
@Mixin(Options.class)
public class OptionsSetKeyMixin {

	@Inject(method = "setKey", at = @At("TAIL"))
	private void originclient$saveSoon(KeyMapping key, InputConstants.Key input, CallbackInfo ci) {
		OptionsAutosave.changed();
	}
}
