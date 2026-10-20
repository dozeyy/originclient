package com.origin.client.client.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: Sodium 0.9.2+mc26.3 SortedRenderLists.lists (private final ObjectArrayList<ChunkRenderList>,
//   javap 2026-10-20): the per-region lists one walk produced, in draw order.
// Why: dev-shots only -- OffMapShadows.afterWalk counts the sections a shadow walk put in the lists.
// What breaks if it stops applying: the dev count throws, is caught, and Off-Map Shadows latches off in
//   dev runs only (players never call it: the DEV flag is false for them).
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists", remap = false)
public interface SodiumSortedRenderListsAccessor {
	@Accessor(value = "lists", remap = false)
	ObjectArrayList<ChunkRenderList> originclient$lists();
}