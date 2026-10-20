package com.origin.client.client.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// PER-VERSION (1.21.2+): Limit Far Shadows and Off-Map Shadows test each shadow-casting entity's
// CULLING box (render/ShadowCasterLimit, render/OffMapShadows). 1.21.1 read it from
// Entity.getBoundingBoxForCulling(); 1.21.2+ has it on the renderer.
// Target: EntityRenderer.getBoundingBoxForCulling(T)AABB, protected (javap of the 1.21.6 Mojmap jar
//   2026-10-20; the base returns entity.getBoundingBox(), renderers such as the guardian's widen it).
//   The vanilla culling test (EntityRenderer.shouldRender) uses this same box.
// What breaks if it stops applying: the cast in ShadowCasterLimit.cullBox throws, both features catch
//   it and turn themselves off for the session (F3 says so); shadows render as with the toggles off.
@Mixin(EntityRenderer.class)
public interface EntityRendererCullingBoxInvoker {
	@Invoker("getBoundingBoxForCulling")
	AABB originclient$cullingBox(Entity entity);
}
