package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Two render-only client mods. The 1.21.2 render rewrite moved sky + weather into
// the framegraph: LevelRenderer.addSkyPass(...) / addWeatherPass(...) build those
// passes (the old renderSky / renderSnowAndRain are gone). We HEAD-cancel the whole
// pass to skip it — Weather Toggle "Clear" and Custom Sky "Flat".
//
// These target methods are PRIVATE and their param lists drift every version
// (FogParameters -> GpuBufferSlice -> fewer args), so we match by NAME with a
// CallbackInfo-ONLY handler (captures no target locals) — that binds regardless of
// the signature. required:false: if a name ever changes the inject is skipped, not
// crashed. (Block Outline + Overlay: direct renderBlockOutline hook below.)
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	@Inject(method = "addWeatherPass", at = @At("HEAD"), cancellable = true)
	private void originclient$skipWeather(CallbackInfo ci) {
		// Clear mode skips the precipitation pass; other modes force rain levels
		// tick-side (OriginClientMod.applyWeather) and render normally.
		if (Mods.on("weather") && Mods.mode("weather", "mode").equals("Clear")) {
			ci.cancel();
		}
	}

	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void originclient$flatSky(CallbackInfo ci) {
		// Flat mode skips the sky pass entirely, leaving the cheap flat backdrop.
		if (Mods.on("customsky") && Mods.mode("customsky", "mode").equals("Flat")) {
			ci.cancel();
		}
	}

	// Block Outline + Overlay: direct hook on vanilla's own outline draw (the
	// Lunar model — own the draw path instead of riding Fabric's
	// BEFORE_BLOCK_OUTLINE event, which proved fragile on 1.21.11). Vanilla
	// calls renderBlockOutline twice per frame (solid pass, then the
	// translucent sort layer); Origin draws once in the solid pass and cancels
	// both so exactly one outline ever shows. Fail-soft: any throw leaves
	// vanilla's outline untouched.
	@Inject(method = "renderBlockOutline", at = @At("HEAD"), cancellable = true)
	private void originclient$blockOutline(net.minecraft.client.renderer.MultiBufferSource.BufferSource bufferSource,
			com.mojang.blaze3d.vertex.PoseStack poseStack, boolean translucentPass,
			net.minecraft.client.renderer.state.LevelRenderState levelRenderState, CallbackInfo ci) {
		try {
			var outline = levelRenderState.blockOutlineRenderState;
			if (outline == null || !com.origin.client.client.mods.BlockOverlayRenderer.wouldDraw(outline)) {
				return;
			}
			// Cancel-ONLY: the actual Origin outline/overlay draws from the
			// END_MAIN world event (the path proven to run under Sodium+Iris);
			// this hook just suppresses vanilla's white outline when it runs.
			ci.cancel();
		} catch (Throwable t) {
			// fail-soft: let vanilla draw its own outline
		}
	}
}
