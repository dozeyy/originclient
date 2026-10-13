package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Vanilla's top-right potion-effect icons, behind a setting: while the Potion
// Effects mod is on, the "Vanilla Display" toggle decides whether the default
// top-right rendering still shows alongside Origin's own movable element.
// priority 2000: Origin's potion-effect gating wins over other UI mods.
@Mixin(value = Hud.class, priority = 2000)
public abstract class GuiEffectsMixin {
	@Inject(method = "extractEffects", at = @At("HEAD"), cancellable = true)
	private void originclient$gateVanillaEffects(GuiGraphicsExtractor guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Mods.on("potionhud") && !Mods.bool("potionhud", "vanillaDisplay")) {
			ci.cancel();
		}
	}
}
