package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.SoundChanger;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.audio.SoundManager;
import net.minecraft.client.audio.SoundPoolEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Sound Changer: scales a sound's volume by its slider (SoundChanger.factor).
//
// 1.8.9 SoundManager works every sound's volume out in ONE private helper,
// getNormalizedVolume(ISound, SoundPoolEntry, SoundCategory) =
// clamp(volume x entry volume x category volume, 0, 1), called by playSound --
// where a result of 0 makes vanilla skip the sound outright, so a 0% slider truly
// mutes it -- and again whenever vanilla re-applies a playing sound's volume (a
// category slider move, tickable sounds every tick). Scaling its return value
// (factor is 0..1, so the clamp still holds) covers both paths in one hook, the
// same semantics as 1.16.5's calculateVolume hook.
@Mixin(SoundManager.class)
public abstract class SoundChangerMixin {
	@Inject(method = "getNormalizedVolume", at = @At("RETURN"), cancellable = true)
	private void originclient$scaleVolume(ISound sound, SoundPoolEntry entry, SoundCategory category,
										  CallbackInfoReturnable<Float> cir) {
		try {
			float f = SoundChanger.factor(sound.getSoundLocation());
			if (f != 1f) {
				cir.setReturnValue(cir.getReturnValueF() * f);
			}
		} catch (Throwable t) {
			// never break audio over a slider
		}
	}
}
