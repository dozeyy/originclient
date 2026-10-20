package com.origin.client.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Target: EntityRenderer.getBoundingBoxForCulling(T)AABB (protected; erased to Entity), javap of 26.2.
// Why: Limit Far Shadows / Off-Map Shadows (render/ShadowCasterLimit.cullBox) measure an entity by
//   the same culling box vanilla's own frustum test uses, which lives on the renderer since 1.21.2.
// What breaks if it stops applying: the first call throws; both callers catch it and turn their
//   feature off for the session (shadows draw normally).
@Mixin(EntityRenderer.class)
public interface EntityRendererCullingBoxInvoker {
	@Invoker("getBoundingBoxForCulling")
	AABB originclient$cullingBox(Entity entity);
}