package com.vulkairis.compatibility;

import com.vulkairis.vulkan.VulkanCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Maps GPU families to known compatibility status and manages fallback logic.
 */
public final class CompatibilityMatrix {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/CompatibilityMatrix");

    public enum Stability {
        STABLE,
        EXPERIMENTAL,
        BROKEN,
        UNKNOWN
    }

    private static final Map<Integer, Stability> VENDOR_STABILITY = Map.of(
        0x10DE, Stability.STABLE,    // NVIDIA
        0x1002, Stability.STABLE,    // AMD
        0x8086, Stability.EXPERIMENTAL // Intel
    );

    public static Stability getStability(int vendorId) {
        return VENDOR_STABILITY.getOrDefault(vendorId, Stability.UNKNOWN);
    }

    public static boolean shouldFallback() {
        VulkanCapabilities caps = VulkanCapabilities.getActiveCapabilities();
        Stability stability = getStability(caps.getVendorId());

        if (stability == Stability.BROKEN) {
            LOGGER.warn("[Vulkairis] GPU vendor 0x{} marked BROKEN. Falling back to vanilla rendering.", Integer.toHexString(caps.getVendorId()));
            return true;
        }
        return false;
    }
}
