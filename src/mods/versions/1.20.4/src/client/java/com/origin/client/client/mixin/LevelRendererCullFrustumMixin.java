package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
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
 * Target: LevelRenderer.prepareCullFrustum(PoseStack, Vec3, Matrix4f projection) (MC 1.20.4,
 * javap of the Loom jar 2026-10-20: GameRenderer.renderLevel calls it once per frame, right before
 * LevelRenderer.renderLevel; it builds the culling Frustum from poseStack.last().pose() and the
 * projection -- 1.21.1 passes that pose matrix in directly), and the debug capturedFrustum field. Why: Smart Terrain Updates only reuses the chunk graph when the
 * culling frustum is bit-identical to the last frame's and no frustum is captured.
 * What breaks if it stops applying: the feature refuses to reuse (this hook must be seen firing).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullFrustumMixin {
	@Shadow
	private Frustum capturedFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void originclient$cullFingerprint(PoseStack poseStack, Vec3 pos, Matrix4f projectionMatrix,
			CallbackInfo ci) {
		// the same matrix vanilla hands the Frustum constructor (offsets 0-4 of the method)
		if (!com.origin.client.client.render.SodiumLoaded.YES) {
			return;   // SmartTerrainUpdates uses Sodium types: never load it without Sodium
		}
		SmartTerrainUpdates.onCullFrustum(pos, poseStack.last().pose(), projectionMatrix, capturedFrustum != null);
	}
}
