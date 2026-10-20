package com.origin.client.client.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import me.jellysquid.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: Sodium 0.5.8 SortedRenderLists.lists (the per-region lists one walk produced, in draw
// order). Why: Smart Terrain Updates copies those lists' contents after a real main walk.
// Missing -> the feature never engages (version-gated).
@Mixin(value = SortedRenderLists.class, remap = false)
public interface SodiumSortedRenderListsAccessor {
	@Accessor("lists")
	ObjectArrayList<ChunkRenderList> originclient$lists();
}
