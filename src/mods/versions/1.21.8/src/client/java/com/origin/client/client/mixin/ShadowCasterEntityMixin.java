package com.origin.client.client.mixin;

import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.culling.Frustum;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

// SETTINGS > Performance > Limit Far Shadows, entity half (render/ShadowCasterLimit).
// Target: EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z at RETURN (javap,
//   1.21.6 and 1.21.8 Mojmap, unchanged from 1.21.1; generic <E extends Entity>, erased to Entity).
// Why: Iris 1.9.6 ShadowRenderer.renderEntities / renderPlayerEntity ask exactly this
//   (bytecode offsets 68 and 33 in 1.9.6+mc1.21.8) for every loaded entity during the shadow pass, then rebuild
//   the model of each one that passes. Answering false there skips that rebuild. Outside the
//   shadow pass ShadowCasterLimit always answers "keep", so the normal view is untouched.
// What breaks if it stops applying: nothing visible -- far mobs simply keep casting shadows
//   and the saving is gone (the F3 line stays at 0 skipped).
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCasterEntityMixin {
	// The renderer local (slot 9, the only EntityRenderer local: getRenderer(entity) at offset 2, javap
	// 2026-10-20) is captured so the culling box comes from it without a second map lookup.
	// ModifyReturnValue, not a cancellable Inject: this runs for every entity on every pass, and a
	// cancellable RETURN Inject allocates a CallbackInfoReturnable each call; this allocates nothing.
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean originclient$farShadowCaster(boolean visible, Entity entity, Frustum frustum,
			double camX, double camY, double camZ, @Local EntityRenderer<?, ?> renderer) {
		// Off-Map Shadows first (render/OffMapShadows): an entity entirely off the shadow map's x/y edges.
		// OffMapShadows links Sodium types: only reached while Sodium is loaded (SodiumIrisBuilds.SODIUM).
		if (visible && ((com.origin.client.client.render.SodiumIrisBuilds.SODIUM
				&& com.origin.client.client.render.OffMapShadows.skipEntity(entity, renderer, camX, camY, camZ))
				|| ShadowCasterLimit.skipEntity(entity, renderer, camX, camY, camZ))) {
			return false;
		}
		return visible;
	}
}
