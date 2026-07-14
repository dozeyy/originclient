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
// crashed. (Block Overlay lives in BlockOverlayRenderer via the Fabric event.)
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
}
