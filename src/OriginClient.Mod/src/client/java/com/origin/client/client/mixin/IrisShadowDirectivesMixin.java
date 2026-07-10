package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Cross-mod @Pseudo mixin into Iris's PackShadowDirectives. Shadow map
// resolution and shadow render distance are read from these two getters by both
// the shadow framebuffer allocation (in ShadowRenderer's constructor) and the
// per-frame shadow culling, so halving the returned values here applies to
// EVERY shaderpack with no per-pack config — the biggest single GPU win with
// shaders enabled (a 2048 -> 1024 shadow map is a quarter of the pixels).
//
// Behavior: when SETTINGS > Performance > Shader Performance Mode is on, the
// pack's shadow settings are always served at half. Tuning a shader's own
// shadow sliders still works (the pack re-resolves, we halve the new value);
// to get full quality back the user turns the toggle off. remap = false (Iris
// isn't Minecraft-mapped) and require = 0 so this whole mixin no-ops cleanly if
// Iris is absent or renames its internals in a future version.
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shaderpack.properties.PackShadowDirectives", remap = false)
public class IrisShadowDirectivesMixin {
	private static boolean originclient$perf() {
		try {
			return Mods.bool(Mods.PERFORMANCE_ID, "shaderPerformanceMode");
		} catch (Throwable t) {
			return false;
		}
	}

	@Inject(method = "getResolution", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void originclient$halveResolution(CallbackInfoReturnable<Integer> cir) {
		if (!originclient$perf()) {
			return;
		}
		int res = cir.getReturnValueI();
		// Keep a sane floor so a low-res pack doesn't collapse to mush.
		if (res > 512) {
			cir.setReturnValue(Math.max(512, res / 2));
		}
	}

	@Inject(method = "getDistance", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void originclient$halveDistance(CallbackInfoReturnable<Float> cir) {
		if (!originclient$perf()) {
			return;
		}
		float dist = cir.getReturnValueF();
		// A non-positive distance means "unbounded / follow render distance" —
		// leave that alone; only halve a real, positive shadow distance.
		if (dist > 0f) {
			cir.setReturnValue(dist / 2f);
		}
	}
}
