package com.origin.flywheelvulkan.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Target: VulkanMod 0.6.7 {@code net.vulkanmod.render.chunk.build.task.BuildTask}
 * {@code private <E extends BlockEntity> void handleBlockEntity(CompileResult, E)} (javap 2026-11-17):
 * VulkanMod's own section compiler, which replaces vanilla's {@code SectionCompiler} and copies its
 * {@code handleBlockEntity} (add the block entity to the section's renderer list).
 *
 * <p>Why: Flywheel learns about block entities that load WITH a chunk only through its hook on
 * vanilla {@code SectionCompiler.handleBlockEntity} (flywheel's visualmanage.SectionCompilerMixin):
 * the visual manager is rebuilt on every level-renderer reload and re-adds entities, but not block
 * entities. VulkanMod never calls vanilla's compiler, so on origin:vulkan every Create/Trainworks
 * machine saved in the world had no visual (and its block-entity renderer stood aside because the
 * level supports visualization): a reopened factory drew none of its moving parts. Only blocks
 * placed during play showed (they go through Flywheel's LevelChunk hook). This does exactly what
 * Flywheel's own hook does: hand the block entity to Flywheel, and keep it off the section's
 * renderer list when Flywheel took it.
 *
 * <p>If it stops applying: saved kinetic machines are invisible in Vulkan mode until a block next to
 * them changes. The plugin applies it only when both pins (VulkanMod and Flywheel) match.
 */
@Mixin(targets = "net.vulkanmod.render.chunk.build.task.BuildTask", remap = false)
abstract class VulkanBuildTaskBlockEntityMixin {
	@Inject(method = "handleBlockEntity", at = @At("HEAD"), cancellable = true, remap = false)
	private void origin$visualizeBlockEntity(@Coerce Object compileResult, BlockEntity blockEntity, CallbackInfo ci) {
		if (VisualizationHelper.tryAddBlockEntity(blockEntity)) {
			ci.cancel();
		}
	}
}
