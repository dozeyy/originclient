package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.WeatherOverride;
import net.minecraft.client.Minecraft;
import net.minecraft.util.BlockPos;
import net.minecraft.world.biome.BiomeGenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Weather Changer "Snow" on 1.8.9: snow in every biome, not only cold ones.
 *
 * EntityRenderer.renderRainSnow draws a column when canRain() || getEnableSnow(), as snow
 * when getFloatTemperature(pos) < 0.15; addRainParticles splashes (and plays rain) only when
 * the temperature is >= 0.15 (javap, Forge 11.15.1.2318). EntityRenderer is OptiFine-replaced,
 * so those calls can't be redirected; instead WeatherRenderMixin raises
 * WeatherOverride.precipPass for exactly those two passes and this answers "snowy, cold" while
 * it is up. The extra client-thread check keeps the integrated server and chunk-builder
 * threads (biome grass colour) on the real answer. Normal cost: one static boolean read.
 * Fail-soft: HEAD only, defaultRequire 0 -- if it doesn't apply, Snow falls as vanilla does.
 */
@Mixin(BiomeGenBase.class)
public abstract class BiomeSnowMixin {

	@Inject(method = "getFloatTemperature", at = @At("HEAD"), cancellable = true)
	private void originclient$coldForSnow(BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (WeatherOverride.precipPass && Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
			cir.setReturnValue(0.0f);
		}
	}

	@Inject(method = "getEnableSnow", at = @At("HEAD"), cancellable = true)
	private void originclient$snowEverywhere(CallbackInfoReturnable<Boolean> cir) {
		if (WeatherOverride.precipPass && Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
			cir.setReturnValue(true);
		}
	}
}
