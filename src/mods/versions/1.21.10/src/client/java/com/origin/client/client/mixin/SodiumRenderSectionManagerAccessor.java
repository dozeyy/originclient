package com.origin.client.client.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * Target: Sodium 0.7.3+mc1.21.10 RenderSectionManager fields (javap 2026-10-20):
 * {@code Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists} (0.6's ChunkUpdateType key is gone) and
 * {@code SectionCollector sectionCollector} (0.7: the walk fills a collector; finalizeRenderLists turns it
 * into the render lists and clears the field).
 * Why: Smart Terrain Updates checks that no rebuild/sort work waits in the main task lists before it
 * replays a walk; Off-Map Shadows' dev count reads the region lists a shadow walk's collector filled.
 * What breaks if it stops applying: the first call throws, which each feature catches and latches
 * itself off for the session (logged once); nothing else reads these.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface SodiumRenderSectionManagerAccessor {
	@Accessor("taskLists")
	Map<TaskQueueType, ArrayDeque<RenderSection>> originclient$taskLists();

	@Accessor("sectionCollector")
	SectionCollector originclient$sectionCollector();
}
