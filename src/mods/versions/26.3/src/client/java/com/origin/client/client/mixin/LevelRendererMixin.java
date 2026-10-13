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

	// 26.3: weather is no longer its own framegraph pass (addWeatherPass is gone);
	// Weather "Clear" empties the extracted rain/snow state in WeatherEffectRendererMixin.

	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void originclient$flatSky(CallbackInfo ci) {
		// Flat mode skips the sky pass entirely, leaving the cheap flat backdrop.
		if (Mods.on("customsky") && Mods.mode("customsky", "mode").equals("Flat")) {
			ci.cancel();
		}
	}

	// Block Outline + Overlay: cancel vanilla's own outline submit when Origin
	// draws it (26.2: LevelRenderer.submitBlockOutline(PoseStack,
	// SubmitNodeCollector, LevelRenderState), javap). The Origin outline/overlay
	// itself is submitted from the level-render event (BlockOverlayRenderer).
	// Fail-soft: any throw leaves vanilla's outline untouched.
	@Inject(method = "submitBlockOutline", at = @At("HEAD"), cancellable = true)
	private void originclient$blockOutline(com.mojang.blaze3d.vertex.PoseStack poseStack,
			net.minecraft.client.renderer.SubmitNodeCollector collector,
			net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState, CallbackInfo ci) {
		try {
			var outline = levelRenderState.blockOutlineRenderState;
			if (outline == null || !com.origin.client.client.mods.BlockOverlayRenderer.wouldDraw(outline)) {
				return;
			}
			ci.cancel();
		} catch (Throwable t) {
			// fail-soft: let vanilla draw its own outline
		}
	}
}
