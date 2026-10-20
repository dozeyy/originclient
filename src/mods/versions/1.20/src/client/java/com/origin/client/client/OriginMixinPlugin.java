package com.origin.client.client;

import com.origin.client.client.render.SodiumIrisBuilds;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin plugin of originclient.client.mixins.json. Its one job: apply each shader speed-up mixin only on
 * the Sodium / Iris build it was written against. This jar runs on MC 1.20 (Sodium 0.4.10 + Iris 1.6.4)
 * and MC 1.20.1 (Sodium 0.5.13 + Iris 1.7.6), and the two share class names with different members
 * (Sodium's RenderSectionManager exists in both with unrelated methods and fields), so an @Accessor or
 * @Shadow written for one fails at apply time on the other. Kept out of the mixin package (a non-mixin
 * class there can't be loaded); reads nothing but Fabric Loader's mod metadata (SodiumIrisBuilds).
 *
 * <p>Any mixin not listed here (vanilla targets, the older Iris/Sodium compat mixins) is always applied.
 * The plugin is asked before Mixin looks the target class up, so a skipped mixin whose target is absent
 * (Viewport / OcclusionCuller on Sodium 0.4.10, net.irisshaders on Iris 1.6.4) logs nothing.
 */
public final class OriginMixinPlugin implements IMixinConfigPlugin {
	private static final String PKG = "com.origin.client.client.mixin.";

	/** Sodium 0.5.13 only: the 0.5 chunk graph (Smart Terrain Updates, Off-Map chunk half, Half-Rate chunk hooks). */
	private static final Set<String> SODIUM_05 = Set.of(
			"SodiumRenderSectionManagerAccessor", "SodiumSortedRenderListsAccessor", "SodiumChunkRenderListAccessor",
			"SodiumGraphReuseMixin", "SodiumUpdateReuseMixin", "SodiumWalkEndMixin", "SodiumOcclusionStampMixin",
			"SodiumViewportShadowMapMixin", "SodiumWorldRendererReuseMixin");
	/** Sodium 0.4.10 only: Half-Rate Shadows' chunk-change hooks on the old RenderSectionManager. */
	private static final Set<String> SODIUM_04 = Set.of("Sodium04HalfRateMixin");
	/** Iris 1.7.6 only (net.irisshaders). */
	private static final Set<String> IRIS_17 = Set.of("IrisPipelineHalfRateMixin", "IrisShadowRendererHalfRateMixin");
	/** Iris 1.6.4 only (net.coderbot). */
	private static final Set<String> IRIS_16 = Set.of("IrisPipelineHalfRate16Mixin", "IrisShadowRendererHalfRate16Mixin",
			"IrisShadowProjectionMixin");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (!mixinClassName.startsWith(PKG)) {
			return true;
		}
		String name = mixinClassName.substring(PKG.length());
		if (SODIUM_05.contains(name)) {
			return SodiumIrisBuilds.SODIUM_05;
		}
		if (SODIUM_04.contains(name)) {
			return SodiumIrisBuilds.SODIUM_04;
		}
		if (IRIS_17.contains(name)) {
			return SodiumIrisBuilds.IRIS_17;
		}
		if (IRIS_16.contains(name)) {
			return SodiumIrisBuilds.IRIS_16;
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
