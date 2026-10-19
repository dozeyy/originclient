package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Cross-mod @Pseudo mixin into Iris's PackShadowDirectives.
//
// TARGET PACKAGE: this jar runs on TWO Iris builds that straddle Iris's
// net.coderbot -> net.irisshaders rename (finished in 1.7): 1.20 gets
// iris-mc1.20-1.6.4 (net.coderbot.iris.shaderpack.PackShadowDirectives) and
// 1.20.1 gets iris-1.7.6+mc1.20.1 (net.irisshaders.iris.shaderpack.properties.
// PackShadowDirectives) -- PerformanceModCatalog.Data.cs. Both are targeted;
// @Pseudo skips whichever is absent. Targeting only one leaves the mixin a
// silent dead no-op on the other version (@Pseudo + require=0 = no error):
// Shader Performance Mode did nothing on 1.20 before the coderbot target, and
// on 1.20.1 until the irisshaders one was added (2026-10-19). Bytecode-verified
// (javap): getDistance()F exists on both classes.
//
// Shadow render distance is read from getDistance() by every Java-side consumer
// (shadow ortho matrix, per-frame culling, shadowDistance uniform), so halving
// it shrinks the shadow pass coherently for EVERY shaderpack: far fewer chunks
// into the shadow map each frame, the biggest single win with shaders enabled.
//
// Resolution (getResolution) is deliberately NOT halved: Iris allocates the
// shadow framebuffer from that getter at pipeline creation, but a pack's own
// GLSL `const int shadowMapResolution` compiles as-written -- so serving a
// halved value gives const-math packs a half-size depth texture under full-size
// texel arithmetic and visibly breaks their shadows.
//
// Behavior: when SETTINGS > Performance > Shader Performance Mode is on, the
// pack's shadow distance is always served at half; toggling triggers an Iris
// reload (OriginModMenuScreen) so cached-at-creation and per-frame readers
// never disagree mid-run. remap = false + require = 0 so this no-ops cleanly if
// Iris is absent or renames its internals.
@Pseudo
@Mixin(targets = {
		"net.coderbot.iris.shaderpack.PackShadowDirectives",              // Iris 1.6.4 (MC 1.20)
		"net.irisshaders.iris.shaderpack.properties.PackShadowDirectives" // Iris 1.7.6 (MC 1.20.1)
}, remap = false)
public class IrisShadowDirectivesMixin {
	private static boolean originclient$perf() {
		try {
			return Mods.bool(Mods.PERFORMANCE_ID, "shaderPerformanceMode");
		} catch (Throwable t) {
			return false;
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
