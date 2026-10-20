package com.origin.client.client.mixin;

import com.origin.client.client.render.SmartTerrainUpdates;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Camera.prepareCullFrustum(Matrix4fc, Matrix4f, Vec3)V (MC 26.1.2, javap 2026-10-20). PER-VERSION
 * (26.1): the culling frustum moved from LevelRenderer (1.21.11: private prepareCullFrustum(Matrix4f,
 * Matrix4f, Vec3)Frustum) to Camera, which now owns cullFrustum and the debug capturedFrustum; Camera.update
 * calls it once per frame (offset 131), before the main walk.
 * Why: Smart Terrain Updates (Sodium 0.8.9 stack only) reuses the chunk graph only when the culling
 * frustum is bit-identical to the last frame's and no frustum is captured.
 * What breaks if it stops applying: the feature refuses to reuse (this hook must be seen firing). Nothing
 * else reads it, so on the Sodium 0.9 stack it costs one cached boolean read per frame.
 */
@Mixin(Camera.class)
public abstract class CameraCullFrustumMixin {
	@Shadow
	private Frustum capturedFrustum;

	@Inject(method = "prepareCullFrustum", at = @At("HEAD"))
	private void originclient$cullFingerprint(Matrix4fc a, Matrix4f b, Vec3 pos, CallbackInfo ci) {
		SmartTerrainUpdates.onCullFrustum(a, b, pos, capturedFrustum != null);
	}
}
