package com.origin.flywheelvulkan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Entry point. Reads the version gate once; only when it is open does anything touch a Flywheel
 * or VulkanMod class (so a player without either, or with other versions, loads nothing of them).
 */
public final class FlywheelVulkanClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("origin-flywheel-vulkan");

	public record GateState(Gate.Verdict verdict, String vulkanmod, String flywheel, boolean vulkanmodPinned) {
	}

	private static GateState gate;

	/** Safe from the mixin plugin (runs before any entrypoint): only reads loader metadata. */
	public static synchronized GateState gate() {
		if (gate == null) {
			String vk = version("vulkanmod");
			String fw = version("flywheel");
			Gate.Pins pins = Gate.Pins.load();
			gate = new GateState(Gate.decide(vk, fw, pins.vulkanmod(), pins.flywheel()), vk, fw,
					vk != null && vk.equals(pins.vulkanmod()));
		}
		return gate;
	}

	private static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(ModContainer::getMetadata)
				.map(m -> m.getVersion().getFriendlyString())
				.orElse(null);
	}

	@Override
	public void onInitializeClient() {
		GateState g = gate();
		if (g.vulkanmodPinned() && com.origin.flywheelvulkan.spike.InstancedCubeSpike.ENABLED) {
			SpikeHook.register();
		}
		if (g.verdict() != Gate.Verdict.ON) {
			LOGGER.info("Vulkan Flywheel backend off: {} (vulkanmod={}, flywheel={})", g.verdict().reason, g.vulkanmod(), g.flywheel());
			return;
		}
		// Class-loads Flywheel/VulkanMod types only past the gate.
		Hooks.init();
	}
}
