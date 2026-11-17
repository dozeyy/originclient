package com.vulkairis.plan;

import com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage;
import com.vulkairis.rendering.exec.PassGates;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;

import java.util.List;
import java.util.Objects;

/**
 * Phase 3: Immutable description of an executable pass in the pipeline.
 * Names its source program, fallback, render stage, required gates, and read/write targets.
 */
public record PassSpec(
        String passId,
        ProgramGroup group,
        VulkanModRenderStage stage,
        String sourceProgram,
        String fallbackProgram,
        List<String> readTargets,
        List<String> writeTargets,
        PassGates.Gate requiredGate,
        boolean hasDepthWrite,
        boolean hasDepthTest,
        String blendMode,
        int executionOrder
) {
    public PassSpec {
        Objects.requireNonNull(passId, "passId");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(sourceProgram, "sourceProgram");
        readTargets = (readTargets != null) ? List.copyOf(readTargets) : List.of();
        writeTargets = (writeTargets != null) ? List.copyOf(writeTargets) : List.of();
        blendMode = (blendMode != null) ? blendMode : "OPAQUE";
    }
}
