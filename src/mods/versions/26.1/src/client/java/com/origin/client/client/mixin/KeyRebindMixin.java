package com.origin.client.client.mixin;

import com.origin.client.client.OptionsAutosave;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Rebinding a key in Controls: save it at once like every other setting
// (OptionsAutosave). Hooked on KeyMapping.setKey, not Options.setKey — that one
// is gone from 1.21.2 on, while KeyMapping.setKey is what every version's
// Controls screen (and its Reset buttons) calls. options.txt's own load also
// goes through here, but before Minecraft.options is set, so changed() skips it.
@Mixin(KeyMapping.class)
public class KeyRebindMixin {

	@Inject(method = "setKey", at = @At("TAIL"))
	private void originclient$saveSoon(InputConstants.Key input, CallbackInfo ci) {
		OptionsAutosave.changed();
	}
}
