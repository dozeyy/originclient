package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.origin.client.client.render.OffMapShadows;
import net.coderbot.iris.pipeline.ShadowRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SETTINGS > Performance > Off-Map Shadows: where the shadow projection comes from on Iris 1.6.x (MC 1.20;
 * CompatMixinPlugin (originclient.compat-gates.txt) applies it on Iris 1.6.4 only -- Iris 1.7.6 on MC 1.20.1 has ShadowRenderer.PROJECTION).
 * Target: Iris 1.6.4 net.coderbot.iris.pipeline.ShadowRenderer.renderShadows(LevelRendererAccessor,
 * Camera)V (javap of the jar 2026-10-20): HEAD, and its two projection builders -- INVOKE
 * ShadowMatrices.createPerspectiveMatrix(F) (offset 317, a pack with a shadow fov) and
 * ShadowMatrices.createOrthoMatrix(F) (offset 329, halfPlaneLength) -- whose result goes straight to
 * IrisRenderSystem.setShadowProjection. Iris 1.7+ stores it in a static ShadowRenderer.PROJECTION;
 * 1.6.x keeps it in a local, so this hands it to render/OffMapShadows. No handler takes the target's
 * arguments (CallbackInfo / the returned value only), so an argument change can only lose a handler.
 * Why: Off-Map Shadows tests each shadow-walk section and shadow-pass entity against the map's x/y
 * edges, which needs PROJECTION * MODELVIEW (MODELVIEW is a static on every Iris).
 * What breaks if it stops applying: nothing visible -- with no projection OffMapShadows never skips
 * anything (shadows are drawn exactly as without the feature) and its F3 line says why.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class IrisShadowProjectionMixin {
	@Inject(method = "renderShadows", at = @At("HEAD"))
	private void originclient$shadowFrameBegin(CallbackInfo ci) {
		OffMapShadows.onShadowRendererBegin(this);
	}

	@ModifyExpressionValue(method = "renderShadows", at = @At(value = "INVOKE",
			target = "Lnet/coderbot/iris/shadows/ShadowMatrices;createOrthoMatrix(F)Lorg/joml/Matrix4f;"))
	private Matrix4f originclient$orthoProjection(Matrix4f projection) {
		OffMapShadows.onShadowProjection(this, projection);
		return projection;
	}

	@ModifyExpressionValue(method = "renderShadows", at = @At(value = "INVOKE",
			target = "Lnet/coderbot/iris/shadows/ShadowMatrices;createPerspectiveMatrix(F)Lorg/joml/Matrix4f;"))
	private Matrix4f originclient$perspectiveProjection(Matrix4f projection) {
		OffMapShadows.onShadowProjection(this, projection);   // not ortho: OffMapShadows leaves it alone
		return projection;
	}
}
