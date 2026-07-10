package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// SETTINGS > Performance > Entity Distance. A percentage of your render
// distance: 100% culls nothing extra (vanilla behavior); lower values stop
// drawing entities past that fraction of the render radius. Runs only after
// vanilla already decided the entity IS renderable, so it can only remove.
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true)
	private void originclient$entityCull(Entity entity, Frustum frustum, double camX, double camY, double camZ,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			return;
		}
		double pct = Mods.num(Mods.PERFORMANCE_ID, "entityDistance");
		if (pct >= 100) {
			return;
		}
		double max = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0 * (pct / 100.0);
		double dx = entity.getX() - camX;
		double dy = entity.getY() - camY;
		double dz = entity.getZ() - camZ;
		if (dx * dx + dy * dy + dz * dz > max * max) {
			cir.setReturnValue(false);
		}
	}
}
