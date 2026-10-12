package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Hypixel Bed Wars > Colored Beds: draws each bed in its team's colour.
//
// 1.21.8 BedRenderer.render(BedBlockEntity, float, PoseStack, MultiBufferSource,
// int light, int overlay, Vec3 cameraPos) (verified against the decompiled 1.21.6
// and 1.21.8 client source; 1.21.5 added the camera position)
// opens with `Material material = Sheets.getBedMaterial(bed.getColor());`
// -- its only Material local -- and draws both halves with it. (1.21.2+ made
// Sheets.BED_TEXTURES private; getBedMaterial(DyeColor) is the public door.) Swapping that
// one local at its STORE swaps the bed's texture and nothing else: the block,
// its drops and what the server thinks are untouched. The full descriptor keeps
// the injector off the synthetic render(BlockEntity, ...) bridge.
@Mixin(BedRenderer.class)
public class HypixelBedwarsBedMixin {
	@ModifyVariable(
			method = "render(Lnet/minecraft/world/level/block/entity/BedBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/world/phys/Vec3;)V",
			at = @At("STORE"), ordinal = 0)
	private Material originclient$bwTeamBed(Material material, BedBlockEntity bed, float partialTick,
			PoseStack poseStack, MultiBufferSource buffers, int light, int overlay, Vec3 cameraPos) {
		try {
			DyeColor team = HypixelBedwars.bedColor(bed);
			return team != null ? Sheets.getBedMaterial(team) : material;
		} catch (Throwable t) {
			return material;
		}
	}
}
