package com.vulkairis.test;

import com.vulkairis.vulkan.VulkanCapabilities;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class VulkanCapabilitiesTest {

    @Test
    public void testCapabilitiesDefaults() {
        VulkanCapabilities caps = new VulkanCapabilities();
        Assertions.assertNotNull(caps.getDeviceName());
        Assertions.assertNotNull(caps.getVendorName());
        Assertions.assertTrue(caps.getMaxColorAttachments() >= 1);
        Assertions.assertTrue(caps.getMaxImageDimension2D() > 0);
    }

    @Test
    public void testCompatibilityCheck() {
        VulkanCapabilities caps = new VulkanCapabilities();
        Assertions.assertTrue(caps.isCompatible(), "Capabilities should pass default compatibility check");
    }
}
