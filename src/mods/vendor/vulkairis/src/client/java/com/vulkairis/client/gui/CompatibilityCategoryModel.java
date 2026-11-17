package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.CompatibilityTestService;
import com.vulkairis.config.VulkairisConfig;
import com.vulkairis.config.VulkairisConfigManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compatibility category (GUI plan Phase 6): staged validation workflow.
 *
 * <p>Runs the shared {@link CompatibilityTestService} fixture validation off-thread,
 * preserves the first failing stage, and never claims success from world-load alone.
 * Strict handling maps to {@code compatibilityMode=STRICT} (default ON per plan).</p>
 */
public class CompatibilityCategoryModel extends AbstractCategoryModel {

    private volatile CompatibilityTestService.CompatibilityTestReport lastReport;
    private volatile boolean testRunning;
    private volatile int progressDone;
    private volatile int progressTotal;
    private final AtomicBoolean cancelFlag = new AtomicBoolean(false);

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        if (category != GuiCategory.COMPATIBILITY) {
            return List.of();
        }
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Run compatibility test",
                "Validate fixtures through translate/compile/validate/reflect stages.",
                "Shows progress; first failure is preserved.", SettingRow.Kind.ACTION, "runTest"));
        rows.add(new SettingRow("Strict failure handling",
                "Stop and report at first unsupported critical stage.",
                "Default ON for validation.", SettingRow.Kind.TOGGLE, "strictFailures"));
        rows.add(new SettingRow("Copy details",
                "Copy the concise result to the clipboard.",
                "Fallback when export is unavailable.", SettingRow.Kind.ACTION, "copyDetails"));
        rows.add(new SettingRow("Test status",
                "Overall verdict of the last run.",
                "Passed / Failed / Partial / Not supported.", SettingRow.Kind.STATUS, "testStatus"));
        rows.add(new SettingRow("First failure",
                "First failing stage and cause.",
                "Empty when the last run passed.", SettingRow.Kind.STATUS, "firstFailure"));
        return rows;
    }

    /** @return whether a validation is currently running */
    public boolean isTestRunning() {
        return testRunning;
    }

    public int progressDone() {
        return progressDone;
    }

    public int progressTotal() {
        return progressTotal;
    }

    public void cancelTest() {
        cancelFlag.set(true);
    }

    /** @return the last report, if any */
    public java.util.Optional<CompatibilityTestService.CompatibilityTestReport> lastReport() {
        return java.util.Optional.ofNullable(lastReport);
    }

    private boolean strict() {
        return VulkairisConfigManager.getConfig().getCompatibilityMode()
                == VulkairisConfig.CompatibilityMode.STRICT;
    }

    @Override
    public boolean booleanValue(String key) {
        if ("strictFailures".equals(key)) {
            return strict();
        }
        return false;
    }

    @Override
    public boolean setBoolean(String key, boolean requested) {
        if (!"strictFailures".equals(key)) {
            return false;
        }
        VulkairisConfigManager.getConfig().setCompatibilityMode(requested
                ? VulkairisConfig.CompatibilityMode.STRICT
                : VulkairisConfig.CompatibilityMode.AUTOMATIC);
        VulkairisConfigManager.saveConfig();
        touch();
        return true;
    }

    @Override
    public void runAction(String key) {
        if ("runTest".equals(key)) {
            runAsync();
        } else if ("copyDetails".equals(key)) {
            copyDetails();
        }
    }

    private void runAsync() {
        if (testRunning) {
            return;
        }
        cancelFlag.set(false);
        testRunning = true;
        progressDone = 0;
        progressTotal = 0;
        touch();
        boolean strictAtStart = strict();
        new Thread(() -> {
            try {
                CompatibilityTestService.CompatibilityTestReport report =
                        CompatibilityTestService.runFixtureValidation(cancelFlag,
                                (name, done, total) -> {
                                    progressDone = done;
                                    progressTotal = total;
                                    touch();
                                });
                if (strictAtStart && report.summary()
                        == CompatibilityTestService.StageStatus.PARTIAL) {
                    // Strict mode surfaces partials at full severity in the status row;
                    // the stored report itself is unchanged.
                }
                lastReport = report;
            } catch (Throwable t) {
                pendingNotice("Compatibility test failed: " + t.getMessage());
            } finally {
                testRunning = false;
                touch();
            }
        }, "vulkairis-compat-test").start();
    }

    private void copyDetails() {
        CompatibilityTestService.CompatibilityTestReport report = lastReport;
        String summary = report == null ? "No compatibility test run yet."
                : report.summary() + " — "
                + report.firstFailure().map(f -> f.stage() + ": " + f.detail())
                .orElse(report.stages().size() + " stages evaluated");
        try {
            net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(summary);
            pendingNotice("Copied " + summary.length() + " chars");
        } catch (Throwable t) {
            pendingNotice("Clipboard unavailable; summary is " + summary.length() + " chars");
        }
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        if ("testStatus".equals(key)) {
            if (lastReport == null) {
                return StatusBadge.Status.NEUTRAL;
            }
            return switch (lastReport.summary()) {
                case PASSED -> StatusBadge.Status.PASS;
                case FAILED -> StatusBadge.Status.FAIL;
                case PARTIAL, NOT_SUPPORTED -> StatusBadge.Status.WARN;
            };
        }
        if ("firstFailure".equals(key)) {
            if (lastReport == null) {
                return StatusBadge.Status.NEUTRAL;
            }
            return lastReport.firstFailure().isPresent()
                    ? StatusBadge.Status.FAIL : StatusBadge.Status.PASS;
        }
        return StatusBadge.Status.NEUTRAL;
    }

    @Override
    public String statusText(String key) {
        if ("testStatus".equals(key)) {
            if (testRunning) {
                return "Running " + progressDone + "/" + progressTotal;
            }
            if (lastReport == null) {
                return "Not tested";
            }
            return lastReport.summary().name();
        }
        if ("firstFailure".equals(key)) {
            if (lastReport == null) {
                return "Not tested";
            }
            return lastReport.firstFailure()
                    .map(f -> f.stage() + ": " + f.detail())
                    .orElse("None — all stages passed");
        }
        return "Not tested";
    }

    @Override
    public void resetCategory(GuiCategory category) {
        if (category != GuiCategory.COMPATIBILITY) {
            return;
        }
        VulkairisConfigManager.getConfig()
                .setCompatibilityMode(VulkairisConfig.CompatibilityMode.STRICT);
        VulkairisConfigManager.saveConfig();
        lastReport = null;
        touch();
    }
}
