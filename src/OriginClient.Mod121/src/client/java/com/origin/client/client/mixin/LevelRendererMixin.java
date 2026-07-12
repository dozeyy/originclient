package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
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

	@Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
	private void originclient$flatSky(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
									  Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
		if (Mods.on("customsky") && Mods.mode("customsky", "mode").equals("Flat")) {
			skyFogSetup.run();
			ci.cancel();
		}
	}

	// Block Outline + Overlay moved to BlockOverlayRenderer (Fabric BLOCK_OUTLINE
	// event) so it can draw a filled overlay, not just lines.
}
