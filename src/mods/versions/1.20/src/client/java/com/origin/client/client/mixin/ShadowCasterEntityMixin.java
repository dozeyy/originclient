package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// SETTINGS > Performance > Limit Far Shadows and Off-Map Shadows, entity half
// (render/ShadowCasterLimit, render/OffMapShadows).
// Target: EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z at RETURN (javap, MC 1.20.1
//   Mojmap 2026-10-20, same shape as 1.21.1; generic <E extends Entity>, erased to Entity).
// Why: Iris 1.7.6 (and 1.6.4 on MC 1.20) ShadowRenderer.renderEntities / renderPlayerEntity ask exactly this for every
//   loaded entity during the shadow pass, then rebuild the model of each one that passes. Answering
//   false there skips that rebuild. Outside the shadow pass both helpers always answer "keep", so the
//   normal view is untouched. The culling box is entity.getBoundingBoxForCulling() (public on 1.20.4).
// What breaks if it stops applying: nothing visible -- far / off-map mobs simply keep casting
//   shadows and the saving is gone (the F3 lines stay at 0 skipped).
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCasterEntityMixin {
	// ModifyReturnValue, not a cancellable Inject: this runs for every entity on every pass, and a
	// cancellable RETURN Inject allocates a CallbackInfoReturnable each call; this allocates nothing.
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean originclient$farShadowCaster(boolean visible, Entity entity, Frustum frustum,
			double camX, double camY, double camZ) {
		// Off-Map Shadows first: an entity entirely off the shadow map's x/y edges.
		// OffMapShadows uses Sodium types: never loaded without Sodium (Sodium-free constant first).
		if (visible && ((com.origin.client.client.render.SodiumIrisBuilds.SODIUM && OffMapShadows.skipEntity(entity, camX, camY, camZ)
				|| ShadowCasterLimit.skipEntity(entity, camX, camY, camZ)))) {
			return false;
		}
		return visible;
	}
}
