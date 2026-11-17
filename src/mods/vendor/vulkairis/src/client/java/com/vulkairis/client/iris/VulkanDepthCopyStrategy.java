package com.vulkairis.client.iris;

import com.vulkairis.diagnostics.DiagnosticManager;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;

public class VulkanDepthCopyStrategy implements DepthCopyStrategy {
    public static final VulkanDepthCopyStrategy INSTANCE = new VulkanDepthCopyStrategy();

    @Override
    public boolean needsDestFramebuffer() {
        return false;
    }

    @Override
    public void copy(GlFramebuffer src, int srcTexture, GlFramebuffer dst, int dstTexture, int width, int height) {
        // Phase 9: the GPU depth copy/resolve has no pass implementation yet (it lands with pass
        // execution). This is triage step #1 for the bright-outline regression. Loud by design:
        // a structured record is logged once (repeats deduplicated) instead of silently
        // producing wrong depth.
        DiagnosticManager.logStructured(DiagnosticManager.Category.RESOURCE,
                DiagnosticManager.Phase.TARGET,
                "Depth copy requested but not implemented",
                width + "x" + height + " depth resolve outstanding", null,
                null, null, null, -1, null,
                "Depth-dependent output is unreliable until the copy path lands; see triage");
    }
}
