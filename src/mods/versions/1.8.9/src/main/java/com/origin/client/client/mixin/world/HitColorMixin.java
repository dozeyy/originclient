package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.HitColor;
import com.origin.client.client.mods.OldAnimations;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Hit Color: RendererLivingEntity.setBrightness(T, partialTicks, combineTextures)
// sets up the hurt flash as a constant-colour texture environment and returns
// true when it did. At RETURN (robust in OptiFine's replacement of this class)
// HitColor re-sends that constant with the player's colour + strength. T erases
// to EntityLivingBase.
@Mixin(RendererLivingEntity.class)
public abstract class HitColorMixin {
	@Inject(method = "setBrightness(Lnet/minecraft/entity/EntityLivingBase;FZ)Z", at = @At("RETURN"))
	private void originclient$hitColor(EntityLivingBase entity, float partialTicks, boolean combineTextures,
									   CallbackInfoReturnable<Boolean> cir) {
		try {
			if (cir.getReturnValueZ()) {
				HitColor.applyFlash(entity);
			}
		} catch (Throwable ignored) {
			// the flash stays vanilla red
		}
		try {
			// 1.7 Animations > Red Armor Flash: when this is the armor layer's flash that
			// ArmorGlintMixin turned on for it, 1.7's 40% red replaces vanilla's 30%.
			OldAnimations.afterFlash(entity, cir.getReturnValueZ());
		} catch (Throwable t) {
			OldAnimations.fail("red armor flash", t);
		}
	}
}
