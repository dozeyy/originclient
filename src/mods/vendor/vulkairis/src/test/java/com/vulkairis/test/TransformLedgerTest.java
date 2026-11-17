package com.vulkairis.test;

import com.vulkairis.rendering.TransformLedger;
import com.vulkairis.resource.ColorSpacePolicy;
import com.vulkairis.resource.RenderTargetFormat;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

import java.util.List;

/**
 * Phase 9: the transform ledger holds — one Y flip, one depth remap, mirrored winding,
 * explicit blend and single tone-map/gamma ownership.
 */
public class TransformLedgerTest {

    @Test
    public void testDepthRangeMapping() {
        Assertions.assertEquals(0.0f, TransformLedger.ndcDepthToVulkan(-1.0f), 1e-6f);
        Assertions.assertEquals(0.5f, TransformLedger.ndcDepthToVulkan(0.0f), 1e-6f);
        Assertions.assertEquals(1.0f, TransformLedger.ndcDepthToVulkan(1.0f), 1e-6f);
        Assertions.assertEquals(1.0f, TransformLedger.VULKAN_CLEAR_DEPTH,
                "Vulkan clears depth to the far plane");
    }

    @Test
    public void testFrontFaceMirrorsForYFlip() {
        Assertions.assertEquals(VK10.VK_FRONT_FACE_CLOCKWISE,
                TransformLedger.vulkanFrontFaceFor(TransformLedger.GL_CCW));
        Assertions.assertEquals(VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE,
                TransformLedger.vulkanFrontFaceFor(TransformLedger.GL_CW));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> TransformLedger.vulkanFrontFaceFor(0x1234),
                "unknown faces are rejected, never guessed");
    }

    @Test
    public void testSingleFlipConfigured() {
        Assertions.assertTrue(TransformLedger.isSingleFlipConfigured(),
                "Y must flip exactly once (compiler) and never in the correction matrix");
    }

    @Test
    public void testRawPipelineDefaultMirrorsWinding() {
        Assertions.assertEquals(VK10.VK_FRONT_FACE_CLOCKWISE,
                VulkanPipelineFactory.DEFAULT_FRONT_FACE,
                "culling pipelines must mirror GL winding under invert_y");
    }

    @Test
    public void testBlendIsStraightAlpha() {
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_SRC_ALPHA,
                VulkanPipelineFactory.TRANSLUCENT_SRC_COLOR_FACTOR);
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VulkanPipelineFactory.TRANSLUCENT_DST_COLOR_FACTOR);
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_ONE,
                VulkanPipelineFactory.TRANSLUCENT_SRC_ALPHA_FACTOR);
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VulkanPipelineFactory.TRANSLUCENT_DST_ALPHA_FACTOR);
        Assertions.assertEquals(VK10.VK_BLEND_OP_ADD,
                VulkanPipelineFactory.TRANSLUCENT_COLOR_BLEND_OP);
        Assertions.assertEquals(VK10.VK_BLEND_OP_ADD,
                VulkanPipelineFactory.TRANSLUCENT_ALPHA_BLEND_OP);
    }

    @Test
    public void testColorOwnershipNeverDoubles() {
        Assertions.assertTrue(ColorSpacePolicy.shouldApplyToneMapping(false, true),
                "HDR scene without pack tone mapping: bridge tone-maps");
        Assertions.assertFalse(ColorSpacePolicy.shouldApplyToneMapping(true, true),
                "pack-owned tone mapping is never applied twice");
        Assertions.assertFalse(ColorSpacePolicy.shouldApplyToneMapping(false, false),
                "LDR scenes need no tone mapping");
        Assertions.assertTrue(ColorSpacePolicy.shouldApplyPresentationGamma(false));
        Assertions.assertFalse(ColorSpacePolicy.shouldApplyPresentationGamma(true),
                "pack-owned gamma is never applied twice");
        Assertions.assertEquals(ColorSpacePolicy.Encoding.HDR_LINEAR,
                ColorSpacePolicy.sceneEncoding(RenderTargetFormat.RGBA16F));
        Assertions.assertEquals(ColorSpacePolicy.Encoding.SRGB,
                ColorSpacePolicy.presentationEncoding());
    }

    @Test
    public void testBrightOutlineTriageOrder() {
        Assertions.assertEquals(
                List.of("depth-copy-compare",
                        "target-alignment-resolution",
                        "normal-attachments",
                        "blend-alpha",
                        "final-composite-color-space"),
                TransformLedger.brightOutlineTriage());
    }

    @Test
    public void testLedgerDescribes() {
        Assertions.assertFalse(TransformLedger.describe().isBlank());
    }
}
