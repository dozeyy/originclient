package com.vulkairis.rendering.exec;

import com.vulkairis.resource.RenderTargetFormat;

import java.util.List;

/**
 * Phase 7: configuration for a gbuffers geometry pass.
 *
 * <p>Lighting is shader-pack logic, but Vulkairis must provide the pass shape those shaders expect:
 * which color attachments (MRT) exist, the depth format, and whether the pass is opaque or
 * translucent. Opaque and translucent are separate states because blend/cull conventions differ.</p>
 *
 * @param colorFormats color attachment formats in MRT order (may be empty for a depth-only variant)
 * @param depthFormat  depth format, or {@code null} for a pass without depth
 * @param depthTest    whether depth testing is enabled
 * @param depthWrite   whether depth writes are enabled
 * @param blend        whether blending is enabled
 * @param cull         whether back-face culling is enabled
 * @param translucent  whether this is the translucent sub-pass
 */
public record GbufferPassConfig(
        List<RenderTargetFormat> colorFormats,
        RenderTargetFormat depthFormat,
        boolean depthTest,
        boolean depthWrite,
        boolean blend,
        boolean cull,
        boolean translucent) {

    public GbufferPassConfig {
        colorFormats = colorFormats == null ? List.of() : List.copyOf(colorFormats);
        for (RenderTargetFormat format : colorFormats) {
            if (format == null || format.isDepth()) {
                throw new IllegalArgumentException("gbuffer color attachments must be color formats");
            }
        }
        if (depthFormat != null && !depthFormat.isDepth()) {
            throw new IllegalArgumentException("gbuffer depth attachment must be a depth format");
        }
    }

    /** @return the number of MRT color attachments */
    public int colorAttachmentCount() {
        return colorFormats.size();
    }

    /** @return whether this pass uses more than one render target */
    public boolean isMrt() {
        return colorFormats.size() > 1;
    }

    /** @return whether this pass has a depth attachment */
    public boolean hasDepth() {
        return depthFormat != null;
    }

    /** @return the standard opaque terrain gbuffer shape (color + normals + material, depth, no blend) */
    public static GbufferPassConfig opaquePass() {
        return new GbufferPassConfig(
                List.of(RenderTargetFormat.RGBA16F, RenderTargetFormat.RGBA8, RenderTargetFormat.RGBA8),
                RenderTargetFormat.DEPTH32F,
                true, true, false, true, false);
    }

    /** @return the translucent terrain gbuffer shape (blended, no depth write, no cull) */
    public static GbufferPassConfig translucentPass() {
        return new GbufferPassConfig(
                List.of(RenderTargetFormat.RGBA16F, RenderTargetFormat.RGBA8, RenderTargetFormat.RGBA8),
                RenderTargetFormat.DEPTH32F,
                true, false, true, false, true);
    }
}
