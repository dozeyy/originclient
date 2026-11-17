package com.vulkairis.client.gui;

/**
 * Stable GUI categories (plan §2). Names are UI-facing and must not change without a
 * migration note; keyboard navigation follows this declaration order.
 */
public enum GuiCategory {
    GENERAL("General"),
    SHADER_PACKS("Shader Packs"),
    RENDERING("Rendering"),
    COMPATIBILITY("Compatibility"),
    DIAGNOSTICS("Diagnostics"),
    ADVANCED("Advanced");

    private final String title;

    GuiCategory(String title) {
        this.title = title;
    }

    /** @return the display title (moved to translation keys in the localization phase) */
    public String title() {
        return title;
    }
}
