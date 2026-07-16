package com.origin.client.client.mixin;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Shows your OWN nametag in third person.
 *
 * EntityNametagMixin already does this, but on EntityRenderer.shouldShowName --
 * and LivingEntityRenderer OVERRIDES that method. The player is a LivingEntity,
 * so the override is what actually runs and the base-class injection never fired
 * for the one entity the feature exists for: yourself. Vanilla's override returns
 * false for the camera entity, and nothing re-enabled it.
 *
 * Targeting the override is the fix. Kept as its own mixin rather than folded
 * into EntityNametagMixin because the two classes need different @Mixin targets
 * and parameter types; EntityNametagMixin still covers non-living entities.
 */
@Mixin(LivingEntityRenderer.class)
public class LivingNametagMixin {

	@Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z",
			at = @At("RETURN"), cancellable = true)
	private void originclient$ownNametag(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
		// Only ever ADD a nametag vanilla was hiding -- never take one away, so
		// this can't fight the hide toggles in EntityNametagMixin.
		if (cir.getReturnValueZ() || !Mods.on("nametags") || !Mods.bool("nametags", "thirdPerson")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player && !mc.options.getCameraType().isFirstPerson()) {
			cir.setReturnValue(true);
		}
	}
}
