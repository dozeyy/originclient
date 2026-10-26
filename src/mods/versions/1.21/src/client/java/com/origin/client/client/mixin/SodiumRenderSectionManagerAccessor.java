package com.origin.client.client.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;

// Target: Sodium 0.6.13 RenderSectionManager's main-pass walk output (renderLists, taskLists,
// lastUpdatedFrame, needsGraphUpdate -- javap of the jar 2026-10-25; 0.5.11 called the second and
// last rebuildLists / needsUpdate). Why: Smart Terrain Updates snapshots the main walk's region
// lists, only reuses them when the rebuild/sort queues are empty, and on a reused frame does what
// update() itself does around the walk (++lastUpdatedFrame, needsGraphUpdate = false). Missing ->
// the feature never engages (it is version-gated to the build these names were read from).
@Mixin(value = RenderSectionManager.class, remap = false)
public interface SodiumRenderSectionManagerAccessor {
	@Accessor("renderLists")
	SortedRenderLists originclient$renderLists();

	@Accessor("taskLists")
	Map<ChunkUpdateType, ArrayDeque<RenderSection>> originclient$taskLists();

	@Accessor("lastUpdatedFrame")
	int originclient$lastUpdatedFrame();

	@Accessor("lastUpdatedFrame")
	void originclient$setLastUpdatedFrame(int frame);

	@Accessor("needsGraphUpdate")
	void originclient$setNeedsGraphUpdate(boolean needs);
}
