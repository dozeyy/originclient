package com.origin.client.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.origin.client.client.mods.HypixelBedwars;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.resources.model.Material;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Hypixel Bed Wars > Colored Beds: draws each bed in its team's colour.
//
// BedRenderer.render(BedBlockEntity, float, PoseStack, MultiBufferSource,
// int light, int overlay) (verified against decompiled 1.19.4 client source)
// opens with `Material material = Sheets.BED_TEXTURES[bed.getColor().getId()];`
// -- its only Material local -- and draws both halves with it. Swapping that
// one local at its STORE swaps the bed's texture and nothing else: the block,
// its drops and what the server thinks are untouched. The full descriptor keeps
// the injector off the synthetic render(BlockEntity, ...) bridge.
@Mixin(BedRenderer.class)
public class HypixelBedwarsBedMixin {
	@ModifyVariable(
			method = "render(Lnet/minecraft/world/level/block/entity/BedBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
			at = @At("STORE"), ordinal = 0)
	private Material originclient$bwTeamBed(Material material, BedBlockEntity bed, float partialTick,
			PoseStack poseStack, MultiBufferSource buffers, int light, int overlay) {
		try {
			DyeColor team = HypixelBedwars.bedColor(bed);
			return team != null ? Sheets.BED_TEXTURES[team.getId()] : material;
		} catch (Throwable t) {
			return material;
		}
	}
}
