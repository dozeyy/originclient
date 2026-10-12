package com.origin.client.client.mixin;

import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Hypixel Bed Wars > Mute Alert Sound: drops the sound Hypixel plays with a
// "TRAP TRIGGERED!" alert. SoundManager.play(SoundInstance) is the one door
// every sound -- a server sound packet included -- goes through before the
// SoundEngine; cancelling at its HEAD means the sound never starts.
// HypixelBedwars.muteSound is false for every other sound, and everywhere
// outside a Bed Wars game.
//
// 1.21.10 (verified against decompiled 1.21.10 client source): play returns a
// SoundEngine.PlayResult, so the cancel answers NOT_STARTED -- what vanilla
// itself returns for a sound it didn't start.
@Mixin(SoundManager.class)
public class HypixelBedwarsSoundMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true, require = 0)
	private void originclient$bwMuteAlert(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		try {
			if (HypixelBedwars.muteSound(sound)) {
				cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
			}
		} catch (Throwable t) {
			// never break audio over an alert
		}
	}
}
