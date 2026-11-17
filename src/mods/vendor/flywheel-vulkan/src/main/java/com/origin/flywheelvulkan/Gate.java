package com.origin.flywheelvulkan;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Decides whether the Vulkan backend may exist at all this session. Pure logic over version
 * strings so it is unit-tested; {@link FlywheelVulkanClient} feeds it from Fabric Loader.
 *
 * The rule (DECISIONS 2026-10-26 + 2026-11-16): Origin's hooks into Flywheel and VulkanMod
 * check the version; on one they don't know, the feature switches off and nothing crashes.
 * Both pins are exact: the Vulkan backend reaches into Flywheel's internals (EngineImpl,
 * DrawManager, InstancerKey) and VulkanMod's private render state, which change between builds.
 */
public final class Gate {
	public enum Verdict {
		ON("on"),
		NO_VULKANMOD("VulkanMod not installed"),
		NO_FLYWHEEL("Flywheel not installed"),
		UNKNOWN_VULKANMOD("VulkanMod version not the one this backend was built for"),
		UNKNOWN_FLYWHEEL("Flywheel version not the one this backend was built for");

		public final String reason;

		Verdict(String reason) {
			this.reason = reason;
		}
	}

	private Gate() {
	}

	/** @param vulkanmod installed VulkanMod version string, or null when absent; same for flywheel. */
	public static Verdict decide(String vulkanmod, String flywheel, String pinnedVulkanmod, String pinnedFlywheel) {
		if (vulkanmod == null) return Verdict.NO_VULKANMOD;
		if (flywheel == null) return Verdict.NO_FLYWHEEL;
		if (!vulkanmod.equals(pinnedVulkanmod)) return Verdict.UNKNOWN_VULKANMOD;
		if (!flywheel.equals(pinnedFlywheel)) return Verdict.UNKNOWN_FLYWHEEL;
		return Verdict.ON;
	}

	/** The pins baked in at build time from gradle.properties (processResources expand). */
	public record Pins(String vulkanmod, String flywheel) {
		public static Pins load() {
			Properties p = new Properties();
			try (InputStream in = Gate.class.getResourceAsStream("flywheel_vulkan_pins.properties")) {
				if (in != null) p.load(in);
			} catch (IOException ignored) {
				// Missing pins = gate stays closed (both null never equal a real version).
			}
			return new Pins(p.getProperty("vulkanmod"), p.getProperty("flywheel"));
		}
	}
}
