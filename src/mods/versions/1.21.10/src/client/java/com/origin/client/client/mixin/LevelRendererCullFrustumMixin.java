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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Target: LevelRenderer.prepareCullFrustum(Matrix4f, Matrix4f, Vec3)Frustum (1.21.10 Mojmap, javap
 * 2026-10-20). PER-VERSION (1.21.9+): now private, returns the frustum, and takes the camera position
 * LAST (1.21.1-1.21.8 had public (Vec3, Matrix4f, Matrix4f)V). LevelRenderer.renderLevel calls it once
 * per frame (offset 111), right before "cullTerrain" (the main chunk walk). The two matrices are the
 * Frustum constructor's (frustum matrix, projection), in that order. capturedFrustum is still the debug
 * "frozen frustum" field (private Frustum).
 * Why: Smart Terrain Updates only reuses the chunk graph when the culling frustum is bit-identical to
 * the last frame's and no frustum is captured.
 * What breaks if it stops applying: the feature refuses to reuse (this hook must be seen firing).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullFrustumMixin {
	@Shadow
	private Frustum capturedFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void originclient$cullFingerprint(Matrix4f frustumMatrix, Matrix4f projectionMatrix, Vec3 pos,
			CallbackInfoReturnable<Frustum> cir) {
		// Sodium is a separate install here: never touch SmartTerrainUpdates (links Sodium types) without it.
		if (!com.origin.client.client.render.SodiumIrisBuilds.SODIUM) {
			return;
		}
		SmartTerrainUpdates.onCullFrustum(pos, frustumMatrix, projectionMatrix, capturedFrustum != null);
	}
}
