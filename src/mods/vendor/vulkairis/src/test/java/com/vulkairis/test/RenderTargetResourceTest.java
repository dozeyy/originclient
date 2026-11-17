package com.vulkairis.test;

import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.resource.RenderTargetFormat;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.SamplerPolicy;
import com.vulkairis.resource.TextureResource;
import com.vulkairis.resource.TextureSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_DEPTH_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

/**
 * Device-free verification of the Phase 4 resource layer: format classification, spec validation,
 * layout tracking, producer/consumer roles, the standard target set, and resize lifecycle.
 */
public class RenderTargetResourceTest {

    @Test
    public void testFormatClassification() {
        Assertions.assertEquals(com.vulkairis.rendergraph.ResourceKind.COLOR_IMAGE,
                RenderTargetFormat.RGBA16F.kind());
        Assertions.assertEquals(com.vulkairis.rendergraph.ResourceKind.DEPTH_IMAGE,
                RenderTargetFormat.DEPTH32F.kind());
        Assertions.assertTrue(RenderTargetFormat.DEPTH32F.isDepth());
        Assertions.assertFalse(RenderTargetFormat.RGBA16F.isDepth());
        Assertions.assertEquals(VK_IMAGE_ASPECT_DEPTH_BIT,
                RenderTargetFormat.DEPTH32F.aspectMask() & VK_IMAGE_ASPECT_DEPTH_BIT);
        Assertions.assertEquals(VK_IMAGE_ASPECT_COLOR_BIT,
                RenderTargetFormat.RGBA16F.aspectMask() & VK_IMAGE_ASPECT_COLOR_BIT);
        Assertions.assertTrue(RenderTargetFormat.DEPTH32F.supportsCompareSampling());
        Assertions.assertNotEquals(0, RenderTargetFormat.RGBA16F.defaultUsage());
    }

    @Test
    public void testSamplerPolicyMapping() {
        Assertions.assertTrue(SamplerPolicy.SHADOW_COMPARE.isCompareEnabled());
        Assertions.assertFalse(SamplerPolicy.LINEAR_CLAMP.isCompareEnabled());
        Assertions.assertTrue(SamplerPolicy.LINEAR_REPEAT.isLinear());
        Assertions.assertFalse(SamplerPolicy.NEAREST_CLAMP.isLinear());
        Assertions.assertNotEquals(SamplerPolicy.LINEAR_REPEAT.addressMode(),
                SamplerPolicy.LINEAR_CLAMP.addressMode());
    }

    /** A color resource must not accept a depth format, and vice versa. */
    @Test
    public void testSpecRejectsKindMismatch() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> TextureSpec.of(
                RenderResource.color("bad"), RenderTargetFormat.DEPTH32F, 64, 64, SamplerPolicy.LINEAR_CLAMP));
    }

    @Test
    public void testSpecRejectsInvalidDimensions() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> TextureSpec.of(
                RenderResource.color("bad"), RenderTargetFormat.RGBA8, 0, 64, SamplerPolicy.LINEAR_CLAMP));
    }

    @Test
    public void testSpecSizeBudget() {
        TextureSpec spec = TextureSpec.of(
                RenderResource.color("c"), RenderTargetFormat.RGBA16F, 1920, 1080, SamplerPolicy.LINEAR_CLAMP);
        Assertions.assertEquals(1920L * 1080L * 8L, spec.sizeInBytes());
    }

    @Test
    public void testLayoutTracking() {
        TextureResource resource = new TextureResource(TextureSpec.of(
                RenderResource.color("c"), RenderTargetFormat.RGBA8, 16, 16, SamplerPolicy.LINEAR_CLAMP));

        Assertions.assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, resource.getCurrentLayout());
        Assertions.assertTrue(resource.transitionTo(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL));
        Assertions.assertEquals(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, resource.getCurrentLayout());
        Assertions.assertFalse(resource.transitionTo(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL),
                "transitioning to the current layout must be a no-op");
    }

    @Test
    public void testProducerConsumerRoles() {
        TextureResource resource = new TextureResource(TextureSpec.of(
                RenderResource.color("c"), RenderTargetFormat.RGBA8, 16, 16, SamplerPolicy.LINEAR_CLAMP));
        resource.markProducedBy("gbuffers");
        resource.addConsumer("composite");
        resource.addConsumer("final");

        Assertions.assertEquals("gbuffers", resource.getProducerPass());
        Assertions.assertTrue(resource.getConsumerPasses().containsAll(Set.of("composite", "final")));
    }

    @Test
    public void testStandardTargetSet() {
        RenderTargetRegistry registry = RenderTargetRegistry.standard(1920, 1080, 2048);
        Assertions.assertEquals(7, registry.size());
        Assertions.assertTrue(registry.get(RenderTargetRegistry.SCENE_COLOR).orElseThrow()
                .getFormat() == RenderTargetFormat.RGBA16F);
        Assertions.assertTrue(registry.get(RenderTargetRegistry.SCENE_DEPTH).orElseThrow()
                .getFormat() == RenderTargetFormat.DEPTH32F);
        Assertions.assertTrue(registry.get(RenderTargetRegistry.SHADOW_DEPTH).orElseThrow()
                .getSpec().sampler() == SamplerPolicy.SHADOW_COMPARE);
        Assertions.assertEquals(2048,
                registry.get(RenderTargetRegistry.SHADOW_DEPTH).orElseThrow().getWidth());
        Assertions.assertTrue(registry.get(RenderTargetRegistry.PING).orElseThrow()
                .getSpec().resource().transientResource());
    }

    @Test
    public void testResizeLeavesFixedShadowMapUntouched() {
        RenderTargetRegistry registry = RenderTargetRegistry.standard(1920, 1080, 2048);
        registry.resizeAllExcept(Set.of(RenderTargetRegistry.SHADOW_DEPTH, RenderTargetRegistry.SHADOW_COLOR),
                2560, 1440);

        TextureResource scene = registry.get(RenderTargetRegistry.SCENE_COLOR).orElseThrow();
        Assertions.assertEquals(2560, scene.getWidth());
        Assertions.assertEquals(1440, scene.getHeight());

        TextureResource shadow = registry.get(RenderTargetRegistry.SHADOW_DEPTH).orElseThrow();
        Assertions.assertEquals(2048, shadow.getWidth(), "shadow map must keep its fixed resolution");
    }

    @Test
    public void testResizeResetsLayout() {
        TextureResource resource = new TextureResource(TextureSpec.of(
                RenderResource.color("c"), RenderTargetFormat.RGBA8, 16, 16, SamplerPolicy.LINEAR_CLAMP));
        resource.transitionTo(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        resource.resize(32, 32);

        Assertions.assertEquals(32, resource.getWidth());
        Assertions.assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, resource.getCurrentLayout(),
                "recreated image backing starts UNDEFINED");
    }
}
