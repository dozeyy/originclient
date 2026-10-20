package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: LevelRenderer.prepareCullFrustum(Vec3, Matrix4f, Matrix4f) (MC 1.21 Mojmap, javap 2026-10-20: offset 557 of
 * GameRenderer.renderLevel calls it once per frame, right before LevelRenderer.renderLevel), and
 * the debug capturedFrustum field. Why: Smart Terrain Updates only reuses the chunk graph when the
 * culling frustum is bit-identical to the last frame's and no frustum is captured.
 * What breaks if it stops applying: the feature refuses to reuse (this hook must be seen firing).
 *
 * <p>PER-VERSION (1.21): Sodium is a separate install here and SmartTerrainUpdates holds Sodium
 * types (its static arrays), so without Sodium its first use throws NoClassDefFoundError from this
 * VANILLA hook, which runs every frame. The handler latches off on any Throwable instead (mandate 4:
 * never crash); with Sodium present the try costs nothing.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullFrustumMixin {
	@Shadow
	private Frustum capturedFrustum;

	@Unique
	private static boolean originclient$dead;

	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void originclient$cullFingerprint(Vec3 pos, Matrix4f frustumMatrix, Matrix4f projectionMatrix,
			CallbackInfo ci) {
		if (originclient$dead) {
			return;
		}
		try {
			SmartTerrainUpdates.onCullFrustum(pos, frustumMatrix, projectionMatrix, capturedFrustum != null);
		} catch (Throwable t) {
			originclient$dead = true;   // no Sodium (or a linkage change): Smart Terrain simply never reuses
		}
	}
}
