package com.origin.client.client.mixin;

import com.origin.client.client.OptionsAutosave;
import com.origin.client.client.mods.ClickStats;
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

	// CPS counter: every keybind click (mouse button or key, in the world) passes
	// through the static KeyMapping.click(Key) on every 1.20+ era (javap: 1.20 to
	// 26.3); ClickStats keeps the ones bound to Attack / Use. If this stops
	// applying, CPS reads 0 and nothing else changes.
	@Inject(method = "click", at = @At("HEAD"))
	private static void originclient$countClick(InputConstants.Key key, CallbackInfo ci) {
		ClickStats.onClick(key);
	}
}
