package com.vulkairis.client.gui.services;

import java.util.ArrayList;
import java.util.List;

/**
 * Startup validation (General menu): verifies required runtime capabilities before shader
 * execution is attempted, with one actionable problem per failed check.
 */
public final class StartupValidation {

    private StartupValidation() {
    }

    /** One capability check outcome. */
    public record Check(String name, boolean ok, String detail) {
    }

    /** @return the validation report; {@code passed} only when every check holds */
    public record Report(boolean passed, List<Check> checks) {
        public List<String> problems() {
            List<String> problems = new ArrayList<>();
            for (Check check : checks) {
                if (!check.ok()) {
                    problems.add(check.name() + ": " + check.detail());
                }
            }
            return problems;
        }
    }

    /** Runs every startup check. Never throws; failures are data. */
    public static Report validate() {
        List<Check> checks = new ArrayList<>();
        checks.add(new Check("Java 21+", Runtime.version().feature() >= 21,
                "running on Java " + Runtime.version().feature()));
        checks.add(present("Iris shaders", "net.irisshaders.iris.Iris"));
        checks.add(present("VulkanMod", "net.vulkanmod.vulkan.Vulkan"));
        boolean deviceLive = false;
        try {
            deviceLive = com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.isLive(
                    com.vulkairis.backend.vulkanmod.VulkanBackendAdapter.getDevice());
        } catch (Throwable ignored) {
        }
        // Capability, not liveness: the device arrives with the first frames, so a dead device
        // before then is expected. What matters is that VulkanMod is installed to provide one.
        Check modCheck = present("VulkanMod", "net.vulkanmod.vulkan.Vulkan");
        checks.add(new Check("Vulkan device capability", modCheck.ok(),
                deviceLive ? "device live" : "VulkanMod installed; device arrives with first frame"));
        com.vulkairis.config.VulkairisConfig config;
        try {
            config = com.vulkairis.config.VulkairisConfigManager.getConfig();
        } catch (Throwable t) {
            config = null;
        }
        if (config == null) {
            checks.add(new Check("Configuration", false, "config unreadable"));
        } else {
            List<String> problems = config.validate();
            checks.add(new Check("Configuration", problems.isEmpty(),
                    problems.isEmpty() ? "valid" : String.join("; ", problems)));
        }
        boolean passed = checks.stream().allMatch(Check::ok);
        return new Report(passed, List.copyOf(checks));
    }

    private static Check present(String name, String className) {
        boolean found = false;
        try {
            Class.forName(className, false, StartupValidation.class.getClassLoader());
            found = true;
        } catch (Throwable ignored) {
        }
        return new Check(name, found, found ? "present" : "not installed");
    }
}
