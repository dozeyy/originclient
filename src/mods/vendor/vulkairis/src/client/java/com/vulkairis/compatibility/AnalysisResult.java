package com.vulkairis.compatibility;

/**
 * Result of a shader or capability compatibility analysis.
 */
public record AnalysisResult(
        Status status,
        String shaderName,
        String reason,
        String details
) {
    public enum Status {
        PASS,
        UNSUPPORTED,
        ERROR
    }

    public static AnalysisResult pass(String shaderName) {
        return new AnalysisResult(Status.PASS, shaderName, "Passed compatibility checks", null);
    }

    public static AnalysisResult unsupported(String shaderName, String reason, String details) {
        return new AnalysisResult(Status.UNSUPPORTED, shaderName, reason, details);
    }

    public static AnalysisResult error(String shaderName, String reason, String details) {
        return new AnalysisResult(Status.ERROR, shaderName, reason, details);
    }

    public boolean isSuccess() {
        return status == Status.PASS;
    }
}
