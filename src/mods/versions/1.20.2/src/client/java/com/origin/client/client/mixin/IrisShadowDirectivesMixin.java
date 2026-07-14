package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Cross-mod @Pseudo mixin into Iris's PackShadowDirectives. Shadow render
// distance is read from getDistance() by every Java-side consumer -- the
// shadow ortho matrix, the per-frame culling, and the shadowDistance uniform
// (bytecode-confirmed against iris-1.7.2: ShadowRenderer, MatrixUniforms,
// IrisRenderingPipeline) -- so halving it here shrinks the shadow pass
// coherently for EVERY shaderpack: far fewer chunks rendered into the shadow
// map each frame, the biggest single win with shaders enabled.
//
// Resolution (getResolution) is deliberately NOT halved on this version:
// Iris allocates the shadow framebuffer from that getter at pipeline creation,
// but a pack's own GLSL `const int shadowMapResolution` compiles as-written
// (Iris only rewrites it through its user-facing option system, see
// OptionAnnotatedSource) -- so serving a halved value gives const-math packs a
// half-size depth texture under full-size texel arithmetic and visibly breaks
// their shadows (confirmed in-game on 1.20.4 + Complementary).
//
// Behavior: when SETTINGS > Performance > Shader Performance Mode is on, the
// pack's shadow distance is always served at half; toggling triggers an Iris
// reload (OriginModMenuScreen) so cached-at-creation and per-frame readers
// never disagree mid-run. remap = false (Iris isn't Minecraft-mapped) and
// require = 0 so this whole mixin no-ops cleanly if Iris is absent or renames
// its internals in a future version.
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
