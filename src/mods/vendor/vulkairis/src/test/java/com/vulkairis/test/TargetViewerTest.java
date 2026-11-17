package com.vulkairis.test;

import com.vulkairis.resource.RenderTargetFormat;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.SamplerPolicy;
import com.vulkairis.resource.TargetViewer;
import com.vulkairis.resource.TextureResource;
import com.vulkairis.resource.TextureSpec;
import com.vulkairis.rendergraph.RenderResource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 8: the diagnostic target viewer reports dimensions, formats, layouts, backing and
 * clear state; targets track first-clear obligations across resize and destroy.
 */
public class TargetViewerTest {

    @Test
    public void testStandardSetDescribes() {
        RenderTargetRegistry registry = RenderTargetRegistry.standard(1920, 1080, 1024);
        List<String> lines = TargetViewer.describe(registry);
        Assertions.assertEquals(registry.size() + 1, lines.size(),
                "one line per target plus a summary");
        String joined = String.join("\n", lines);
        Assertions.assertTrue(joined.contains("colortex0"), joined);
        Assertions.assertTrue(joined.contains("1920x1080"), joined);
        Assertions.assertTrue(joined.contains("depthtex0"), joined);
        Assertions.assertTrue(joined.contains("shadowtex0"), joined);
        Assertions.assertTrue(joined.contains("1024x1024"), "shadow maps keep square resolution");
    }

    @Test
    public void testEmptyRegistryDescribes() {
        Assertions.assertEquals(List.of("no render targets registered"),
                TargetViewer.describe(new RenderTargetRegistry()));
        Assertions.assertEquals("targets: none", TargetViewer.summary(new RenderTargetRegistry()));
        Assertions.assertEquals(List.of("no render targets registered"),
                TargetViewer.describe(null));
    }

    @Test
    public void testSummaryCountsPendingClears() {
        RenderTargetRegistry registry = RenderTargetRegistry.standard(1280, 720, 512);
        String summary = TargetViewer.summary(registry);
        Assertions.assertTrue(summary.contains("1280x720"), summary);
        Assertions.assertTrue(summary.contains("needing clear"), summary);
    }

    @Test
    public void testClearTrackingAcrossLifecycle() {
        TextureResource target = new TextureResource(TextureSpec.of(
                RenderResource.color("probe"), RenderTargetFormat.RGBA8,
                64, 64, SamplerPolicy.NEAREST_CLAMP));
        Assertions.assertFalse(target.isCleared());
        Assertions.assertFalse(target.needsClear(),
                "no backing yet, so no clear is owed");

        target.markCleared();
        Assertions.assertTrue(target.isCleared());

        target.resize(128, 128);
        Assertions.assertFalse(target.isCleared(),
                "a fresh backing must be cleared before its first read");
        Assertions.assertEquals(128, target.getWidth());

        target.markCleared();
        target.destroy();
        Assertions.assertFalse(target.isCleared(), "destroy resets clear state");
        Assertions.assertFalse(target.needsClear());
    }

    @Test
    public void testFormatConversionHasNoSilentDowncast() {
        Assertions.assertEquals(RenderTargetFormat.RGBA16F,
                RenderTargetFormat.fromGlInternalFormat(34842).orElseThrow());
        Assertions.assertEquals(RenderTargetFormat.DEPTH32F,
                RenderTargetFormat.fromGlInternalFormat(36012).orElseThrow());
        Assertions.assertEquals(RenderTargetFormat.DEPTH24_STENCIL8,
                RenderTargetFormat.fromGlInternalFormat(35056).orElseThrow());
        Assertions.assertTrue(RenderTargetFormat.fromGlInternalFormat(6407).isEmpty(),
                "GL_RGB has no exact target and must not downcast");
        Assertions.assertTrue(RenderTargetFormat.fromGlInternalFormat(33190).isEmpty(),
                "24-bit stencil-less depth must not gain a stencil plane silently");
        Assertions.assertTrue(RenderTargetFormat.fromGlInternalFormat(36013).isEmpty(),
                "32-bit depth+stencil has no exact target and must stay explicit");
        Assertions.assertTrue(RenderTargetFormat.fromGlInternalFormat(0).isEmpty());
    }
}
