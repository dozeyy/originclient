package com.origin.client.client.mixin;

import com.origin.client.client.render.ShadowCasterLimit;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SETTINGS > Performance > Limit Far Shadows, entity half (render/ShadowCasterLimit).
// Target: EntityRenderDispatcher.shouldRender(Entity, Frustum, DDD)Z at RETURN (javap,
//   1.21.1 Mojmap; generic <E extends Entity>, erased to Entity).
// Why: Iris 1.8.8 ShadowRenderer.renderEntities / renderPlayerEntity ask exactly this
//   (bytecode offsets 74 and 39) for every loaded entity during the shadow pass, then rebuild
//   the model of each one that passes. Answering false there skips that rebuild. Outside the
//   shadow pass ShadowCasterLimit always answers "keep", so the normal view is untouched.
// What breaks if it stops applying: nothing visible -- far mobs simply keep casting shadows
//   and the saving is gone (the F3 line stays at 0 skipped).
@Mixin(EntityRenderDispatcher.class)
public abstract class ShadowCasterEntityMixin {
	@Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
	private void originclient$farShadowCaster(Entity entity, Frustum frustum, double camX, double camY, double camZ,
			CallbackInfoReturnable<Boolean> cir) {
		// Off-Map Shadows first (render/OffMapShadows): an entity entirely off the shadow map's x/y edges.
		// OffMapShadows links Sodium types: only with a Sodium build its hooks were proven on (SodiumIrisBuilds.SODIUM).
		if (cir.getReturnValueZ() && ((com.origin.client.client.render.SodiumIrisBuilds.SODIUM
				&& com.origin.client.client.render.OffMapShadows.skipEntity(entity, camX, camY, camZ))
				|| ShadowCasterLimit.skipEntity(entity, camX, camY, camZ))) {
			cir.setReturnValue(false);
		}
	}
}
