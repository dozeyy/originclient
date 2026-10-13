package com.origin.client.client.mixin.world;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Third Person Nametag: shows your OWN name tag in F5.
 *
 * 1.8.9's RendererLivingEntity.canRenderName(T) refuses the render-view entity
 * (`entity != renderManager.livingPlayer`) -- that's you. While the Nametags mod's
 * "Third Person" row is on and the camera is in a third-person view, the answer
 * for the local player is flipped to true (still only when vanilla's other rules
 * would allow a tag: GUI shown, not invisible, not ridden). Only ever ADDS a tag
 * vanilla was hiding, so it can't fight the hide toggles. T erases to
 * EntityLivingBase (the bridge method takes Entity and is left alone).
 * RETURN: RendererLivingEntity is OptiFine-replaced.
 */
@Mixin(RendererLivingEntity.class)
public abstract class LivingNametagMixin {

	@Inject(method = "canRenderName(Lnet/minecraft/entity/EntityLivingBase;)Z", at = @At("RETURN"), cancellable = true)
	private void originclient$ownNametag(EntityLivingBase entity, CallbackInfoReturnable<Boolean> cir) {
		try {
			if (cir.getReturnValueZ() || !Mods.on("nametags") || !Mods.bool("nametags", "thirdPerson")) {
				return;
			}
			Minecraft mc = Minecraft.getMinecraft();
			if (entity == mc.thePlayer && mc.gameSettings.thirdPersonView != 0 && Minecraft.isGuiEnabled()
					&& !entity.isInvisible() && entity.riddenByEntity == null) {
				cir.setReturnValue(true);
			}
		} catch (Throwable ignored) {
			// vanilla's answer
		}
	}
}
