package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.origin.flywheelvulkan.compat.BulkOnlyConsumers;

import net.vulkanmod.render.chunk.build.frapi.mesh.MutableQuadViewImpl;

/**
 * Target: VulkanMod 0.6.7 {@code net.vulkanmod.render.chunk.build.frapi.render.AbstractRenderContext}
 * {@code protected void bufferQuad(MutableQuadViewImpl, VertexConsumer)} (javap 2026-11-17). It is the
 * one place VulkanMod writes Fabric-renderer quads into a caller's VertexConsumer vertex by vertex:
 * BlockRenderContext (ModelBlockRendererM.hookRender, every non-vanilla model passed to
 * ModelBlockRenderer.tesselateBlock) and ItemRenderContext both end here.
 *
 * <p>Why: catnip's ShadedBlockSbbBuilder accepts only putBulkData, so every SuperByteBuffer bake of a
 * Fabric-renderer model (Create/Trainworks kinetic block-entity renderers, contraptions, schematics)
 * crashed the game in Vulkan mode. This swaps such a consumer for its unwrapped BufferBuilder per quad,
 * recording the quad's shade group the way putBulkData does (see {@link BulkOnlyConsumers}).
 *
 * <p>If it stops applying: the crash "ShadedBlockSbbBuilder only supports putBulkData!" returns as soon
 * as a Create or Trainworks kinetic block is on screen in Vulkan mode. The plugin applies it only with
 * the pinned VulkanMod build.
 */
@Mixin(targets = "net.vulkanmod.render.chunk.build.frapi.render.AbstractRenderContext", remap = false)
abstract class VulkanFrapiBulkOnlyMixin {
	@ModifyVariable(method = "bufferQuad", at = @At("HEAD"), argsOnly = true, remap = false)
	private VertexConsumer origin$perVertexConsumer(VertexConsumer consumer, MutableQuadViewImpl quad, VertexConsumer original) {
		return (VertexConsumer) BulkOnlyConsumers.forPerVertexWrites(consumer, quad.hasShade());
	}
}
