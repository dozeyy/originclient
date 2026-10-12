package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Full Bright / Lighting. 26.2 moved the lightmap off LightTexture.updateLightTexture
// (an OptionInstance.get redirect) onto a render-state model: the extractor fills a
// LightmapRenderState that Lightmap.render() then consumes. We boost that state
// after it's extracted — brightness (the gamma floor) to max, ambient to white,
// and a full night-vision term — which flattens the whole map to bright. Boost
// Factor is the softer lift used when Full Bright is off. Fail-soft via require 0.
@Mixin(LightmapRenderStateExtractor.class)
public class LightmapRenderStateExtractorMixin {

	@Inject(method = "extract", at = @At("TAIL"))
	private void originclient$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		if (!Mods.on("fullbright")) {
			return;
		}
		if (Mods.bool("fullbright", "fullBright")) {
			state.brightness = 1.0f;
			state.darknessEffectScale = 0.0f;
			state.nightVisionEffectIntensity = 1.0f;
			state.ambientColor = LightmapRenderStateExtractor.WHITE;
			state.needsUpdate = true;
		} else {
			// Boost Factor (1..10): a gentler lift of the gamma floor only.
			double boost = Mods.num("fullbright", "gamma");
			if (boost > 1.0) {
				state.brightness = Math.min(1.0f, state.brightness + (float) ((boost - 1.0) * 0.1));
				state.needsUpdate = true;
			}
		}
	}
}
