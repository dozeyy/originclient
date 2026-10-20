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
 * Target: LevelRenderer.prepareCullFrustum(Matrix4f, Matrix4f, Vec3)Frustum (1.21.11, javap 2026-10-20:
 * PER-VERSION -- now private, returns the frustum, and takes the camera position LAST; 1.21.1-1.21.4 had
 * public (Vec3, Matrix4f, Matrix4f)V). renderLevel calls it once per frame (offset 118), before Iris's
 * shadow pass and the main walk. capturedFrustum is still the debug "frozen frustum" field.
 * Why: Smart Terrain Updates only reuses the chunk graph when the culling frustum is bit-identical to
 * the last frame's and no frustum is captured.
 * What breaks if it stops applying: the feature refuses to reuse (this hook must be seen firing).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererCullFrustumMixin {
	@Shadow
	private Frustum capturedFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void originclient$cullFingerprint(Matrix4f a, Matrix4f b, Vec3 pos, CallbackInfoReturnable<Frustum> cir) {
		SmartTerrainUpdates.onCullFrustum(a, b, pos, capturedFrustum != null);
	}
}
