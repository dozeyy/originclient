package com.vulkairis.diagnostics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits the standardized per-stage evidence lines required by the implementation plan
 * (section 29). A shader must never be reported as ready merely because GLSL source
 * compilation succeeded; every later stage ({@code VkShaderModule}, reflection, program
 * linking, pipeline, command recording, submission) reports its own PASS/FAIL.
 */
public final class StageDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Stage");

    private StageDiagnostics() {
    }

    public static void intercepted(String identifier, String stage) {
        LOGGER.info("[Vulkairis] Shader intercepted: {} (stage: {})", identifier, stage);
    }

    public static void pass(String stage, String detail) {
        if (detail == null || detail.isBlank()) {
            LOGGER.info("[Vulkairis] {}: PASS", stage);
        } else {
            LOGGER.info("[Vulkairis] {}: PASS ({})", stage, detail);
        }
    }

    public static void fail(String stage, String detail, Throwable throwable) {
        if (throwable != null) {
            LOGGER.error("[Vulkairis] {}: FAIL ({})", stage,
                    (detail == null ? "" : detail + " - ") + throwable.getMessage(), throwable);
        } else {
            LOGGER.error("[Vulkairis] {}: FAIL ({})", stage, detail);
        }
    }

    public static void unsupported(String operation, String detail) {
        LOGGER.warn("[Vulkairis] Unsupported operation: {} ({})", operation, detail);
    }

    public static void warn(String stage, String detail) {
        if (detail == null || detail.isBlank()) {
            LOGGER.warn("[Vulkairis] {}: FAIL", stage);
        } else {
            LOGGER.warn("[Vulkairis] {}: FAIL ({})", stage, detail);
        }
    }
}
