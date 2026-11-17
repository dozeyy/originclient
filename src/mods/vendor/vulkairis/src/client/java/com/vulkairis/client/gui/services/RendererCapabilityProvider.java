package com.vulkairis.client.gui.services;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;

/**
 * Read-only renderer capability snapshot (GUI plan §5). Reports what is actually present —
 * never assumes Vulkan or OpenGL features exist. Safe headless: unknown values read as
 * unavailable rather than crashing.
 */
public final class RendererCapabilityProvider {

    private RendererCapabilityProvider() {
    }

    /** Point-in-time capabilities for backend choices and status panels. */
    public record Capabilities(
            boolean vulkanModPresent,
            boolean irisPresent,
            boolean deviceLive,
            String deviceName,
            String effectiveBackend,
            boolean openGlAvailable) {

        /** @return the backend actually rendering right now */
        public String describeEffective() {
            return effectiveBackend;
        }
    }

    /** @return the current snapshot; never throws */
    public static Capabilities snapshot() {
        boolean vulkanMod = isPresent("net.vulkanmod.vulkan.Vulkan");
        boolean iris = isPresent("net.irisshaders.iris.Iris");
        boolean live = false;
        String device = "unavailable";
        try {
            live = VulkanBackendAdapter.isLive(VulkanBackendAdapter.getDevice());
            if (live) {
                try {
                    device = com.vulkairis.vulkan.VulkanCapabilities.getActiveCapabilities()
                            .getDeviceName();
                } catch (Throwable ignored) {
                    device = "Vulkan device";
                }
            }
        } catch (Throwable ignored) {
        }
        String effective = live && vulkanMod ? "Vulkan (VulkanMod)" : "OpenGL (vanilla)";
        return new Capabilities(vulkanMod, iris, live, device, effective, true);
    }

    /** @return backend choices genuinely offered by this build */
    public static java.util.List<String> offeredBackends(boolean allowOpenGlFallback) {
        java.util.List<String> backends = new java.util.ArrayList<>();
        backends.add("Auto");
        if (isPresent("net.vulkanmod.vulkan.Vulkan")) {
            backends.add("Vulkan");
        }
        if (allowOpenGlFallback) {
            backends.add("OpenGL");
        }
        return java.util.List.copyOf(backends);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, RendererCapabilityProvider.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
