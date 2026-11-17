package com.vulkairis.compatibility;

import com.vulkairis.vulkan.VulkanCapabilities;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates system environment, GPU capabilities, and installed mods
 * to determine overall bridge readiness.
 */
public class CompatibilityManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Compatibility");

    public record EnvironmentReport(
            boolean vulkanModPresent,
            boolean irisPresent,
            boolean sodiumPresent,
            boolean gpuCompatible,
            String gpuName,
            String details
    ) {}

    public static EnvironmentReport evaluateEnvironment() {
        FabricLoader loader = FabricLoader.getInstance();
        boolean vulkanMod = loader.isModLoaded("vulkanmod");
        boolean iris = loader.isModLoaded("iris");
        boolean sodium = loader.isModLoaded("sodium");

        VulkanCapabilities caps = VulkanCapabilities.getActiveCapabilities();
        boolean gpuOk = caps.isCompatible();

        String details = String.format("GPU: %s (%s), VulkanMod: %b, Iris: %b, Sodium: %b",
                caps.getDeviceName(), caps.getVendorName(), vulkanMod, iris, sodium);

        LOGGER.info("[Vulkairis] Environment Report: {}", details);
        return new EnvironmentReport(vulkanMod, iris, sodium, gpuOk, caps.getDeviceName(), details);
    }
}
