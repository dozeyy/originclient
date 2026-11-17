package com.vulkairis.test;

import com.vulkairis.resource.RenderTargetFormat;
import com.vulkairis.resource.SamplerPolicy;
import com.vulkairis.resource.TargetDescriptor;
import com.vulkairis.resource.TextureSpec;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase 8: target descriptors carry the full plan contract (identity, scaling, format,
 * samples, role, load/store, lifetime, owning pass, clear values) and validate eagerly.
 */
public class TargetDescriptorTest {

    @Test
    public void testColorAndDepthFactories() {
        TargetDescriptor color = TargetDescriptor.color("colortex0", RenderTargetFormat.RGBA16F);
        Assertions.assertEquals(TargetDescriptor.Role.COLOR, color.role());
        Assertions.assertEquals(1.0f, color.widthScale());
        Assertions.assertEquals(TargetDescriptor.LoadOp.CLEAR, color.loadOp());
        Assertions.assertEquals(TargetDescriptor.StoreOp.STORE, color.storeOp());
        Assertions.assertTrue(color.sampled());
        Assertions.assertFalse(color.transientTarget());

        TargetDescriptor depth = TargetDescriptor.depth("depthtex0", RenderTargetFormat.DEPTH32F);
        Assertions.assertEquals(TargetDescriptor.Role.DEPTH, depth.role());
    }

    @Test
    public void testValidationRejectsBadDescriptors() {
        TextureSpec clamped = TargetDescriptor.color("x", RenderTargetFormat.RGBA16F)
                .toSpec(0, 0, SamplerPolicy.LINEAR_CLAMP);
        Assertions.assertEquals(1, clamped.width());
        Assertions.assertEquals(1, clamped.height(),
                "degenerate base resolutions clamp to the 1x1 minimum, never to zero");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TargetDescriptor("x", 0.0f, 1.0f, RenderTargetFormat.RGBA8, 1,
                        TargetDescriptor.Role.COLOR, TargetDescriptor.LoadOp.LOAD,
                        TargetDescriptor.StoreOp.STORE, true, false, "", null, 1.0f),
                "non-positive scale must fail");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TargetDescriptor("x", 1.0f, 1.0f, RenderTargetFormat.RGBA8, 3,
                        TargetDescriptor.Role.COLOR, TargetDescriptor.LoadOp.LOAD,
                        TargetDescriptor.StoreOp.STORE, true, false, "", null, 1.0f),
                "non-power-of-two samples must fail");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TargetDescriptor("x", 1.0f, 1.0f, RenderTargetFormat.RGBA8, 1,
                        TargetDescriptor.Role.DEPTH, TargetDescriptor.LoadOp.LOAD,
                        TargetDescriptor.StoreOp.STORE, true, false, "", null, 1.0f),
                "depth role with a color format must fail");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TargetDescriptor("x", 1.0f, 1.0f, RenderTargetFormat.DEPTH32F, 1,
                        TargetDescriptor.Role.COLOR, TargetDescriptor.LoadOp.LOAD,
                        TargetDescriptor.StoreOp.STORE, true, false, "", null, 1.0f),
                "color role with a depth format must fail");
    }

    @Test
    public void testSizeScalingResolves() {
        TargetDescriptor half = new TargetDescriptor("colortex1", 0.5f, 0.5f,
                RenderTargetFormat.RGBA16F, 1, TargetDescriptor.Role.COLOR,
                TargetDescriptor.LoadOp.CLEAR, TargetDescriptor.StoreOp.STORE,
                true, true, "composite", null, 1.0f);
        Assertions.assertEquals(960, half.resolveWidth(1920));
        Assertions.assertEquals(540, half.resolveHeight(1080));
        Assertions.assertTrue(half.transientTarget());
        Assertions.assertEquals("composite", half.owningPass());
    }

    @Test
    public void testToSpecBridgesToAllocation() {
        TargetDescriptor depth = TargetDescriptor.depth("depthtex0", RenderTargetFormat.DEPTH32F);
        TextureSpec spec = depth.toSpec(1920, 1080, SamplerPolicy.NEAREST_CLAMP);
        Assertions.assertEquals(1920, spec.width());
        Assertions.assertEquals(1080, spec.height());
        Assertions.assertEquals(RenderTargetFormat.DEPTH32F, spec.format());
        Assertions.assertTrue(spec.isDepth());

        TargetDescriptor ping = new TargetDescriptor("composite_ping", 1.0f, 1.0f,
                RenderTargetFormat.RGBA16F, 1, TargetDescriptor.Role.COLOR,
                TargetDescriptor.LoadOp.DONT_CARE, TargetDescriptor.StoreOp.DONT_CARE,
                true, true, "composite", null, 1.0f);
        TextureSpec pingSpec = ping.toSpec(1920, 1080, SamplerPolicy.LINEAR_CLAMP);
        Assertions.assertTrue(pingSpec.resource().transientResource(),
                "transient descriptors stay transient into allocation");
    }

    @Test
    public void testClearColorDefensiveCopy() {
        float[] clear = {1.0f, 0.0f, 0.0f, 1.0f};
        TargetDescriptor descriptor = TargetDescriptor.color("colortex0", RenderTargetFormat.RGBA8);
        descriptor = new TargetDescriptor(descriptor.logicalId(), descriptor.widthScale(),
                descriptor.heightScale(), descriptor.format(), descriptor.samples(),
                descriptor.role(), descriptor.loadOp(), descriptor.storeOp(), descriptor.sampled(),
                descriptor.transientTarget(), descriptor.owningPass(), clear, descriptor.clearDepth());
        clear[0] = 0.0f;
        Assertions.assertEquals(1.0f, descriptor.clearColor()[0],
                "clear values must be copied, not aliased");
    }
}
