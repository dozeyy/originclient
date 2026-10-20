package com.origin.client.client;

import com.origin.client.client.render.SodiumIrisBuilds;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin plugin of originclient.client.mixins.json. Its one job: skip a mixin whose handlers fit only
 * one of the Sodium builds this module runs with, on the others. Kept out of the mixin package (a
 * non-mixin class there can't be loaded); reads nothing but Fabric Loader's mod metadata.
 *
 * <p>SodiumWorldRendererReuseMixin (Smart Terrain Updates) takes the FogParameters argument that only
 * Sodium 0.6.13+mc1.21.4 passes: javap 2026-10-20 reads setupTerrain(Camera, Viewport, FogParameters,
 * boolean, boolean) there and setupTerrain(Camera, Viewport, boolean, boolean) in 0.6.1 and 0.6.8
 * (1.21.2 / 1.21.3). Applied there it would fail at apply time with an ERROR line on every boot, so it
 * is only applied where the feature runs (SodiumIrisBuilds.SMART_TERRAIN). Every other Sodium/Iris
 * mixin in this config fits all three builds and is always applied.
 */
public final class OriginMixinPlugin implements IMixinConfigPlugin {
	private static final String SMART_TERRAIN_MIXIN = "com.origin.client.client.mixin.SodiumWorldRendererReuseMixin";

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (SMART_TERRAIN_MIXIN.equals(mixinClassName)) {
			return SodiumIrisBuilds.SMART_TERRAIN;
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
