package com.vulkairis.translation;

import java.nio.ByteBuffer;

/**
 * The honest outcome of one GLSL-to-SPIR-V compilation (Phase 4).
 *
 * <p>A compile result is pure data: status, diagnostics, source hash, stage, bytecode (present
 * only on success), the exact profile that ran, and timing. It is kept separate from Vulkan
 * object creation — a failed compilation never yields bytecode, and no valid
 * {@code VkShaderModule} may be registered from it.
 */
public record ShaderCompileResult(
        Status status,
        String shaderName,
        int stageGl,
        int shadercKind,
        String sourceHash,
        ByteBuffer spirv,
        String compilerLog,
        String profileDigest,
        long durationMs,
        boolean fatal) {

    /** Outcome classes. Only {@link #SUCCESS} carries usable bytecode. */
    public enum Status {
        /** Compiled, structurally validated bytecode. */
        SUCCESS,
        /** Stage rejected before compilation (tessellation/geometry/compute). */
        STAGE_REJECTED,
        /** Shaderc reported a compilation error. */
        COMPILE_ERROR,
        /** Bytecode produced but structural validation failed. */
        VALIDATION_FAILED,
        /** Module valid but reflection is incomplete and untrustworthy. */
        REFLECTION_INCOMPLETE
    }

    /** @return whether this result carries usable SPIR-V bytecode */
    public boolean succeeded() {
        return status == Status.SUCCESS && spirv != null && spirv.hasRemaining();
    }

    /** @return a success result; {@code spirv} must hold validated bytecode */
    public static ShaderCompileResult success(String shaderName, int stageGl, int shadercKind,
                                              String sourceHash, ByteBuffer spirv, String profileDigest,
                                              long durationMs) {
        return new ShaderCompileResult(Status.SUCCESS, shaderName, stageGl, shadercKind,
                sourceHash, spirv, "", profileDigest, durationMs, false);
    }

    /** @return a failure result; never carries bytecode */
    public static ShaderCompileResult failure(Status status, String shaderName, int stageGl,
                                              int shadercKind, String sourceHash, String compilerLog,
                                              String profileDigest, long durationMs, boolean fatal) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("failure() cannot report SUCCESS");
        }
        String log = compilerLog != null ? compilerLog : "";
        return new ShaderCompileResult(status, shaderName, stageGl, shadercKind,
                sourceHash, null, log, profileDigest, durationMs, fatal);
    }
}
