package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
import com.origin.client.client.mods.WeatherOverride;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import com.mojang.math.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Three render-only client mods in one target class:
//  - Weather Toggle: skips rain/snow rendering entirely (server weather and
//    gameplay effects untouched — this is draw-skipping only).
//  - Custom Sky "Flat" mode: skips the sky pass, leaving the fog color as a
//    cheap flat backdrop (also removes the sky's render cost).
//  - Block Overlay: replaces the vanilla hit outline with a configurable
//    color/opacity/thickness one (thickness is emulated by re-emitting the
//    edges with tiny offsets — line-width state isn't reliable on core GL).
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
	private void originclient$skipWeather(LightTexture lightTexture, float partialTick,
										  double camX, double camY, double camZ, CallbackInfo ci) {
		// Clear mode skips the precipitation pass; other modes force rain
		// levels tick-side (OriginClientMod.applyWeather) and render normally.
		if (Mods.on("weather") && Mods.mode("weather", "mode").equals("Clear")) {
			ci.cancel();
		}
	}

	// 1.17.1 sky era: renderSky is (PoseStack, Matrix4f, float, Runnable) --
	// no Camera / isFoggy params yet (those arrive in 1.18). The skyFogSetup
	// Runnable is the 4th arg; run it before cancelling so fog still sets up.
	@Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
	private void originclient$flatSky(PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
									  Runnable skyFogSetup, CallbackInfo ci) {
		if (Mods.on("customsky") && Mods.mode("customsky", "mode").equals("Flat")) {
			skyFogSetup.run();
			ci.cancel();
		}
	}

	// Block Outline + Overlay moved to BlockOverlayRenderer (Fabric BLOCK_OUTLINE
	// event) so it can draw a filled overlay, not just lines.

	// Weather Changer "Snow": snow in EVERY biome. Minecraft picks rain or snow per column from
	// the biome, so Snow used to show plain rain in warm biomes. These rewrite only the answers
	// LevelRenderer's precipitation pass and rain-splash tick read: client-only, the Biome
	// itself (shared with the integrated server) is never touched. WeatherOverride.snow is a
	// field refreshed once a tick, so the per-column cost is one read. Fail-soft: the config's
	// defaultRequire is 0, so if another mod rewrote these calls the hooks skip and the
	// biome's own answer stands.
	// 1.16.5/1.17.1 (javap): Biome.getPrecipitation() (NONE skips the column; tickRain splashes
	// only on RAIN) and getTemperature(pos) (< 0.15 draws snow).
	@WrapOperation(method = {"renderSnowAndRain", "tickRain"}, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitation()Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
	private Biome.Precipitation originclient$snowEverywhere(Biome biome, Operation<Biome.Precipitation> original) {
		return WeatherOverride.snow ? Biome.Precipitation.SNOW : original.call(biome);
	}

	@WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Biome;getTemperature(Lnet/minecraft/core/BlockPos;)F"))
	private float originclient$snowCold(Biome biome, BlockPos pos, Operation<Float> original) {
		return WeatherOverride.snow ? 0.0f : original.call(biome, pos);
	}
}
