package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.HalfRateShadows;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Iris 1.8.8 ShadowRenderer (javap of the jar 2026-10-25):
 * renderShadows(Lnet/irisshaders/iris/mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V,
 * static createShadowModelView(FF)Lcom/mojang/blaze3d/vertex/PoseStack; -- four floats on Iris 1.8.8 (two on
 * the 1.7.3 this module used before 2026-10-25), so that handler takes only the CallbackInfoReturnable (no argument list to go stale).
 * No @Shadow and no direct field access: MODELVIEW, ACTIVE and packHasVoxelization are read by
 * reflection in HalfRateShadows, so a different Iris build only turns the feature off.
 * Why: Half-Rate Shadows (render/HalfRateShadows). renderShadows HEAD skips the whole shadow draw on
 * a kept frame (shadow walk, terrain/entity draws, mipmaps, shadowcomp); RETURN saves the matrix and
 * camera of a real draw. createShadowModelView RETURN (callers: renderShadows and the
 * shadowModelView uniform in MatrixUniforms) corrects the uniform on a kept frame so the main pass
 * looks the kept map up where it was drawn (no swimming).
 * What breaks if it stops applying: nothing -- HalfRateShadows keeps a frame only after both the
 * draw save and the matrix hook have been seen running; otherwise shadows draw every frame.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class IrisShadowRendererHalfRateMixin {
	@Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true)
	private void originclient$keepShadowMap(CallbackInfo ci) {
		if (HalfRateShadows.skipShadowDraw()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderShadows", at = @At("RETURN"))
	private void originclient$shadowDrawn(CallbackInfo ci) {
		HalfRateShadows.afterShadowDraw(this);
	}

	@Inject(method = "createShadowModelView", at = @At("RETURN"))
	private static void originclient$keptModelView(CallbackInfoReturnable<PoseStack> cir) {
		HalfRateShadows.onShadowModelView(cir.getReturnValue());
	}
}
