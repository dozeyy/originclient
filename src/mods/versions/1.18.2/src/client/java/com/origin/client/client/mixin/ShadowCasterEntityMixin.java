package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// SETTINGS > Performance > Limit Far Shadows + Off-Map Shadows, entity half (render/ShadowCasterLimit,
// render/OffMapShadows).
// Target: EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z at RETURN (vanilla 1.18.2 Mojmap,
//   the same method EntityRenderDispatcherMixin already hooks; generic <E extends Entity>, erased).
// Why: Iris 1.6.11 net.coderbot ShadowRenderer.renderEntities / renderPlayerEntity ask exactly this for
//   every loaded entity during the shadow pass (javap 2026-10-20), then rebuild the model of each one that
//   passes. Answering false there skips that rebuild. Outside the shadow pass both features always answer
//   "keep", so the normal view is untouched.
// ModifyReturnValue, not a cancellable Inject: this runs for every entity on every pass, and a cancellable
//   RETURN Inject allocates a CallbackInfoReturnable each call; this allocates nothing. The arguments are
//   vanilla's (one build per module), so declaring them is safe.
// What breaks if it stops applying: nothing visible -- far / off-map mobs keep casting shadows and the
//   saving is gone (the F3 lines stay at 0 skipped).
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCasterEntityMixin {
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean originclient$farShadowCaster(boolean visible, Entity entity, Frustum frustum,
			double camX, double camY, double camZ) {
		// Off-Map Shadows first: an entity entirely off the shadow map's x/y edges.
		// OffMapShadows links Sodium types: only with a Sodium build its hooks were proven on.
		if (visible && ((com.origin.client.client.compat.CompatGates.on("sodium") && OffMapShadows.skipEntity(entity, camX, camY, camZ))
				|| ShadowCasterLimit.skipEntity(entity, camX, camY, camZ))) {
			return false;
		}
		return visible;
	}
}
