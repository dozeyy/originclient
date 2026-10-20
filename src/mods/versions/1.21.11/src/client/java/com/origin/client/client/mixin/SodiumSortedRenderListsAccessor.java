package com.origin.client.client.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: Sodium 0.8.12+mc1.21.11 SortedRenderLists.lists, private final ObjectArrayList<ChunkRenderList>
// (javap 2026-10-20: the per-region lists one walk produced, in draw order). Why: dev-shots only
// (ORIGIN_SHOTS) -- Off-Map Shadows counts which sections a shadow walk put in the shadow lists
// (render/OffMapShadows.afterWalk). Missing -> those dev counts read zero; players are unaffected.
@Mixin(value = SortedRenderLists.class, remap = false)
public interface SodiumSortedRenderListsAccessor {
	@Accessor("lists")
	ObjectArrayList<ChunkRenderList> originclient$lists();
}
