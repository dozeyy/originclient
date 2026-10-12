package com.origin.client.client.mixin;

import com.origin.client.client.mods.SoundChanger;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sound Changer: scales a sound's volume by its slider (SoundChanger.factor).
//
// SoundEngine (1.21.1 source-verified; same shape in 1.21.6/1.21.8 except that
// play now returns a SoundEngine.PlayResult) works a volume out in two places, both
// through the private calculateVolume(float volume, SoundSource) helper:
//  - play(SoundInstance): when a sound starts. A result of 0 makes vanilla skip
//    the sound outright, so a 0% slider truly mutes it.
//  - calculateVolume(SoundInstance): every tick for sounds still playing (rain,
//    music, portal hum) and when a category slider moves -- so the slider also
//    applies live to a sound that is already looping.
// Neither helper sees the SoundInstance at the inner call, so each outer method
// records it at HEAD; the one @ModifyArg then scales the raw volume before
// vanilla multiplies in the category volume and clamps. Sound playback runs on
// the render thread only, so a plain field is safe.
@Mixin(SoundEngine.class)
public class SoundChangerMixin {
	@Unique
	private SoundInstance originclient$current;

	@Inject(method = "play", at = @At("HEAD"))
	private void originclient$capturePlay(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		originclient$current = sound;
	}

	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("HEAD"))
	private void originclient$captureTick(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		originclient$current = sound;
	}

	@ModifyArg(method = {"play", "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F"},
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"),
			index = 0)
	private float originclient$scaleVolume(float volume) {
		SoundInstance s = originclient$current;
		originclient$current = null;
		if (s == null) {
			return volume;
		}
		try {
			return volume * SoundChanger.factor(s.getLocation());
		} catch (Throwable t) {
			return volume;   // never break audio over a slider
		}
	}
}
