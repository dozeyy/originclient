package com.origin.client.client.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Target: Sodium 0.5.8 ChunkRenderList (one per render region, SHARED by the main walk and Iris's
// shadow walk). Why: Smart Terrain Updates snapshots the main walk's contents and writes them back
// before the shadow walk on a reused frame, so the main draw sees exactly what a fresh walk gives.
// Field names re-read from the 0.5.8 jar 2026-10-20. Missing -> the feature never engages (version-gated).
@Mixin(value = ChunkRenderList.class, remap = false)
public interface SodiumChunkRenderListAccessor {
	@Accessor("sectionsWithGeometry")
	byte[] originclient$geo();

	@Accessor("sectionsWithSprites")
	byte[] originclient$sprites();

	@Accessor("sectionsWithEntities")
	byte[] originclient$entities();

	@Accessor("sectionsWithGeometryCount")
	int originclient$geoCount();

	@Accessor("sectionsWithGeometryCount")
	void originclient$setGeoCount(int v);

	@Accessor("sectionsWithSpritesCount")
	int originclient$spriteCount();

	@Accessor("sectionsWithSpritesCount")
	void originclient$setSpriteCount(int v);

	@Accessor("sectionsWithEntitiesCount")
	int originclient$entityCount();

	@Accessor("sectionsWithEntitiesCount")
	void originclient$setEntityCount(int v);

	@Accessor("size")
	int originclient$size();

	@Accessor("size")
	void originclient$setSize(int v);

	@Accessor("lastVisibleFrame")
	void originclient$setLastVisibleFrame(int v);
}
