package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Two render-only client mods. The 1.21.2 render rewrite moved sky + weather into
// the framegraph: LevelRenderer.addSkyPass(...) / addWeatherPass(...) build those
// passes (the old renderSky / renderSnowAndRain are gone). We HEAD-cancel the sky
// pass for Custom Sky "Flat" (Weather "Clear" needs no pass hook, see below).
//
// These target methods are PRIVATE and their param lists drift every version
// (FogParameters -> GpuBufferSlice -> fewer args), so we match by NAME with a
// CallbackInfo-ONLY handler (captures no target locals) — that binds regardless of
// the signature. required:false: if a name ever changes the inject is skipped, not
// crashed. (Block Overlay lives in BlockOverlayRenderer via the Fabric event.)
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

	// Weather "Clear" no longer cancels addWeatherPass: that pass ALSO draws the
	// world border, so cancelling it hid the border (UHC / border-shrink servers).
	// Clear needs no render hook at all: LevelWeatherMixin -> WeatherOverride.rain()
	// reads 0 for the client level, so the weather renderer draws no rain, snow or
	// splashes, and the border still draws.

	@Inject(method = "addSkyPass", at = @At("HEAD"), cancellable = true)
	private void originclient$flatSky(CallbackInfo ci) {
		// Flat mode skips the sky pass entirely, leaving the cheap flat backdrop.
		if (Mods.on("customsky") && Mods.mode("customsky", "mode").equals("Flat")) {
			ci.cancel();
		}
	}
}
