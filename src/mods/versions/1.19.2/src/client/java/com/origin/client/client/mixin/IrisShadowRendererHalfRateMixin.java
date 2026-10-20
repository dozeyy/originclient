package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.render.HalfRateShadows;
import com.origin.client.client.render.OffMapShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Iris 1.6.11+mc1.19.2 net.coderbot.iris.pipeline.ShadowRenderer (javap of the jar 2026-10-20):
 * renderShadows(Lnet/coderbot/iris/mixin/LevelRendererAccessor;Lnet/minecraft/class_4184;)V and static
 * createShadowModelView(FF)Lcom/mojang/blaze3d/vertex/PoseStack; (2 floats here, 4 on Iris 1.8). No handler
 * declares a target argument, no @Shadow and no direct field access: MODELVIEW, ACTIVE, halfPlaneLength,
 * fov and packHasVoxelization are read by reflection, so a different Iris build only turns the features off.
 * Why: Half-Rate Shadows (render/HalfRateShadows). renderShadows HEAD skips the whole shadow draw on a kept
 * frame (shadow walk, terrain/entity draws, mipmaps, shadowcomp); RETURN saves the matrix and camera of a
 * real draw. createShadowModelView RETURN corrects the shadowModelView uniform (MatrixUniforms calls it)
 * on a kept frame so the main pass looks the kept map up where it was drawn (no swimming).
 * Off-Map Shadows (render/OffMapShadows) reads this renderer's ortho half plane at renderShadows HEAD:
 * this Iris has no static PROJECTION, and builds the projection only after the shadow walk.
 * What breaks if it stops applying: nothing -- HalfRateShadows keeps a frame only after both the draw save
 * and the matrix hook have been seen running, and Off-Map Shadows never arms; shadows draw every frame.
 */
@Pseudo
@Mixin(targets = "net.coderbot.iris.pipeline.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererHalfRateMixin {
	@Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$keepShadowMap(CallbackInfo ci) {
		OffMapShadows.onShadowPass(this);
		if (HalfRateShadows.skipShadowDraw()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderShadows", at = @At("RETURN"), remap = false)
	private void originclient$shadowDrawn(CallbackInfo ci) {
		HalfRateShadows.afterShadowDraw(this);
	}

	@Inject(method = "createShadowModelView", at = @At("RETURN"), remap = false)
	private static void originclient$keptModelView(CallbackInfoReturnable<PoseStack> cir) {
		HalfRateShadows.onShadowModelView(cir.getReturnValue());
	}
}
