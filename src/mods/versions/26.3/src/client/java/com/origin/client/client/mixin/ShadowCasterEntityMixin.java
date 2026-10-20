package com.origin.client.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.origin.client.client.render.OffMapShadows;
import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// SETTINGS > Performance > Limit Far Shadows + Off-Map Shadows, entity half.
// Target: EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD, F)Z at RETURN (javap 26.3: the partial tick is new;
//   getRenderer at offset 2 into local 10, the only EntityRenderer local; generic <E extends Entity>, erased).
// Why: Iris 1.11.7 ShadowRenderer.renderShadows asks exactly this for every loaded entity during the
//   shadow pass, then extracts + submits each one that passes. Answering false there skips that work.
//   Outside the shadow pass both helpers always answer "keep", so the normal view is untouched.
//   The arguments are taken with @Local(argsOnly = true), not a declared argument list, so a changed
//   parameter list fails this one handler instead of the mixin (brief rule).
// What breaks if it stops applying: nothing visible -- far / off-map mobs keep casting shadows and the
//   saving is gone (the F3 lines stay at 0 skipped). Coexists with EntityRenderDispatcherMixin's
//   cancellable RETURN inject on the same method.
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCasterEntityMixin {
	// ModifyReturnValue, not a cancellable Inject: this runs for every entity on every pass, and a
	// cancellable RETURN Inject allocates a CallbackInfoReturnable each call; this allocates nothing.
	@ModifyReturnValue(method = "shouldRender", at = @At("RETURN"))
	private boolean originclient$farShadowCaster(boolean visible, @Local(argsOnly = true) Entity entity,
			@Local(argsOnly = true, ordinal = 0) double camX, @Local(argsOnly = true, ordinal = 1) double camY,
			@Local(argsOnly = true, ordinal = 2) double camZ, @Local(argsOnly = true) float partialTick,
			@Local EntityRenderer<?, ?> renderer) {
		if (visible && (OffMapShadows.skipEntity(entity, renderer, camX, camY, camZ, partialTick)
				|| ShadowCasterLimit.skipEntity(entity, renderer, camX, camY, camZ, partialTick))) {
			return false;
		}
		return visible;
	}
}