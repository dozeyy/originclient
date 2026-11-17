package com.vulkairis.client.gui;

import com.vulkairis.client.gui.services.PackSmokeTest;
import com.vulkairis.client.gui.services.PackTestHistory;
import com.vulkairis.client.gui.services.ShaderPackService;
import com.vulkairis.translation.TranslationWorker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Shader Packs category: live browser state over the configured folder (GUI plan Phase 5).
 *
 * <p>Selection is browser selection (inspect and test) — explicitly not pack activation, which
 * stays with Iris. Compatibility marks record smoke-test outcomes per file stamp; a changed
 * file goes stale and requires retesting. Removed files disappear and cannot stay selected.</p>
 */
public class ShaderPacksCategoryModel extends AbstractCategoryModel {

    private volatile ShaderPackService.ScanResult lastScan;
    private volatile String selectedFile;
    private final PackTestHistory history = new PackTestHistory();
    private volatile long scanRequest;
    private volatile TranslationWorker.Task<ShaderPackService.ScanResult> pendingScan;
    private final java.util.concurrent.atomic.AtomicBoolean cancelFlag =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean testRunning;
    private volatile int progressDone;
    private volatile int progressTotal;

    @Override
    public List<SettingRow> rowsFor(GuiCategory category) {
        if (category != GuiCategory.SHADER_PACKS) {
            return List.of();
        }
        List<SettingRow> rows = new ArrayList<>();
        rows.add(new SettingRow("Open pack browser",
                "Browse, search, select and test shader packs from the live folder scan.",
                "Cards come only from the configured folder.", SettingRow.Kind.ACTION, "openBrowser"));
        rows.add(new SettingRow("Rescan folder",
                "Reconcile added, removed, renamed, replaced and unreadable archives.",
                "Also clears selections for vanished files.", SettingRow.Kind.ACTION, "rescan"));
        rows.add(new SettingRow("Selected pack",
                "Browser selection. Activation stays with the Iris shader menu.",
                "Selection never implies a passing test.", SettingRow.Kind.STATUS, "selectedPack"));
        rows.add(new SettingRow("Last compatibility test",
                "Smoke-test outcome for the selected file stamp.",
                "Stale after any file change.", SettingRow.Kind.STATUS, "lastTest"));
        return rows;
    }

    /** @return the configured Iris shader-pack folder, or empty when Iris is absent */
    public Optional<Path> configuredDirectory() {
        try {
            Path directory = (Path) Class.forName("net.irisshaders.iris.Iris")
                    .getMethod("getShaderpacksDirectory").invoke(null);
            return Optional.ofNullable(directory);
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /** @return the last scan result, if any */
    public Optional<ShaderPackService.ScanResult> lastScan() {
        return Optional.ofNullable(lastScan);
    }

    /** @return the selected filename, if it is still discovered */
    public Optional<String> selectedFile() {
        return Optional.ofNullable(selectedFile);
    }

    public PackTestHistory history() {
        return history;
    }

    /**
     * Scans synchronously (call off the render thread) and reconciles selection and history.
     */
    public ShaderPackService.ScanResult rescan(Path directory) {
        ShaderPackService.ScanResult result = ShaderPackService.scan(directory);
        applyScan(result);
        return result;
    }

    /** Starts an async scan; collect it with {@link #collectScan()}. */
    public void rescanAsync(Path directory) {
        long request = ++scanRequest;
        pendingScan = ShaderPackService.scanAsync(request, directory);
    }

    /**
     * Collects a finished async scan when it is still the latest request.
     *
     * @return true when a result was installed (screen should refresh)
     */
    public boolean collectScan() {
        TranslationWorker.Task<ShaderPackService.ScanResult> pending = pendingScan;
        if (pending == null || !pending.future().isDone()) {
            return false;
        }
        pendingScan = null;
        Optional<ShaderPackService.ScanResult> result =
                TranslationWorker.takeIfCurrent(pending, scanRequest, 0L);
        if (result.isEmpty()) {
            return false;
        }
        applyScan(result.get());
        return true;
    }

    /** @return whether a scan is in flight */
    public boolean scanPending() {
        TranslationWorker.Task<ShaderPackService.ScanResult> pending = pendingScan;
        return pending != null && !pending.future().isDone();
    }

    /**
     * Phase F browser state for the Rev 2 §12.3–12.4 matrix: {@code scanning} while a
     * request is in flight, {@code waiting} before the first scan lands, {@code failed}
     * when the folder could not be scanned (missing folder, mods-folder refusal, IO
     * error), {@code empty} for a clean folder with no packs, {@code ready} otherwise.
     */
    public String scanState() {
        if (scanPending()) {
            return "scanning";
        }
        ShaderPackService.ScanResult result = lastScan;
        if (result == null) {
            return "waiting";
        }
        if (!result.scanned()) {
            return "failed";
        }
        if (ShaderPackService.packsOrEmpty(result).isEmpty()) {
            return "empty";
        }
        return "ready";
    }

    private void applyScan(ShaderPackService.ScanResult result) {
        lastScan = result;
        Set<String> identities = new HashSet<>();
        for (ShaderPackService.DiscoveredPack pack : ShaderPackService.packsOrEmpty(result)) {
            identities.add(pack.stableId());
        }
        history.retainOnly(identities);
        if (selectedFile != null && !identities.contains(selectedFile)) {
            selectedFile = null;
            pendingNotice("The selected pack vanished; selection cleared.");
        }
        touch();
    }

    /**
     * Selects a discovered file. Unknown files are refused; selection never implies a test pass.
     *
     * @return whether the selection stuck
     */
    public boolean select(String fileName) {
        if (fileName == null) {
            selectedFile = null;
            touch();
            return true;
        }
        boolean known = ShaderPackService.packsOrEmpty(lastScan).stream()
                .anyMatch(pack -> pack.fileName().equals(fileName));
        if (!known) {
            pendingNotice("That file is not a selectable pack in the current scan.");
            return false;
        }
        selectedFile = fileName;
        touch();
        return true;
    }

    /** @return the selected pack, if still discovered */
    public Optional<ShaderPackService.DiscoveredPack> selectedPack() {
        if (selectedFile == null) {
            return Optional.empty();
        }
        return ShaderPackService.packsOrEmpty(lastScan).stream()
                .filter(pack -> pack.fileName().equals(selectedFile))
                .findFirst();
    }

    /** @return whether the browser selection is also the pack Iris has active */
    public boolean selectedIsActiveInIris() {
        Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
        if (selected.isEmpty()) {
            return false;
        }
        try {
            String active = (String) Class.forName("net.irisshaders.iris.Iris")
                    .getMethod("getCurrentPackName").invoke(null);
            return active != null && active.equals(selected.get().displayName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Validates the selected archive through every applicable stage and records the mark.
     *
     * @return the full report, or empty when nothing is selected
     */
    public Optional<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport> testSelectedFull(
            Path directory) {
        Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
        if (selected.isEmpty() || directory == null) {
            return Optional.empty();
        }
        var report = com.vulkairis.client.gui.services.CompatibilityTestService.runPackValidation(
                directory.resolve(selectedFile), new java.util.concurrent.atomic.AtomicBoolean(false),
                null);
        boolean passed = report.summary()
                == com.vulkairis.client.gui.services.CompatibilityTestService.StageStatus.PASSED;
        String detail = report.firstFailure()
                .map(failure -> failure.stage() + ": " + failure.detail())
                .orElse(report.stages().size() + " stages evaluated");
        history.record(selectedFile, selected.get().contentStamp(),
                new PackSmokeTest.SmokeResult(passed,
                        report.stages().stream()
                                .map(com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityStage::stage)
                                .toList(),
                        detail));
        touch();
        return Optional.of(report);
    }

    /**
     * Smoke-tests the selected archive (single-pair quick probe) and records the mark.
     *
     * @return the result, or empty when nothing is selected
     */
    public Optional<PackSmokeTest.SmokeResult> testSelected(Path directory) {
        Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
        if (selected.isEmpty() || directory == null) {
            return Optional.empty();
        }
        PackSmokeTest.SmokeResult result = PackSmokeTest.test(directory.resolve(selectedFile));
        history.record(selectedFile, selected.get().contentStamp(), result);
        touch();
        return Optional.of(result);
    }

    /** @return the latest async request id (scans and tests share it; newer drops older) */
    public long latestRequest() {
        return scanRequest;
    }

    @Override
    public void resetCategory(GuiCategory category) {
        if (category != GuiCategory.SHADER_PACKS) {
            return;
        }
        selectedFile = null;
        history.clear();
        touch();
    }

    /**
     * Smoke-tests the selected archive off-thread; collect it with {@link #collectSmokeTest}.
     */
    public TranslationWorker.Task<Optional<PackSmokeTest.SmokeResult>> testSelectedAsync(
            Path directory) {
        long request = ++scanRequest;
        return TranslationWorker.submit("pack-smoke-test", request,
                () -> testSelected(directory));
    }

    /**
     * Validates the selected archive through every applicable stage off-thread, with progress
     * and cancellation; collect it with {@link #collectFullTest}.
     */
    public TranslationWorker.Task<Optional<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport>> testFullAsync(
            Path directory) {
        long request = ++scanRequest;
        cancelFlag.set(false);
        testRunning = true;
        progressDone = 0;
        progressTotal = 0;
        touch();
        return TranslationWorker.submit("pack-full-test", request, () -> {
            try {
                Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
                if (selected.isEmpty() || directory == null) {
                    return Optional.<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport>empty();
                }
                var report = com.vulkairis.client.gui.services.CompatibilityTestService.runPackValidation(
                        directory.resolve(selectedFile), cancelFlag,
                        (name, done, total) -> {
                            progressDone = done;
                            progressTotal = total;
                            touch();
                        });
                boolean passed = report.summary()
                        == com.vulkairis.client.gui.services.CompatibilityTestService.StageStatus.PASSED;
                String detail = report.firstFailure()
                        .map(failure -> failure.stage() + ": " + failure.detail())
                        .orElse(report.stages().size() + " stages evaluated");
                history.record(selectedFile, selected.get().contentStamp(),
                        new PackSmokeTest.SmokeResult(passed,
                                report.stages().stream()
                                        .map(com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityStage::stage)
                                        .toList(),
                                detail));
                return Optional.of(report);
            } finally {
                testRunning = false;
                touch();
            }
        });
    }

    /**
     * Requests cancellation of a running validation (honored between pairs, never mid-compile).
     */
    public void cancelTest() {
        cancelFlag.set(true);
    }

    /** @return whether a validation is currently running */
    public boolean isTestRunning() {
        return testRunning;
    }

    /** @return progress numerator for the running validation */
    public int progressDone() {
        return progressDone;
    }

    /** @return progress denominator for the running validation */
    public int progressTotal() {
        return progressTotal;
    }

    /**
     * Collects a finished smoke test when it is still the latest request.
     *
     * @return true when a result was installed (screen should refresh)
     */
    public boolean collectSmokeTest(
            TranslationWorker.Task<Optional<PackSmokeTest.SmokeResult>> pending) {
        if (pending == null || !pending.future().isDone()) {
            return false;
        }
        Optional<PackSmokeTest.SmokeResult> result =
                TranslationWorker.<Optional<PackSmokeTest.SmokeResult>>takeIfCurrent(
                        pending, scanRequest, 0L).flatMap(value -> value);
        touch();
        return result.isPresent();
    }

    /**
     * Collects a finished full validation when it is still the latest request.
     *
     * @return true when a result was installed (screen should refresh)
     */
    public boolean collectFullTest(
            TranslationWorker.Task<Optional<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport>> pending) {
        if (pending == null || !pending.future().isDone()) {
            return false;
        }
        Optional<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport> result =
                TranslationWorker.<Optional<com.vulkairis.client.gui.services.CompatibilityTestService.CompatibilityTestReport>>takeIfCurrent(
                        pending, scanRequest, 0L).flatMap(value -> value);
        touch();
        return result.isPresent();
    }
    /** @return staleness-aware test status text for the status row */
    public String testStatusText() {
        Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
        if (selected.isEmpty()) {
            return "No pack selected";
        }
        String stamp = selected.get().contentStamp();
        Optional<PackTestHistory.TestMark> mark = history.markFor(selectedFile);
        if (mark.isEmpty()) {
            return "Never tested";
        }
        if (history.isStale(selectedFile, stamp)) {
            return "Stale — file changed, retest required";
        }
        return (mark.get().passed() ? "Passed" : "Failed") + ": " + mark.get().detail();
    }

    @Override
    public StatusBadge.Status statusOf(String key) {
        if ("selectedPack".equals(key)) {
            return selectedPack().isPresent() ? StatusBadge.Status.PASS : StatusBadge.Status.NEUTRAL;
        }
        if ("lastTest".equals(key)) {
            Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
            if (selected.isEmpty()) {
                return StatusBadge.Status.NEUTRAL;
            }
            Optional<PackTestHistory.TestMark> mark = history.markFor(selectedFile);
            if (mark.isEmpty() || history.isStale(selectedFile, selected.get().contentStamp())) {
                return StatusBadge.Status.WARN;
            }
            return mark.get().passed() ? StatusBadge.Status.PASS : StatusBadge.Status.FAIL;
        }
        return StatusBadge.Status.NEUTRAL;
    }

    @Override
    public String statusText(String key) {
        if ("selectedPack".equals(key)) {
            Optional<ShaderPackService.DiscoveredPack> selected = selectedPack();
            if (selected.isEmpty()) {
                return "None";
            }
            return selected.get().displayName()
                    + (selectedIsActiveInIris() ? " (active in Iris)" : " (not active in Iris)");
        }
        if ("lastTest".equals(key)) {
            return testStatusText();
        }
        return "Not tested";
    }

    @Override
    public void runAction(String key) {
        if ("openBrowser".equals(key)) {
            navigateTo("browser");
        } else if ("rescan".equals(key)) {
            Optional<Path> directory = configuredDirectory();
            if (directory.isEmpty()) {
                pendingNotice("Iris is not installed; no folder to scan.");
            } else {
                rescanAsync(directory.get());
            }
        }
    }
}
