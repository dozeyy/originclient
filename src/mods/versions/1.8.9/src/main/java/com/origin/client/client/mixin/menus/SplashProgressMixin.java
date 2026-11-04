package com.origin.client.client.mixin.menus;

import com.origin.client.client.render.StartupLoading;
import net.minecraftforge.fml.client.SplashProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Target: FML's SplashProgress (Forge 11.15.1.2318), the boot splash with the Mojang logo,
// the Forge anvil and the red bars, drawn by its own thread with a shared GL context.
// Why: Origin's startup loading screen replaces it (StartupLoading; Will, 2026-11-04).
//  - getBool HEAD: start() reads `enabled = getBool("enabled", !mac) && (no OptiFine ||
//    OptiFine set "optifine.ForgeSplashCompatible")` and returns before creating its thread
//    when that is false (javap 2026-11-04). Answering "enabled" with false switches the splash
//    off whatever splash.properties says (the launcher writes enabled=false there too:
//    ForgeSplashOff). Every later SplashProgress call (finish, pause, resume,
//    drawVanillaScreen) then takes its "not enabled" path.
//  - start RETURN: the window is open and the main thread still owns the GL context -- the
//    startup screen's first frame, before any mod is constructed.
// Forge's own class (never obfuscated, not touched by OptiFine): remap = false, HEAD/RETURN
// only. If this stops applying, FML's splash shows as it used to and StartupLoading never
// draws (it checks the GL context is this thread's).
@Mixin(value = SplashProgress.class, remap = false)
public abstract class SplashProgressMixin {
	@Inject(method = "getBool(Ljava/lang/String;Z)Z", at = @At("HEAD"), cancellable = true, remap = false)
	private static void originclient$forgeSplashOff(String name, boolean fallback, CallbackInfoReturnable<Boolean> cir) {
		if ("enabled".equals(name)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "start()V", at = @At("RETURN"), remap = false)
	private static void originclient$firstFrame(CallbackInfo ci) {
		try {
			StartupLoading.begin();
		} catch (Throwable t) {
			// the load continues on a blank window
		}
	}
}
