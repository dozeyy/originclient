package com.origin.client.client.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.ArrayDeque;
import java.util.Map;

// Target: Sodium 0.5.8 RenderSectionManager's main-pass walk output (renderLists, rebuildLists,
// lastUpdatedFrame, needsUpdate -- javap of the jar 2026-10-20; 0.6.13 called the last two
// taskLists / needsGraphUpdate). Why: Smart Terrain Updates snapshots the main walk's region
// lists, only reuses them when the rebuild queues are empty, and on a reused frame does what
// update() itself does after the walk (needsUpdate = false, lastUpdatedFrame = frame). Missing ->
// the feature never engages (it is version-gated to the build these names were read from).
@Mixin(value = RenderSectionManager.class, remap = false)
public interface SodiumRenderSectionManagerAccessor {
	@Accessor("renderLists")
	SortedRenderLists originclient$renderLists();

	@Accessor("rebuildLists")
	Map<ChunkUpdateType, ArrayDeque<RenderSection>> originclient$rebuildLists();

	@Accessor("lastUpdatedFrame")
	int originclient$lastUpdatedFrame();

	@Accessor("lastUpdatedFrame")
	void originclient$setLastUpdatedFrame(int frame);

	@Accessor("needsUpdate")
	void originclient$setNeedsUpdate(boolean needs);
}
