package com.origin.client.client.compat;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Mixin plugin of originclient.client.mixins.json: a mixin into another mod (Sodium, Iris, JEI) applies
 * only while CompatGates says its group's mod versions are ones it was proven against. Asked before
 * Mixin looks the target class up, so a skipped mixin logs nothing of its own; CompatGates logs one
 * line per switched-off feature instead. Kept out of the mixin package (a non-mixin class there can't
 * be loaded) and free of game classes (it runs before any of them load).
 */
public final class CompatMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
		CompatGates.init();
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return CompatGates.mixinAllowed(mixinClassName);
	}

	@Override public String getRefMapperConfig() { return null; }
	@Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
	@Override public List<String> getMixins() { return null; }
	@Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
	@Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
}
