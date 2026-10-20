package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

// SETTINGS > Performance > Limit Far Shadows + Off-Map Shadows, entity half
//   (render/ShadowCasterLimit, render/OffMapShadows).
// Target: EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z at RETURN (javap of the MC 1.21
//   Mojmap jar 2026-10-20; generic <E extends Entity>, erased to Entity).
// Why: Iris 1.7.3 ShadowRenderer.renderEntities / renderPlayerEntity ask exactly this (bytecode
//   offsets 74 and 39) for every loaded entity during the shadow pass, then rebuild the model of
//   each one that passes. Answering false there skips that rebuild. Outside the shadow pass both
//   helpers always answer "keep", so the normal view is untouched. @ModifyReturnValue (not a
//   cancellable CIR): no CallbackInfoReturnable is allocated per entity per frame.
// What breaks if it stops applying: nothing visible -- far / off-map mobs simply keep casting
//   shadows and the saving is gone (the F3 lines stay at 0 skipped).
// PER-VERSION (1.21): Sodium is a separate install and OffMapShadows' class names Sodium types, so
//   any linkage error from this VANILLA hook (every entity, every frame) latches it off instead of
//   crashing (mandate 4); with everything present the try costs nothing.
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCasterEntityMixin {
	@Unique
	private static boolean originclient$dead;

	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean originclient$farShadowCaster(boolean visible, Entity entity, Frustum frustum,
			double camX, double camY, double camZ) {
		if (!visible || originclient$dead) {
			return visible;
		}
		try {
			// Off-Map Shadows first: an entity entirely off the shadow map's x/y edges.
			if (OffMapShadows.skipEntity(entity, camX, camY, camZ)
					|| ShadowCasterLimit.skipEntity(entity, camX, camY, camZ)) {
				return false;
			}
		} catch (Throwable t) {
			originclient$dead = true;   // shadows of every entity simply draw as before
		}
		return visible;
	}
}
