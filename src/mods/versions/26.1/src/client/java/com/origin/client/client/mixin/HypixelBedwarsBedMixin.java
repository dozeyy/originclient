package com.origin.client.client.mixin;

import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.renderer.blockentity.state.BedRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Hypixel Bed Wars > Colored Beds: draws each bed in its team's colour.
//
// 1.21.11 and 26.1.x (javap 26.1.2 -- same signature): block entities
// are extract-then-submit. BedRenderer.extractRenderState(BedBlockEntity,
// BedRenderState, float, Vec3, CrumblingOverlay) copies the bed's colour into
// BedRenderState.color, and submit() picks the texture with
// Sheets.getBedMaterial(state.color) -- so overriding that one field at the end
// of extraction recolours the bed's texture and nothing else: the block, its
// drops and what the server thinks are untouched. (1.21.1 swapped the Material
// local inside render(); that method no longer exists.) The full descriptor keeps
// the injector off the synthetic extractRenderState(BlockEntity, ...) bridge.
@Mixin(BedRenderer.class)
public class HypixelBedwarsBedMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/BedBlockEntity;Lnet/minecraft/client/renderer/blockentity/state/BedRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			at = @At("TAIL"), require = 0)
	private void originclient$bwTeamBed(BedBlockEntity bed, BedRenderState state, float partialTick, Vec3 camera,
			ModelFeatureRenderer.CrumblingOverlay crumbling, CallbackInfo ci) {
		try {
			DyeColor team = HypixelBedwars.bedColor(bed);
			if (team != null) {
				state.color = team;
			}
		} catch (Throwable t) {
			// keep the bed's own colour
		}
	}
}
