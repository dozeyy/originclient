package com.origin.client.client.mixin;

import com.origin.client.client.render.HalfRateShadows;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: Iris 1.11.4+mc26.2 ShadowRenderer (javap of the jar 2026-10-20):
 * renderShadows(LevelRendererAccessor, Camera, CameraRenderState)V -- a third argument since 1.21.1's
 * build, so these handlers declare NO target arguments (CallbackInfo only, matched by name) -- and
 * static createShadowModelView(FFFF)PoseStack (unchanged; the handler reads only the return value).
 * No @Shadow and no direct field access: MODELVIEW, ACTIVE and packHasVoxelization are read by
 * reflection in HalfRateShadows, so a different Iris build only turns the feature off.
 * @Pseudo + string target: without Iris the mixin is skipped, never a load error.
 * Why: Half-Rate Shadows (render/HalfRateShadows). renderShadows HEAD skips the whole shadow draw on a
 * kept frame (shadow walk, terrain/entity draws, shadowcomp); RETURN saves the matrix and camera of a
 * real draw. createShadowModelView RETURN (called by renderShadows, IrisInternalUniforms and the
 * MatrixUniforms shadowModelView supplier) corrects the shadowModelView uniform on a kept frame, so the
 * main pass looks the kept map up where it was drawn (no swimming).
 * What breaks if it stops applying: nothing -- HalfRateShadows keeps a frame only after both the draw
 * save and the matrix hook have been seen running; otherwise shadows draw every frame. Chunk-change
 * invalidation lives in SodiumRenderSectionManagerHooksMixin (no argument-dependent handlers there).
 */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
public abstract class IrisShadowRendererHalfRateMixin {
	@Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false)
	private void originclient$keepShadowMap(CallbackInfo ci) {
		if (HalfRateShadows.skipShadowDraw()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderShadows", at = @At("RETURN"), remap = false)
	private void originclient$shadowDrawn(CallbackInfo ci) {
		HalfRateShadows.afterShadowDraw(this);
	}

	@Inject(method = "createShadowModelView", at = @At("RETURN"), remap = false)
	private static void originclient$keptModelView(CallbackInfoReturnable<com.mojang.blaze3d.vertex.PoseStack> cir) {
		HalfRateShadows.onShadowModelView(cir.getReturnValue());
	}
}