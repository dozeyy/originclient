package com.origin.client.client.mixin;

import com.origin.client.client.render.OffMapShadows;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Target: Sodium 0.4.10 me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager
 * .addChunkToVisible(Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;)V at HEAD (javap
 * 2026-10-20: private, called from addVisible for every non-empty section the walk keeps). Only the
 * RenderSection argument is taken (@Local argsOnly), so a changed argument list fails only this mixin.
 * Why: Off-Map Shadows' dev measurement (render/OffMapShadows.devSection): how many drawn shadow sections
 * lie off the shadow map. It changes nothing; OffMapShadows ignores the call outside dev shots.
 * What breaks if it stops applying: only the dev-shots stat line reads 0.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public abstract class SodiumShadowWalkDevMixin {
	@Inject(method = "addChunkToVisible", at = @At("HEAD"), remap = false)
	private void originclient$devSection(CallbackInfo ci,
			@com.llamalad7.mixinextras.sugar.Local(argsOnly = true) RenderSection section) {
		OffMapShadows.devSection(section.getOriginX(), section.getOriginY(), section.getOriginZ());
	}
}
