package com.origin.client.client.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SectionCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;

// Target: Sodium 0.8.12+mc1.21.11 RenderSectionManager's walk state (javap 2026-10-20): taskLists
// (Map<TaskQueueType, ArrayDeque<RenderSection>>, set from the walk's collector), lastUpdatedFrame (int,
// bumped by update() before each walk), sectionCollector (the walk's SectionCollector until
// finalizeRenderLists) and lastSectionCollector (the one mid-frame immediate build results go to between
// walks). Why: Smart Terrain Updates only reuses a main walk when the rebuild queues are empty, and on a
// reused frame re-stamps sections with lastUpdatedFrame and points lastSectionCollector back at the main
// walk's collector, as a frame without a walk would. PER-VERSION (1.21.11): Sodium 0.6 had
// ChunkUpdateType task lists and no collectors; renderLists / needsGraphUpdate are no longer needed here.
// Missing -> the feature never engages (it is version-gated to the build these names were read from).
@Mixin(value = RenderSectionManager.class, remap = false)
public interface SodiumRenderSectionManagerAccessor {
	@Accessor("taskLists")
	Map<TaskQueueType, ArrayDeque<RenderSection>> originclient$taskLists();

	@Accessor("lastUpdatedFrame")
	int originclient$lastUpdatedFrame();

	@Accessor("sectionCollector")
	SectionCollector originclient$sectionCollector();

	@Accessor("lastSectionCollector")
	void originclient$setLastSectionCollector(SectionCollector collector);
}
