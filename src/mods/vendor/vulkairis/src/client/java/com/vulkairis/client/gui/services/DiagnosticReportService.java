package com.vulkairis.client.gui.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.vulkairis.diagnostics.DiagnosticManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sanitized diagnostic reports with bounded export (GUI plan Phase 7).
 *
 * <p>Turns technical failures into a useful report: environment, versions, GPU, backend and
 * fallback decisions, pack identity (filenames only — never personal paths), stage-by-stage
 * status with the first failure, and a capped sanitized log excerpt. Exports are size-limited
 * and privacy-reviewed by construction; failures to export are actionable errors, and Copy
 * Details always remains as a fallback.</p>
 */
public final class DiagnosticReportService {

    /** Maximum log lines retained in a report. */
    public static final int MAX_LOG_LINES = 200;
    /** Maximum log characters retained in a report. */
    public static final int MAX_LOG_CHARS = 20_000;
    /** Maximum exported report characters. */
    public static final int MAX_REPORT_CHARS = 100_000;

    /** @param name section name @param lines section body lines */
    public record ReportSection(String name, List<String> lines) {
    }

    /** @param createdAt report time @param sections ordered sections */
    public record DiagnosticReport(Instant createdAt, List<ReportSection> sections) {
    }

    /** Actionable export failure (path, permission, size). */
    public static class ExportException extends Exception {
        public ExportException(String message) {
            super(message);
        }

        public ExportException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private DiagnosticReportService() {
    }

    /**
     * Collects a report from live state plus an optional compatibility result.
     *
     * @param compatReport latest staged validation, if any
     */
    public static DiagnosticReport collect(
            CompatibilityTestService.CompatibilityTestReport compatReport) {
        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("Environment", environmentLines()));
        sections.add(new ReportSection("Backend", backendLines()));
        sections.add(new ReportSection("Shader pack", packLines()));
        sections.add(new ReportSection("Stages", stageLines(compatReport)));
        sections.add(new ReportSection("Log excerpt", logExcerpt()));
        return new DiagnosticReport(Instant.now(), List.copyOf(sections));
    }

    /** @return a concise multi-line summary for Copy Details */
    public static String conciseSummary(DiagnosticReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("Vulkairis diagnostic report @ ").append(report.createdAt()).append('\n');
        for (ReportSection section : report.sections()) {
            builder.append("== ").append(section.name()).append(" ==\n");
            int shown = 0;
            for (String line : section.lines()) {
                if (shown >= 12) {
                    builder.append("... (").append(section.lines().size() - shown)
                            .append(" more)\n");
                    break;
                }
                builder.append(line).append('\n');
                shown++;
            }
        }
        String summary = builder.toString();
        if (summary.length() > MAX_REPORT_CHARS) {
            summary = summary.substring(0, MAX_REPORT_CHARS) + "\n... [truncated]";
        }
        return summary;
    }

    /** @return full text rendering */
    public static String renderText(DiagnosticReport report) {
        StringBuilder builder = new StringBuilder();
        builder.append("Vulkairis diagnostic report @ ").append(report.createdAt()).append('\n');
        for (ReportSection section : report.sections()) {
            builder.append("\n== ").append(section.name()).append(" ==\n");
            for (String line : section.lines()) {
                builder.append(line).append('\n');
            }
        }
        String text = builder.toString();
        if (text.length() > MAX_REPORT_CHARS) {
            text = text.substring(0, MAX_REPORT_CHARS) + "\n... [truncated]";
        }
        return text;
    }

    /** @return JSON rendering */
    public static String renderJson(DiagnosticReport report) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("createdAt", report.createdAt().toString());
        Map<String, List<String>> sections = new LinkedHashMap<>();
        for (ReportSection section : report.sections()) {
            sections.put(section.name(), section.lines());
        }
        root.put("sections", sections);
        String json = gson.toJson(root);
        if (json.length() > MAX_REPORT_CHARS) {
            json = json.substring(0, MAX_REPORT_CHARS) + "\n... [truncated]";
        }
        return json;
    }

    /**
     * Exports a report atomically.
     *
     * @param directory target directory (created when missing)
     * @param asJson    JSON instead of text
     * @return the written file
     * @throws ExportException with an actionable path/permission/size reason
     */
    public static Path export(DiagnosticReport report, Path directory, boolean asJson)
            throws ExportException {
        if (report == null) {
            throw new ExportException("nothing to export: no report collected");
        }
        if (directory == null) {
            throw new ExportException("no export directory configured");
        }
        String fileName = "vulkairis-diagnostic-" + report.createdAt().toString()
                .replace(':', '-').replace('.', '-') + (asJson ? ".json" : ".txt");
        try {
            Files.createDirectories(directory);
            Path target = directory.resolve(sanitizeFileName(fileName));
            String content = asJson ? renderJson(report) : renderText(report);
            Path temp = Files.createTempFile(directory, "diagnostic", ".tmp");
            try {
                Files.writeString(temp, content);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (java.nio.file.AccessDeniedException e) {
            throw new ExportException("permission denied writing to " + directory, e);
        } catch (Throwable t) {
            throw new ExportException("cannot write to " + directory + ": " + t.getMessage(), t);
        }
    }

    /**
     * Redacts personal paths: the user home becomes {@code ~} and the game directory becomes
     * {@code <gamedir>}. The current login name is redacted only as a path segment (never as
     * a bare word, so pack authors like "BSL" survive), and generic user-profile prefixes
     * ({@code Users\<name>}, {@code /home/<name>}, {@code /Users/<name>}) are reduced to
     * {@code <user>} so shared logs never leak other account names either. Usernames never
     * leave the machine inside a report.
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = text;
        try {
            String home = System.getProperty("user.home");
            if (home != null && !home.isBlank()) {
                redacted = redacted.replace(home, "~");
                String swapped = swapSeparators(home);
                if (!swapped.equals(home)) {
                    redacted = redacted.replace(swapped, "~");
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String gameDir = String.valueOf(net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().toAbsolutePath());
            if (!gameDir.isBlank() && !"null".equals(gameDir)) {
                redacted = redacted.replace(gameDir, "<gamedir>");
                String swapped = swapSeparators(gameDir);
                if (!swapped.equals(gameDir)) {
                    redacted = redacted.replace(swapped, "<gamedir>");
                }
            }
        } catch (Throwable ignored) {
        }
        // Generic profile prefixes (covers account names other than the current user).
        redacted = redacted.replaceAll("(?i)([/\\\\]Users[/\\\\])[^/\\\\:\"'\\s]+", "$1<user>");
        redacted = redacted.replaceAll("(?i)(/home/)[^/:\"'\\s]+", "$1<user>");
        redacted = redacted.replaceAll("(?i)(/Users/)[^/:\"'\\s]+", "$1<user>");
        try {
            String user = System.getProperty("user.name");
            if (user != null && user.length() >= 2) {
                // Path-segment scope only: "/alice/packs" -> "/<user>/packs".
                redacted = redacted.replaceAll("(?i)(?<=[/\\\\:])"
                        + java.util.regex.Pattern.quote(user) + "(?=[/\\\\:\"'\\s]|$)", "<user>");
            }
        } catch (Throwable ignored) {
        }
        return redacted;
    }

    private static String swapSeparators(String path) {
        if (path.indexOf('\\') >= 0) {
            return path.replace('\\', '/');
        }
        return path.replace('/', '\\');
    }

    private static List<String> environmentLines() {
        List<String> lines = new ArrayList<>();
        lines.add("minecraft: " + modVersion("minecraft", "unavailable"));
        lines.add("fabricloader: " + modVersion("fabricloader", "unavailable"));
        lines.add("vulkairis: " + modVersion("vulkairis", "unavailable"));
        lines.add("iris: " + modVersion("iris", "unavailable"));
        lines.add("vulkanmod: " + modVersion("vulkanmod", "unavailable"));
        lines.add("java: " + Runtime.version().feature());
        var capabilities = RendererCapabilityProvider.snapshot();
        lines.add("gpu: " + capabilities.deviceName());
        lines.add("device live: " + capabilities.deviceLive());
        return lines;
    }

    private static String modVersion(String modId, String fallback) {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse(fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static List<String> backendLines() {
        List<String> lines = new ArrayList<>();
        lines.add("requested backend: " + RendererSettingsService.requestedBackend());
        lines.add("effective backend: " + RendererSettingsService.effectiveBackend());
        lines.add("fallback: " + DiagnosticManager.getLastFallbackReason());
        String guidance = com.vulkairis.core.VulkairisController.getInstance()
                .getFallbackGuidance();
        lines.add("guidance: " + (guidance.isBlank() ? "none" : guidance));
        lines.add("state: " + com.vulkairis.core.VulkairisController.getInstance().getState());
        return lines;
    }

    private static List<String> packLines() {
        List<String> lines = new ArrayList<>();
        String active;
        try {
            active = (String) Class.forName("net.irisshaders.iris.Iris")
                    .getMethod("getCurrentPackName").invoke(null);
        } catch (Throwable t) {
            active = null;
        }
        lines.add("active pack: " + (active != null ? sanitize(active) : "none"));
        return lines;
    }

    private static List<String> stageLines(
            CompatibilityTestService.CompatibilityTestReport compatReport) {
        List<String> lines = new ArrayList<>();
        if (compatReport == null) {
            lines.add("no staged validation run yet");
            var compile = DiagnosticManager.getLastCompileError();
            lines.add("last compile error: "
                    + (compile != null ? sanitize(compile.id() + " " + compile.message()) : "none"));
            var link = DiagnosticManager.getLastLinkError();
            lines.add("last link error: "
                    + (link != null ? sanitize(link.id() + " " + link.message()) : "none"));
            return lines;
        }
        lines.add("summary: " + compatReport.summary());
        for (CompatibilityTestService.CompatibilityStage stage : compatReport.stages()) {
            lines.add(stage.status() + " " + stage.stage() + " — "
                    + sanitize(stage.detail()));
        }
        return lines;
    }

    private static List<String> logExcerpt() {
        List<String> lines = new ArrayList<>();
        List<DiagnosticManager.DiagnosticRecord> records =
                DiagnosticManager.getRecentRecords(MAX_LOG_LINES);
        int chars = 0;
        for (DiagnosticManager.DiagnosticRecord record : records) {
            String line = sanitize(record.toString());
            if (chars + line.length() > MAX_LOG_CHARS) {
                lines.add("... [log truncated at " + MAX_LOG_CHARS + " chars]");
                break;
            }
            lines.add(line);
            chars += line.length();
        }
        if (lines.isEmpty()) {
            lines.add("(no diagnostics recorded)");
        }
        return lines;
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
