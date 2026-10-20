package com.origin.client.client;

import com.origin.client.client.render.SodiumIrisBuilds;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin plugin of originclient.client.mixins.json. Its one job: skip a mixin whose targets exist in only
 * one of the two Sodium generations this jar meets, on the other. Kept out of the mixin package (a
 * non-mixin class there can't be loaded); reads nothing but Fabric Loader's mod metadata.
 *
 * <p>PER-VERSION (26.1): one jar serves 26.1 / 26.1.1 (Sodium 0.8.9 + Iris 1.10.9) and 26.1.2 (Sodium
 * 0.9.2 + Iris 1.11.4), javap 2026-10-20. Sodium 0.9 rewrote RenderSectionManager (async culling):
 * createTerrainRenderList, update, finalizeRenderLists(Viewport), scheduleSort (still hooked on 0.8 by
 * SodiumGraphReuseMixin), the SectionCollector walk
 * state and OcclusionCuller's frame stamps exist only in 0.8 and are gone in 0.9, and renderOutOfGraph / readRenderListFromTree /
 * scheduleAsyncWork are new. Applied to the wrong generation those mixins would print an apply ERROR on
 * every boot (or, for an accessor, fail outright), so each set runs only on its own generation. Every
 * other Sodium/Iris mixin in this config fits both builds (no declared argument lists where they differ)
 * and is always applied.
 */
public final class OriginMixinPlugin implements IMixinConfigPlugin {
	private static final String PKG = "com.origin.client.client.mixin.";

	/** Smart Terrain Updates' argument-capturing setupTerrain hook: only the exact builds it was read from. */
	private static final Set<String> SMART_TERRAIN_ONLY = Set.of(PKG + "SodiumWorldRendererReuseMixin");

	/** Sodium 0.8 walk hooks (Smart Terrain Updates' graph cache and stamps, Off-Map Shadows' 0.8 walk). */
	private static final Set<String> SODIUM_08_ONLY = Set.of(PKG + "SodiumGraphReuseMixin",
			PKG + "SodiumOcclusionStampMixin", PKG + "SodiumRenderSectionManagerAccessor", PKG + "SodiumWalkEndMixin");

	/** Sodium 0.9 walk hooks (Off-Map Shadows' renderOutOfGraph walk, ShadowTerrainStats). */
	private static final Set<String> SODIUM_09_ONLY = Set.of(PKG + "SodiumRenderSectionManagerHooksMixin");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (SMART_TERRAIN_ONLY.contains(mixinClassName)) {
			return SodiumIrisBuilds.SMART_TERRAIN;
		}
		if (SODIUM_08_ONLY.contains(mixinClassName)) {
			return SodiumIrisBuilds.SODIUM_08;
		}
		if (SODIUM_09_ONLY.contains(mixinClassName)) {
			return SodiumIrisBuilds.SODIUM_09;
		}
		return true;
	}

	@Override public void onLoad(String mixinPackage) { }
	@Override public String getRefMapperConfig() { return null; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
