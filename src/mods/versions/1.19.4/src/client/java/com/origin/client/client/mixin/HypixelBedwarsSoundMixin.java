package com.origin.client.client.mixin;

import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hypixel Bed Wars > Mute Alert Sound: drops the sound Hypixel plays with a
// "TRAP TRIGGERED!" alert. SoundManager.play(SoundInstance) (verified
// against decompiled 1.19.4 client source) is the one door every sound -- a
// server sound packet included -- goes through before the SoundEngine;
// cancelling at its HEAD means the sound never starts. HypixelBedwars.muteSound
// is false for every other sound, and everywhere outside a Bed Wars game.
@Mixin(SoundManager.class)
public class HypixelBedwarsSoundMixin {
	@Inject(method = "play", at = @At("HEAD"), cancellable = true)
	private void originclient$bwMuteAlert(SoundInstance sound, CallbackInfo ci) {
		try {
			if (HypixelBedwars.muteSound(sound)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// never break audio over an alert
		}
	}
}
