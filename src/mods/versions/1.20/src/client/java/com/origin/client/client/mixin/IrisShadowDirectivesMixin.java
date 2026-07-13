package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Cross-mod @Pseudo mixin into Iris's PackShadowDirectives.
//
// TARGET PACKAGE: on the Iris build this module ships (1.6.4+1.20) the class is
// net.coderbot.iris.shaderpack.PackShadowDirectives -- Iris didn't finish the
// net.coderbot -> net.irisshaders rename until 1.7. The 1.21.1/1.20.4 modules
// target the new net.irisshaders...properties path; THIS module must use the
// old coderbot path or the mixin silently never applies (@Pseudo + require=0 =
// no error, just a dead no-op -- which is exactly what happened before this
// fix: Shader Performance Mode did nothing at all on 1.20). Bytecode-verified
// against iris-mc1.20-1.6.4.jar: getDistance()F and getResolution()I both live
// on the coderbot class.
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
@Mixin(targets = "net.coderbot.iris.shaderpack.PackShadowDirectives", remap = false)
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
