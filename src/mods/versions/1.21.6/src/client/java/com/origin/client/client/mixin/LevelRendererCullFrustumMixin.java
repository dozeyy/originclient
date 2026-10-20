package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: LevelRenderer.prepareCullFrustum(Vec3, Matrix4f, Matrix4f) (1.21.6, javap 2026-10-20:
 * public, same descriptor as 1.21.1; GameRenderer.renderLevel calls it once per frame, right before
 * LevelRenderer.renderLevel), and the debug capturedFrustum field (still private Frustum; a
 * captureFrustum request fills it on the next frame, which this then sees as captured).
 * Why: Smart Terrain Updates only reuses the chunk graph when the
 * culling frustum is bit-identical to the last frame's and no frustum is captured.
 * What breaks if it stops applying: the feature refuses to reuse (this hook must be seen firing).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullFrustumMixin {
	@Shadow
	private Frustum capturedFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void originclient$cullFingerprint(Vec3 pos, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
			CallbackInfo ci) {
		SmartTerrainUpdates.onCullFrustum(pos, frustumMatrix, projectionMatrix, capturedFrustum != null);
	}
}
