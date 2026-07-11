package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// SETTINGS > General > Show Achievements. When off, drop advancement toasts
// before they're queued so the pop-ups never appear. Every other toast
// (recipe unlock, tutorial, system) is left untouched.
@Mixin(ToastComponent.class)
public abstract class ToastComponentMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void originclient$suppressAchievements(Toast toast, CallbackInfo ci) {
		if (toast instanceof AdvancementToast && !Mods.bool(Mods.GENERAL_ID, "showAchievements")) {
			ci.cancel();
		}
	}
}
