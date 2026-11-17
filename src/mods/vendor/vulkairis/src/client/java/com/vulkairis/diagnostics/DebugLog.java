package com.vulkairis.diagnostics;

/**
 * Origin (2026-11-17): one switch for Vulkairis's periodic debug logging (render-target samples every
 * 200 frames, loading-overlay and screen traces). Off by default: on a normal game it flooded the log.
 * Turn on with {@code -Dvulkairis.debugLog=true}.
 */
public final class DebugLog {
    public static final boolean ENABLED = Boolean.getBoolean("vulkairis.debugLog");

    private DebugLog() {
    }
}
