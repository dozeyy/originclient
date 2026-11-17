package com.vulkairis.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Structured diagnostic recording and logging system for Vulkairis (Phase 1).
 *
 * <p>Every record carries a stable ID, the subsystem category, the render-pipeline phase that
 * failed, the affected shader/program identity, the source location when known, a hash of the
 * exact source that failed, and a recommended next action. A test report can therefore identify
 * the first failed shader stage or rendering phase without guessing.
 *
 * <p>Identical failures are recorded once: repeats increment a suppression counter instead of
 * spamming the log every frame.
 */
public class DiagnosticManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Diagnostics");

    public enum Category {
        VULKAN,
        SHADER,
        PIPELINE,
        RESOURCE,
        FRAMEBUFFER,
        COMPATIBILITY,
        FALLBACK
    }

    /**
     * Render-pipeline phase in which a diagnostic was raised. The trace must distinguish these;
     * logging alone is not enough.
     */
    public enum Phase {
        INTERCEPT,
        TRANSLATE,
        COMPILE,
        VALIDATE,
        REFLECT,
        LINK,
        PIPELINE,
        BIND,
        DRAW,
        TARGET,
        PRESENT,
        SCHEDULING,
        LIFECYCLE,
        CONFIG
    }

    public record DiagnosticRecord(
            String id,
            Instant timestamp,
            Category category,
            Phase phase,
            String message,
            String details,
            Throwable throwable,
            String shaderName,
            String stage,
            String sourceFile,
            int sourceLine,
            String sourceHash,
            String nextAction
    ) {
        /** Legacy shape: phase defaults to LIFECYCLE and provenance fields are unknown. */
        public DiagnosticRecord(
                Instant timestamp,
                Category category,
                String message,
                String details,
                Throwable throwable) {
            this(nextId(), timestamp, category, Phase.LIFECYCLE, message, details, throwable,
                    null, null, null, -1, null, null);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append('[').append(id).append("] [").append(category).append('/')
                    .append(phase).append("] ").append(message);
            if (shaderName != null && !shaderName.isBlank()) {
                builder.append(" (shader: ").append(shaderName);
                if (stage != null && !stage.isBlank()) {
                    builder.append(", stage: ").append(stage);
                }
                builder.append(')');
            }
            if (sourceFile != null && !sourceFile.isBlank()) {
                builder.append(" at ").append(sourceFile);
                if (sourceLine >= 0) {
                    builder.append(':').append(sourceLine);
                }
            }
            if (details != null && !details.isBlank()) {
                builder.append(" - ").append(details);
            }
            if (nextAction != null && !nextAction.isBlank()) {
                builder.append(" [next: ").append(nextAction).append(']');
            }
            return builder.toString();
        }
    }

    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    private static final List<DiagnosticRecord> HISTORY = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<String, DiagnosticRecord> SEEN = new ConcurrentHashMap<>();
    private static final AtomicLong SUPPRESSED_DUPLICATES = new AtomicLong(0);
    private static final int MAX_HISTORY = 200;

    private static String lastFallbackReason = null;
    private static volatile DiagnosticRecord lastCompileError = null;
    private static volatile DiagnosticRecord lastLinkError = null;

    private static String nextId() {
        return "VULK-" + SEQUENCE.incrementAndGet();
    }

    /**
     * @return a short stable hash of the exact source text, or {@code null} for blank input.
     */
    public static String hashSource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(source.hashCode());
        }
    }

    public static void log(Category category, String message) {
        log(category, message, null, null);
    }

    public static void log(Category category, String message, String details) {
        log(category, message, details, null);
    }

    public static void log(Category category, String message, String details, Throwable throwable) {
        logStructured(category, Phase.LIFECYCLE, message, details, throwable,
                null, null, null, -1, null, null);
    }

    /**
     * Records a structured diagnostic. An identical failure (same category, phase, message and
     * source hash) is only logged once; repeats increment the suppression counter and return the
     * original record so per-frame failures cannot spam the log.
     *
     * @return the recorded (or previously recorded, for duplicates) record
     */
    public static DiagnosticRecord logStructured(
            Category category, Phase phase, String message, String details, Throwable throwable,
            String shaderName, String stage, String sourceFile, int sourceLine,
            String sourceHash, String nextAction) {
        String key = category + "|" + phase + "|" + message + "|" + (sourceHash != null ? sourceHash : "");
        DiagnosticRecord existing = SEEN.get(key);
        if (existing != null) {
            SUPPRESSED_DUPLICATES.incrementAndGet();
            return existing;
        }

        DiagnosticRecord record = new DiagnosticRecord(nextId(), Instant.now(), category, phase,
                message, details, throwable, shaderName, stage, sourceFile, sourceLine,
                sourceHash, nextAction);
        SEEN.put(key, record);
        if (HISTORY.size() >= MAX_HISTORY) {
            HISTORY.remove(0);
        }
        HISTORY.add(record);

        if (phase == Phase.COMPILE) {
            lastCompileError = record;
        } else if (phase == Phase.LINK) {
            lastLinkError = record;
        }

        if (category == Category.FALLBACK) {
            lastFallbackReason = message + (details != null ? ": " + details : "");
            LOGGER.warn("[Vulkairis/Fallback] {}", record);
        } else if (throwable != null) {
            LOGGER.error("[Vulkairis/{}] {} - Details: {}", category, message, details, throwable);
        } else {
            LOGGER.info("[Vulkairis/{}] {}", category, record);
        }
        return record;
    }

    public static void recordFallback(String reason, String details) {
        log(Category.FALLBACK, reason, details);
    }

    /**
     * Phase E: records a fallback engagement with its full honesty identity (requested vs
     * effective backend, stage, pack+shader id, sanitized log excerpt) and logs the structured
     * FALLBACK diagnostic. Status commands mirror the returned record verbatim.
     *
     * @return the recorded engagement
     */
    public static FallbackRecord.Entry recordFallbackStructured(
            String requestedBackend, String effectiveBackend, String stage,
            String packId, String shaderId, String reason, String logExcerpt) {
        FallbackRecord.Entry entry = FallbackRecord.engage(
                requestedBackend, effectiveBackend, stage, packId, shaderId, reason, logExcerpt);
        logStructured(Category.FALLBACK, Phase.LIFECYCLE,
                "Fallback engaged: " + entry.reason(),
                FallbackRecord.describeSingleLine(entry), null,
                entry.shaderId(), entry.stage(), null, -1, null,
                "Vanilla rendering continues; use the status text to see what was requested vs effective");
        // The headline reason keeps the legacy "reason: cause" shape so guidance and status
        // lines carry the cause, followed by the structured identity in brackets. Assigned
        // after logging: the generic FALLBACK branch above would otherwise overwrite it with
        // the log message.
        String cause = firstExcerptLine(entry.logExcerpt());
        lastFallbackReason = entry.reason() + ": " + cause + " [" + entry.id()
                + " requested=" + entry.requestedBackend()
                + " effective=" + entry.effectiveBackend()
                + " stage=" + entry.stage()
                + " pack=" + entry.packId()
                + " shader=" + entry.shaderId() + "]";
        return entry;
    }

    /** @return the most recent Phase E fallback engagement, or {@code null} when healthy */
    public static FallbackRecord.Entry getLastFallbackRecord() {
        return FallbackRecord.last();
    }
    /** @return recent Phase E fallback engagements, newest last */
    public static java.util.List<FallbackRecord.Entry> getFallbackHistory(int limit) {
        return FallbackRecord.history(limit);
    }

    private static String firstExcerptLine(String excerpt) {
        if (excerpt == null || excerpt.isBlank()) {
            return "(no detail)";
        }
        int newline = excerpt.indexOf('\n');
        return newline < 0 ? excerpt : excerpt.substring(0, newline);
    }

    public static String getLastFallbackReason() {
        return lastFallbackReason != null ? lastFallbackReason : "None (Backend healthy)";
    }

    public static DiagnosticRecord getLastCompileError() {
        return lastCompileError;
    }

    public static DiagnosticRecord getLastLinkError() {
        return lastLinkError;
    }

    /** @return how many duplicate failure logs were suppressed */
    public static long getSuppressedDuplicateCount() {
        return SUPPRESSED_DUPLICATES.get();
    }

    public static List<DiagnosticRecord> getRecentRecords(int limit) {
        int size = HISTORY.size();
        if (size == 0) return Collections.emptyList();
        int start = Math.max(0, size - limit);
        return Collections.unmodifiableList(new ArrayList<>(HISTORY.subList(start, size)));
    }

    public static void clearHistory() {
        HISTORY.clear();
        SEEN.clear();
        SUPPRESSED_DUPLICATES.set(0);
        lastFallbackReason = null;
        lastCompileError = null;
        lastLinkError = null;
        FallbackRecord.clearForTests();
    }
}
