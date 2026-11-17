package com.vulkairis.config;

import com.vulkairis.core.DebugRenderMode;

import java.util.Map;

/**
 * Configuration model for Vulkairis with safe defaults.
 *
 * <p>GUI plan persistence rules: stable keys, enums as string identifiers, validation before
 * use, schema versioning with migration, and repair of missing entries from older files.
 * Every field has a default; {@link #repair()} fills anything a previous version left null.</p>
 */
public class VulkairisConfig {
    /** Config schema version. Bump when keys are renamed/removed; see the manager migration. */
    public static final int CURRENT_SCHEMA_VERSION = 2;

    public enum CompatibilityMode {
        AUTOMATIC,
        STRICT
    }

    /** Diagnostic verbosity, stored as a stable string identifier (never the display label). */
    public enum DiagnosticVerbosity {
        BASIC,
        DETAILED,
        DEVELOPER
    }

    private int schemaVersion = CURRENT_SCHEMA_VERSION;
    private boolean enabled = true;
    private boolean useVulkanRenderer = true;
    /** Preferred backend selection policy; the effective backend may differ (see status). */
    private boolean autoBackend = true;
    /** Whether the OpenGL fallback path may be used where valid. */
    private boolean allowOpenGlFallback = true;
    private boolean showStartupStatus = true;
    private boolean startupValidation = true;
    private DiagnosticVerbosity diagnosticVerbosity = DiagnosticVerbosity.BASIC;
    private boolean logShaderCompileErrors = true;
    private boolean includeRendererCapabilities = false;
    /** Whether the driver pipeline cache is used (default ON: implementation validated). */
    private boolean pipelineCacheEnabled = true;
    /**
     * Validate render-target backing before recording passes (default ON). When enabled, a
     * frame whose targets lack backing is skipped with a diagnostic instead of recording
     * blind; disable only to diagnose validation itself.
     */
    private boolean resourceValidation = true;
    /**
     * Experimental flags by stable name. Only implemented flags may be listed by the UI;
     * everything defaults OFF and unknown names are ignored by the renderer.
     */
    private Map<String, Boolean> experimentalFlags = new java.util.LinkedHashMap<>();
    private CompatibilityMode compatibilityMode = CompatibilityMode.AUTOMATIC;
    private boolean dryRunTesting = true;
    private boolean debugLogging = true;
    private boolean debugOverlay = false;
    private boolean dumpSpirv = false;
    /** Phase 1: write translated GLSL to run/vulkairis-dumps only when explicitly enabled. */
    private boolean dumpTranslatedSource = false;
    /** Phase 1: write SPIR-V reflection summaries to run/vulkairis-dumps only when enabled. */
    private boolean dumpReflection = false;
    /** Phase 1: capture per-pass diagnostics (target bindings, pass in/out) when enabled. */
    private boolean perPassCapture = false;
    /** Phase 1: verbose pipeline trace logging (intercept/translate/compile/link phases). */
    private boolean traceLogging = false;
    /**
     * Load spreading: when enabled, heavy translation/compile workloads are distributed across
     * the available compile workers instead of a single worker. Defaults OFF until validated
     * (safe default per the load-spreading plan).
     */
    private boolean loadSpreadingEnabled = false;
    /**
     * Controlled render mode. Defaults to {@link DebugRenderMode#OFF} so a fresh config never
     * draws the test quad over the menu or before a world is loaded. Use {@code /vulkairis mode
     * triangle}, {@code /vulkairis mode shader_test} or one of the diagnostic visualizers
     * ({@code flat}, {@code uv}, {@code lightmap}, {@code depth}, {@code no_post}) to opt in; every
     * controlled mode is also gated to real world frames (see
     * {@code StandaloneVulkanTest.isWorldPassActive} / {@code shouldDrawControlled}).
     */
    private DebugRenderMode debugRenderMode = DebugRenderMode.OFF;
    /**
     * When true, intercepted Iris programs are linked into VulkanMod's pipeline registry so that
     * {@code glUseProgram(virtualId)} binds a real Vulkan pipeline instead of silently doing
     * nothing. Disable to fall back to the previous metadata-only behaviour.
     */
    private boolean irisPipelineLink = true;

    public VulkairisConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isUseVulkanRenderer() {
        return useVulkanRenderer;
    }

    public void setUseVulkanRenderer(boolean useVulkanRenderer) {
        this.useVulkanRenderer = useVulkanRenderer;
    }

    public CompatibilityMode getCompatibilityMode() {
        return compatibilityMode;
    }

    public void setCompatibilityMode(CompatibilityMode compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

    public boolean isDryRunTesting() {
        return dryRunTesting;
    }

    public void setDryRunTesting(boolean dryRunTesting) {
        this.dryRunTesting = dryRunTesting;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public void setDebugLogging(boolean debugLogging) {
        this.debugLogging = debugLogging;
    }

    public boolean isDebugOverlay() {
        return debugOverlay;
    }

    public void setDebugOverlay(boolean debugOverlay) {
        this.debugOverlay = debugOverlay;
    }

    public boolean isDumpSpirv() {
        return dumpSpirv;
    }

    public void setDumpSpirv(boolean dumpSpirv) {
        this.dumpSpirv = dumpSpirv;
    }

    public boolean isDumpTranslatedSource() {
        return dumpTranslatedSource;
    }

    public void setDumpTranslatedSource(boolean dumpTranslatedSource) {
        this.dumpTranslatedSource = dumpTranslatedSource;
    }

    public boolean isDumpReflection() {
        return dumpReflection;
    }

    public void setDumpReflection(boolean dumpReflection) {
        this.dumpReflection = dumpReflection;
    }

    public boolean isPerPassCapture() {
        return perPassCapture;
    }

    public void setPerPassCapture(boolean perPassCapture) {
        this.perPassCapture = perPassCapture;
    }

    public boolean isTraceLogging() {
        return traceLogging;
    }

    public void setTraceLogging(boolean traceLogging) {
        this.traceLogging = traceLogging;
    }

    public boolean isLoadSpreadingEnabled() {
        return loadSpreadingEnabled;
    }

    public void setLoadSpreadingEnabled(boolean loadSpreadingEnabled) {
        this.loadSpreadingEnabled = loadSpreadingEnabled;
    }

    public DebugRenderMode getDebugRenderMode() {
        return debugRenderMode != null ? debugRenderMode : DebugRenderMode.OFF;
    }

    public void setDebugRenderMode(DebugRenderMode debugRenderMode) {
        this.debugRenderMode = (debugRenderMode != null) ? debugRenderMode : DebugRenderMode.OFF;
    }

    public boolean isIrisPipelineLink() {
        return irisPipelineLink;
    }

    public void setIrisPipelineLink(boolean irisPipelineLink) {
        this.irisPipelineLink = irisPipelineLink;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isAutoBackend() {
        return autoBackend;
    }

    public void setAutoBackend(boolean autoBackend) {
        this.autoBackend = autoBackend;
    }

    public boolean isAllowOpenGlFallback() {
        return allowOpenGlFallback;
    }

    public void setAllowOpenGlFallback(boolean allowOpenGlFallback) {
        this.allowOpenGlFallback = allowOpenGlFallback;
    }

    public boolean isShowStartupStatus() {
        return showStartupStatus;
    }

    public void setShowStartupStatus(boolean showStartupStatus) {
        this.showStartupStatus = showStartupStatus;
    }

    public boolean isStartupValidation() {
        return startupValidation;
    }

    public void setStartupValidation(boolean startupValidation) {
        this.startupValidation = startupValidation;
    }

    public DiagnosticVerbosity getDiagnosticVerbosity() {
        return diagnosticVerbosity != null ? diagnosticVerbosity : DiagnosticVerbosity.BASIC;
    }

    public void setDiagnosticVerbosity(DiagnosticVerbosity diagnosticVerbosity) {
        this.diagnosticVerbosity =
                diagnosticVerbosity != null ? diagnosticVerbosity : DiagnosticVerbosity.BASIC;
    }

    public boolean isLogShaderCompileErrors() {
        return logShaderCompileErrors;
    }

    public void setLogShaderCompileErrors(boolean logShaderCompileErrors) {
        this.logShaderCompileErrors = logShaderCompileErrors;
    }

    public boolean isIncludeRendererCapabilities() {
        return includeRendererCapabilities;
    }

    public void setIncludeRendererCapabilities(boolean includeRendererCapabilities) {
        this.includeRendererCapabilities = includeRendererCapabilities;
    }

    public boolean isPipelineCacheEnabled() {
        return pipelineCacheEnabled;
    }

    public void setPipelineCacheEnabled(boolean pipelineCacheEnabled) {
        this.pipelineCacheEnabled = pipelineCacheEnabled;
    }

    public boolean isResourceValidation() {
        return resourceValidation;
    }

    public void setResourceValidation(boolean resourceValidation) {
        this.resourceValidation = resourceValidation;
    }

    public java.util.Map<String, Boolean> getExperimentalFlags() {
        if (experimentalFlags == null) {
            experimentalFlags = new java.util.LinkedHashMap<>();
        }
        return experimentalFlags;
    }

    public void setExperimentalFlags(java.util.Map<String, Boolean> experimentalFlags) {
        this.experimentalFlags = experimentalFlags != null
                ? new java.util.LinkedHashMap<>(experimentalFlags)
                : new java.util.LinkedHashMap<>();
    }

    /** @return whether the named experimental flag is enabled (all default OFF) */
    public boolean isExperimentalFlag(String flag) {
        return Boolean.TRUE.equals(getExperimentalFlags().get(flag));
    }

    /**
     * Validates the model, returning human-readable problems (empty when valid). GUI controls
     * must not write values that fail this check into the renderer.
     */
    public java.util.List<String> validate() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (compatibilityMode == null) {
            problems.add("compatibilityMode must not be null");
        }
        if (diagnosticVerbosity == null) {
            problems.add("diagnosticVerbosity must not be null");
        }
        if (debugRenderMode == null) {
            problems.add("debugRenderMode must not be null");
        }
        for (Map.Entry<String, Boolean> entry : getExperimentalFlags().entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                problems.add("experimental flag name must be non-blank");
            }
            if (entry.getValue() == null) {
                problems.add("experimental flag '" + entry.getKey() + "' must be true/false");
            }
        }
        return problems;
    }

    /**
     * Repairs a model parsed from an older or partial file: fills every missing entry with its
     * default and stamps the current schema version. Always call after deserialization.
     */
    public void repair() {
        if (compatibilityMode == null) {
            compatibilityMode = CompatibilityMode.AUTOMATIC;
        }
        if (diagnosticVerbosity == null) {
            diagnosticVerbosity = DiagnosticVerbosity.BASIC;
        }
        if (debugRenderMode == null) {
            debugRenderMode = DebugRenderMode.OFF;
        }
        getExperimentalFlags();
        schemaVersion = CURRENT_SCHEMA_VERSION;
    }
}
