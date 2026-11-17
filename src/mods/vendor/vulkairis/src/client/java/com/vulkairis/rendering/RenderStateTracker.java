package com.vulkairis.rendering;

import com.vulkairis.vulkan.pipeline.PipelineKey;
import com.vulkairis.vulkan.pipeline.PipelineManager;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;

/**
 * Tracks OpenGL / Blaze3D rasterization, blend, depth, and scissor states
 * to build deterministic Vulkan pipeline keys.
 *
 * <p>Per-frame pack pass/target evidence additionally lands here as a diagnostics
 * mirror: the terrain door reports every handed-pass replacement via
 * {@link #observePackPass(String, String)}. The canonical store is
 * {@code VulkairisController} ({@code activePass}/{@code currentTarget}) plus the
 * {@code StageDiagnostics} pass/warn lines; this mirror exists so the plan's §6.5/§8
 * acceptance ("shows pack pass/target") reads against pipeline-key state as well.
 * Mirror writes never affect {@link #createPipelineKey}.</p>
 */
public class RenderStateTracker {
    private boolean blendEnabled = false;
    private int blendModeOrdinal = 0;
    private boolean depthTest = true;
    private boolean depthMask = true;
    private boolean cull = true;
    private int viewportWidth = 1920;
    private int viewportHeight = 1080;
    /** Last pack pass label reported by the terrain door, or "none". Diagnostics mirror. */
    private volatile String lastPackPass = "none";
    /** Last pack target label reported by the terrain door, or "none". Diagnostics mirror. */
    private volatile String lastPackTarget = "none";

    private static final RenderStateTracker INSTANCE = new RenderStateTracker();

    public static RenderStateTracker getInstance() {
        return INSTANCE;
    }

    public void setBlend(boolean enabled, int blendModeOrdinal) {
        this.blendEnabled = enabled;
        this.blendModeOrdinal = blendModeOrdinal;
    }

    public void setDepth(boolean test, boolean mask) {
        this.depthTest = test;
        this.depthMask = mask;
    }

    public void setCulling(boolean cull) {
        this.cull = cull;
    }

    public void setViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /**
     * Mirrors one pack pass/target replacement for per-frame evidence. Diagnostics-only:
     * never affects pipeline keys.
     *
     * @param passLabel effective pack pass label (e.g. {@code "gbuffers_terrain"})
     * @param targetLabel effective pack target label (e.g. {@code "colortex0"})
     */
    public void observePackPass(String passLabel, String targetLabel) {
        this.lastPackPass = (passLabel != null && !passLabel.isBlank()) ? passLabel : "none";
        this.lastPackTarget = (targetLabel != null && !targetLabel.isBlank()) ? targetLabel : "none";
    }

    /** @return the last pack pass label mirrored here, or "none" */
    public String getLastPackPass() {
        return lastPackPass;
    }

    /** @return the last pack target label mirrored here, or "none" */
    public String getLastPackTarget() {
        return lastPackTarget;
    }

    public PipelineKey createPipelineKey(long vertModule, long fragModule, String formatName) {
        boolean blend = blendEnabled;
        return new PipelineKey(
                vertModule,
                fragModule,
                formatName,
                // Render pass is resolved by the renderer when it selects a pass;
                // 0 means "unspecified" for state-only keys.
                0L,
                VulkanPipelineFactory.DEFAULT_TOPOLOGY,
                depthTest,
                depthMask,
                VulkanPipelineFactory.DEFAULT_DEPTH_COMPARE_OP,
                VulkanPipelineFactory.DEFAULT_FRONT_FACE,
                cull ? VulkanPipelineFactory.BACK_FACE_CULL_MODE : VulkanPipelineFactory.NO_CULL_MODE,
                blend ? blendModeOrdinal : -1,
                blend ? VulkanPipelineFactory.TRANSLUCENT_SRC_COLOR_FACTOR : 0,
                blend ? VulkanPipelineFactory.TRANSLUCENT_DST_COLOR_FACTOR : 0,
                blend ? VulkanPipelineFactory.TRANSLUCENT_COLOR_BLEND_OP : 0,
                blend ? VulkanPipelineFactory.TRANSLUCENT_SRC_ALPHA_FACTOR : 0,
                blend ? VulkanPipelineFactory.TRANSLUCENT_DST_ALPHA_FACTOR : 0,
                blend ? VulkanPipelineFactory.TRANSLUCENT_ALPHA_BLEND_OP : 0,
                VulkanPipelineFactory.DEFAULT_COLOR_WRITE_MASK,
                VulkanPipelineFactory.DEFAULT_SAMPLE_COUNT,
                0L,
                "",
                (depthTest ? 1 : 0) | (depthMask ? 2 : 0) | (cull ? 4 : 0) | (blend ? 8 : 0)
        );
    }

    public boolean isBlendEnabled() {
        return blendEnabled;
    }

    public boolean isDepthTest() {
        return depthTest;
    }

    public boolean isDepthMask() {
        return depthMask;
    }

    public boolean isCull() {
        return cull;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }
}
