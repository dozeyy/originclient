package com.origin.client.client.mixin;

import com.origin.client.client.mods.SoundChanger;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sound Changer: scales a sound's volume by its slider (SoundChanger.factor).
//
// 1.16.5 SoundEngine (decompiled 1.16.4) works every sound's
// volume out in ONE private helper, calculateVolume(SoundInstance) =
// clamp(volume x category volume, 0, 1), called both by play(SoundInstance) --
// where a result of 0 makes vanilla skip the sound outright, so a 0% slider truly
// mutes it -- and every tick / on a category-slider move for sounds still
// playing (rain, music, portal hum), so the slider also applies live to a sound
// already looping. Scaling its return value (factor is 0..1, so the clamp still
// holds) covers both paths in one hook.
@Mixin(SoundEngine.class)
public class SoundChangerMixin {
	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"), cancellable = true)
	private void originclient$scaleVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		try {
			float f = SoundChanger.factor(sound.getLocation());
			if (f != 1f) {
				cir.setReturnValue(cir.getReturnValueF() * f);
			}
		} catch (Throwable t) {
			// never break audio over a slider
		}
	}
}
