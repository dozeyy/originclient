package com.origin.flywheelvulkan.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.origin.flywheelvulkan.FlywheelVulkanClient;

/**
 * Applies Origin's VulkanMod/Flywheel mixins only when the version gate is open. On an unknown
 * VulkanMod or Flywheel the mixins never touch their classes (a field renamed in another build
 * would otherwise fail at apply time and stop the game booting).
 */
public final class FlywheelVulkanMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		var g = FlywheelVulkanClient.gate();
		// VulkanMod-only hooks need just the VulkanMod pin (the spike runs without Flywheel);
		// anything reaching into Flywheel needs both pins.
		if (targetClassName.startsWith("net.vulkanmod.")) return g.vulkanmodPinned();
		return g.verdict() == com.origin.flywheelvulkan.Gate.Verdict.ON;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
