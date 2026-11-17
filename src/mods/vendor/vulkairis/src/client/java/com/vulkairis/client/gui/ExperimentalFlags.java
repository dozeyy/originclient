package com.vulkairis.client.gui;

import java.util.List;

/**
 * Implemented experimental flags (GUI plan Phase 8). Only flags with a real code path behind
 * them are listed — each with its risk — and everything defaults OFF. There are currently no
 * implemented flags, so the Advanced menu reports that honestly instead of offering toggles
 * that do nothing.
 */
public final class ExperimentalFlags {

    /** @param name stable flag name @param risk short risk description */
    public record ExperimentalFlag(String name, String risk) {
    }

    private ExperimentalFlags() {
    }

    /** @return every implemented experimental flag (empty until one lands) */
    public static List<ExperimentalFlag> implemented() {
        return List.of();
    }
}
