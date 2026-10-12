package com.origin.client.client.mixin;

import com.origin.client.client.mods.SoundChanger;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sound Changer: scales a sound's volume by its slider (SoundChanger.factor).
//
// 1.18 SoundEngine (decompiled 1.18.1) works a volume out in ONE place, the
// private calculateVolume(SoundInstance) = clamp(volume x category volume, 0, 1)
// -- called by play(SoundInstance) when a sound starts (a result of 0 makes
// vanilla skip the sound outright, so a 0% slider truly mutes it) and every
// tick / category-slider change for sounds still playing, so the slider also
// applies live to a looping sound. (1.19 split it into calculateVolume(float,
// SoundSource); the 1.19.x modules hook that inner call instead.) Scaling the
// clamped result is the same as scaling the input except above full volume.
@Mixin(SoundEngine.class)
public class SoundChangerMixin {
	@Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F",
			at = @At("RETURN"), cancellable = true)
	private void originclient$scaleVolume(SoundInstance sound, CallbackInfoReturnable<Float> cir) {
		try {
			float f = SoundChanger.factor(sound.getLocation());
			if (f != 1f) {
				cir.setReturnValue(Mth.clamp(cir.getReturnValueF() * f, 0.0F, 1.0F));
			}
		} catch (Throwable t) {
			// never break audio over a slider
		}
	}
}
