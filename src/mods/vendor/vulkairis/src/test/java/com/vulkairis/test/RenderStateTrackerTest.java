package com.vulkairis.test;

import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.vulkairis.rendering.RenderStateTracker;
import com.vulkairis.vulkan.pipeline.PipelineKey;
import com.vulkairis.vulkan.pipeline.VertexInputDescription;
import com.vulkairis.vulkan.pipeline.VulkanPipelineFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VK10;

public class RenderStateTrackerTest {

    @Test
    public void testPipelineKeyGeneration() {
        RenderStateTracker tracker = new RenderStateTracker();

        tracker.setBlend(true, 1);
        tracker.setDepth(true, true);
        tracker.setCulling(true);
        PipelineKey key1 = tracker.createPipelineKey(100L, 200L, "POSITION_COLOR");

        tracker.setBlend(false, 0);
        PipelineKey key2 = tracker.createPipelineKey(100L, 200L, "POSITION_COLOR");

        Assertions.assertNotEquals(key1, key2, "Keys with different blend states must not be equal");
        Assertions.assertEquals(100L, key1.vertShaderModule());
        Assertions.assertEquals(200L, key1.fragShaderModule());
        Assertions.assertEquals("POSITION_COLOR", key1.vertexFormatName());
        Assertions.assertEquals(1, key1.blendModeOrdinal());
        Assertions.assertEquals(-1, key2.blendModeOrdinal(), "A disabled blend must not alias an enabled blend mode");
    }

    @Test
    public void testViewportTracking() {
        RenderStateTracker tracker = new RenderStateTracker();
        tracker.setViewport(2560, 1440);
        Assertions.assertEquals(2560, tracker.getViewportWidth());
        Assertions.assertEquals(1440, tracker.getViewportHeight());
    }

    /**
     * SHORT/USHORT vertex elements (Minecraft overlay UV1 and lightmap UV2) must
     * map to integer formats (SINT/UINT). Shaders consume them as integer vectors (ivec2).
     * Mapping them to SNORM/UNORM produces float values that truncate to 0 in ivec2 inputs,
     * causing text and lightmap rendering to fail.
     */
    @Test
    public void testShortAndUshortElementMapping() {
        // Minecraft 1.21.1: UV1 and UV2 are both SHORT x2.
        Assertions.assertEquals(VK10.VK_FORMAT_R16G16_SINT,
                VertexInputDescription.mapElementToVkFormat(VertexFormatElement.UV1));
        Assertions.assertEquals(VK10.VK_FORMAT_R16G16_SINT,
                VertexInputDescription.mapElementToVkFormat(VertexFormatElement.UV2));

        VertexFormatElement ushort2 = normalizedElement(VertexFormatElement.Type.USHORT, 2);
        VertexFormatElement ushort4 = normalizedElement(VertexFormatElement.Type.USHORT, 4);
        Assertions.assertEquals(VK10.VK_FORMAT_R16G16_UINT,
                VertexInputDescription.mapElementToVkFormat(ushort2));
        Assertions.assertEquals(VK10.VK_FORMAT_R16G16B16A16_UINT,
                VertexInputDescription.mapElementToVkFormat(ushort4));

        VertexFormatElement short4 = normalizedElement(VertexFormatElement.Type.SHORT, 4);
        Assertions.assertEquals(VK10.VK_FORMAT_R16G16B16A16_SINT,
                VertexInputDescription.mapElementToVkFormat(short4));
    }

    /** BYTE/UBYTE mapping must honor the element component count, not always assume RGBA. */
    @Test
    public void testByteElementCountMapping() {
        Assertions.assertEquals(VK10.VK_FORMAT_R8G8_SNORM,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.BYTE, 2)));
        Assertions.assertEquals(VK10.VK_FORMAT_R8G8B8_SNORM,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.BYTE, 3)));
        Assertions.assertEquals(VK10.VK_FORMAT_R8G8B8A8_SNORM,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.BYTE, 4)));

        Assertions.assertEquals(VK10.VK_FORMAT_R8_UNORM,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.UBYTE, 1)));
        Assertions.assertEquals(VK10.VK_FORMAT_R8G8B8_UNORM,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.UBYTE, 3)));
        Assertions.assertEquals(VK10.VK_FORMAT_R8G8B8A8_UNORM,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.UBYTE, 4)));

        // FLOAT/INT/UINT keep their native (non-normalized) formats.
        Assertions.assertEquals(VK10.VK_FORMAT_R32G32B32_SFLOAT,
                VertexInputDescription.mapElementToVkFormat(VertexFormatElement.POSITION));
        Assertions.assertEquals(VK10.VK_FORMAT_R32G32_SINT,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.INT, 2)));
        Assertions.assertEquals(VK10.VK_FORMAT_R32_UINT,
                VertexInputDescription.mapElementToVkFormat(normalizedElement(VertexFormatElement.Type.UINT, 1)));
    }

    /**
     * Minecraft's translucent pass expects destination alpha to accumulate:
     * alpha = ONE, ONE_MINUS_SRC_ALPHA. ONE, ZERO corrupts destination alpha and
     * causes bright halos, while RGB keeps standard alpha blending.
     */
    @Test
    public void testTranslucentBlendAlphaFactors() {
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_ONE,
                VulkanPipelineFactory.TRANSLUCENT_SRC_ALPHA_FACTOR,
                "Translucent source alpha factor must be ONE");
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VulkanPipelineFactory.TRANSLUCENT_DST_ALPHA_FACTOR,
                "Translucent destination alpha factor must be ONE_MINUS_SRC_ALPHA");

        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_SRC_ALPHA,
                VulkanPipelineFactory.TRANSLUCENT_SRC_COLOR_FACTOR,
                "Translucent RGB source factor must remain SRC_ALPHA");
        Assertions.assertEquals(VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA,
                VulkanPipelineFactory.TRANSLUCENT_DST_COLOR_FACTOR,
                "Translucent RGB destination factor must remain ONE_MINUS_SRC_ALPHA");
    }

    /** The pipeline cache key must distinguish render passes and depth compare ops. */
    @Test
    public void testPipelineKeyIncludesRenderPassAndDepthCompare() {
        VulkanPipelineFactory.PipelineParams base = new VulkanPipelineFactory.PipelineParams(
                1L, 2L, 3L, 0xAAAL, null, true, true, true, false, 0L);
        PipelineKey baseKey = VulkanPipelineFactory.buildPipelineKey(base);

        VulkanPipelineFactory.PipelineParams otherPass = new VulkanPipelineFactory.PipelineParams(
                1L, 2L, 3L, 0xBBBL, null, true, true, true, false, 0L);
        PipelineKey otherPassKey = VulkanPipelineFactory.buildPipelineKey(otherPass);
        Assertions.assertNotEquals(baseKey, otherPassKey,
                "Different render passes must not alias the same cached pipeline");

        VulkanPipelineFactory.PipelineParams otherDepth = new VulkanPipelineFactory.PipelineParams(
                1L, 2L, 3L, 0xAAAL, null, true, true, true, false, 0L,
                VulkanPipelineFactory.DEFAULT_TOPOLOGY, VK10.VK_COMPARE_OP_LESS,
                VulkanPipelineFactory.DEFAULT_FRONT_FACE, VulkanPipelineFactory.DEFAULT_SAMPLE_COUNT,
                false, 0.0f, 0.0f, 0.0f);
        PipelineKey otherDepthKey = VulkanPipelineFactory.buildPipelineKey(otherDepth);
        Assertions.assertNotEquals(baseKey, otherDepthKey,
                "Different depth compare ops must not alias the same cached pipeline");

        Assertions.assertEquals(0xAAAL, baseKey.renderPass());
        Assertions.assertEquals(VK10.VK_COMPARE_OP_LESS_OR_EQUAL, baseKey.depthCompareOp());
    }

    private static VertexFormatElement normalizedElement(VertexFormatElement.Type type, int count) {
        // id 31 is within the vanilla BY_ID table (32 entries) and is unused by 1.21.1.
        return new VertexFormatElement(31, 0, type, VertexFormatElement.Usage.GENERIC, count);
    }
}
