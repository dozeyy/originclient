package com.vulkairis.config;

/**
 * Manages loading, saving, and accessing Vulkairis configuration.
 *
 * <p>Thin delegate over {@link VulkairisConfigManager}, kept so existing call sites keep
 * working. New code should use the manager directly.</p>
 */
public class ConfigManager {
    /**
     * Manages loading, saving, and accessing Vulkairis configuration.
     */
    public static synchronized VulkairisConfig loadConfig() {
        return VulkairisConfigManager.loadConfig();
    }

    public static synchronized void saveConfig() {
        VulkairisConfigManager.saveConfig();
    }

    public static VulkairisConfig getConfig() {
        return VulkairisConfigManager.getConfig();
    }
}
