package com.vulkairis.plan;

import com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;

import java.util.Objects;

/**
 * Phase 3: Resolution state of a shader program within the pack execution plan.
 */
public record ProgramResolution(
        String programName,
        String effectiveProgramName,
        ProgramGroup group,
        VulkanModRenderStage renderStage,
        ResolutionStatus status,
        String reason
) {
    public enum ResolutionStatus {
        RESOLVED,
        FALLBACK_APPLIED,
        DISABLED,
        UNSUPPORTED
    }

    public ProgramResolution {
        Objects.requireNonNull(programName, "programName");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(renderStage, "renderStage");
        Objects.requireNonNull(status, "status");
        effectiveProgramName = (effectiveProgramName != null) ? effectiveProgramName : programName;
        reason = (reason != null) ? reason : "";
    }

    public boolean isExecutable() {
        return status == ResolutionStatus.RESOLVED || status == ResolutionStatus.FALLBACK_APPLIED;
    }
}
