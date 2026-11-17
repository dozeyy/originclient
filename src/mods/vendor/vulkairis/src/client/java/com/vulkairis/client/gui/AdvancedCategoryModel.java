package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.RendererSettingsService;
import com.vulkairis.config.VulkairisConfigManager;
import com.vulkairis.vulkan.pipeline.PipelineManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced category: correctness-affecting controls with warnings and safe reset
 * (GUI plan Phase 8). Experimental flags list only implemented flags — currently none, so
 * the section reports that instead of offering dead toggles. Cache controls are not
 * duplicated here: they live in Rendering; this menu shows cache state.
 */
public class AdvancedCategoryModel extends AbstractCategoryModel {

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        if (category != GuiCategory.ADVANCED) {
            return List.of();
        }
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Reload shader pipeline",
                "Rebuild generation-tracked state without touching shader packs or worlds.",
                "Safe reload; full rebuilds happen only when required.",
                SettingRow.Kind.ACTION, "reloadPipeline"));
        rows.add(new SettingRow("Resource validation",
                "Refuse frames whose targets lack backing instead of recording blind.",
                "Disable only to diagnose validation itself.", SettingRow.Kind.TOGGLE,
                "resourceValidation"));
        rows.add(new SettingRow("Load Spreading",
                "Distribute heavy translation/compile work across available workers under load.",
                "OFF keeps the existing single-worker behavior; ON spreads work when busy.",
                SettingRow.Kind.TOGGLE, "loadSpreading"));
        rows.add(new SettingRow("Debug render overlays",
                "On-screen labels for state, pass, target and backend.",
                "Visual overlay only.", SettingRow.Kind.TOGGLE, "debugOverlay"));
        rows.add(new SettingRow("Experimental flags",
                experimentalSummary(),
                "Only implemented flags are ever listed.", SettingRow.Kind.STATUS,
                "experimentalFlags"));
        rows.add(new SettingRow("Pipeline cache state",
                cacheSummary(),
                "Controls live in Rendering; this row only reports.",
                SettingRow.Kind.STATUS, "pipelineCacheState"));
        rows.add(new SettingRow("Safe reset",
                "Reset Vulkairis options and flags. Packs, worlds and other mods untouched.",
                "Persists safe defaults immediately.", SettingRow.Kind.ACTION, "safeReset"));
        return rows;
    }

    private String experimentalSummary() {
        if (ExperimentalFlags.implemented().isEmpty()) {
            return "No experimental flags implemented";
        }
        return ExperimentalFlags.implemented().size() + " flag(s)";
    }

    private String cacheSummary() {
        boolean enabled;
        try {
            enabled = VulkairisConfigManager.getConfig().isPipelineCacheEnabled();
        } catch (Throwable t) {
            enabled = true;
        }
        return (enabled ? "enabled" : "bypassed") + ", " + PipelineManager.memoryHits()
                + "/" + PipelineManager.memoryMisses() + " hits/misses";
    }

    @Override
    public boolean booleanValue(String key) {
        return switch (key) {
            case "resourceValidation" ->
                    VulkairisConfigManager.getConfig().isResourceValidation();
            case "debugOverlay" -> VulkairisConfigManager.getConfig().isDebugOverlay();
            case "loadSpreading" -> VulkairisConfigManager.getConfig().isLoadSpreadingEnabled();
            default -> false;
        };
    }

    /**
     * Phase F gate: the Advanced menu never offers cache invalidation. Memory/disk
     * invalidation stays behind the Rendering "Clear pipeline cache" action, which is backed
     * by the validated Phase C blob ({@code PipelineManager}); until a Phase C disk-cache
     * API exists for an Advanced-level control, this stays closed so no dead control can
     * imply an effect it does not have.
     *
     * @return whether this menu offers a cache-invalidation control (always false for now)
     */
    public boolean offersCacheInvalidation() {
        return false;
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        switch (key) {
            case "resourceValidation":
                VulkairisConfigManager.getConfig().setResourceValidation(requested);
                break;
            case "debugOverlay":
                VulkairisConfigManager.getConfig().setDebugOverlay(requested);
                break;
            case "loadSpreading":
                RendererSettingsService.setLoadSpreadingEnabled(requested);
                break;
            default:
                return false;
        }
        VulkairisConfigManager.saveConfig();
        touch();
        return true;
    }

    @Override
    public void runAction(String key) {
        if ("safeReset".equals(key)) {
            VulkairisConfigManager.resetToDefaults();
            pendingNotice("Options reset to safe defaults. Packs, worlds and other mods untouched.");
        } else if ("reloadPipeline".equals(key)) {
            com.vulkairis.iris.SourceProvenance.nextGeneration("manual pipeline reload");
            com.vulkairis.core.VulkairisController.getInstance().setPendingReload(true);
            pendingNotice("Pipeline reload requested; the next frame rebuilds the plan.");
        }
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        // Informational rows only; red is reserved for failed diagnostics.
        return StatusBadge.Status.NEUTRAL;
    }

    @Override
    public void resetCategory(GuiCategory category) {
        if (category != GuiCategory.ADVANCED) {
            return;
        }
        var defaults = new com.vulkairis.config.VulkairisConfig();
        var config = com.vulkairis.config.VulkairisConfigManager.getConfig();
        config.setResourceValidation(defaults.isResourceValidation());
        config.setDebugOverlay(defaults.isDebugOverlay());
        config.setLoadSpreadingEnabled(defaults.isLoadSpreadingEnabled());
        config.setExperimentalFlags(new java.util.LinkedHashMap<>());
        com.vulkairis.config.VulkairisConfigManager.saveConfig();
        touch();
    }

    @Override
    public String statusText(String key) {
        if ("experimentalFlags".equals(key)) {
            return experimentalSummary();
        }
        if ("pipelineCacheState".equals(key)) {
            return cacheSummary();
        }
        return "Not tested";
    }
}
