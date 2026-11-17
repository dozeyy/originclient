package com.vulkairis.test;

import com.vulkairis.rendergraph.CompiledRenderGraph;
import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendergraph.RenderGraph;
import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.rendergraph.ResourceUsage;
import com.vulkairis.rendergraph.ShaderStage;
import com.vulkairis.rendering.sync.BarrierGenerator;
import com.vulkairis.rendering.sync.ImageLayoutMapper;
import com.vulkairis.rendering.sync.ResourceStateTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

/**
 * Device-free verification of the Phase 11 synchronization layer: usage-to-layout/stage/access
 * mapping, barrier generation from graph transitions, and the resource-state tracker.
 */
public class SynchronizationTest {

    private static CompiledRenderGraph twoPassGraph(RenderResource color, RenderResource depth) {
        RenderGraph graph = new RenderGraph();
        graph.addExternalResource(RenderResource.color("swapchain"));
        graph.addPass(PassNode.graphics("gbuffers")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .colorOutput(color)
                .depthOutput(depth)
                .build());
        graph.addPass(PassNode.graphics("composite")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .sampled(color)
                .colorOutput(RenderResource.color("swapchain"))
                .build());
        return graph.compile();
    }

    @Test
    public void testUsageToLayoutMapping() {
        Assertions.assertEquals(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                ImageLayoutMapper.layoutFor(ResourceUsage.COLOR_ATTACHMENT));
        Assertions.assertEquals(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                ImageLayoutMapper.layoutFor(ResourceUsage.DEPTH_ATTACHMENT));
        Assertions.assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                ImageLayoutMapper.layoutFor(ResourceUsage.SAMPLED));
        Assertions.assertEquals(VK_IMAGE_LAYOUT_GENERAL,
                ImageLayoutMapper.layoutFor(ResourceUsage.STORAGE));
        Assertions.assertEquals(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                ImageLayoutMapper.layoutFor(ResourceUsage.PRESENT));
    }

    @Test
    public void testStageAndAccessMasks() {
        Assertions.assertEquals(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                ImageLayoutMapper.stageMaskFor(ResourceUsage.COLOR_ATTACHMENT));
        Assertions.assertNotEquals(0,
                ImageLayoutMapper.accessMaskFor(ResourceUsage.COLOR_ATTACHMENT)
                        & VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        Assertions.assertNotEquals(0,
                ImageLayoutMapper.accessMaskFor(ResourceUsage.DEPTH_ATTACHMENT)
                        & VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        Assertions.assertNotEquals(0,
                ImageLayoutMapper.stageMaskFor(ResourceUsage.STORAGE) & VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    @Test
    public void testBarriersGeneratedForWriteThenSample() {
        RenderResource color = RenderResource.color("gbuffer_color");
        RenderResource depth = RenderResource.depth("gbuffer_depth");

        List<BarrierGenerator.PipelineBarrier> barriers =
                BarrierGenerator.generate(twoPassGraph(color, depth));

        BarrierGenerator.PipelineBarrier colorSample = barriers.stream()
                .filter(b -> b.resourceName().equals("gbuffer_color")
                        && b.toLayout() == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                .findFirst().orElseThrow();

        Assertions.assertEquals(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, colorSample.fromLayout());
        Assertions.assertNotEquals(0, colorSample.srcAccessMask() & VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        Assertions.assertNotEquals(0, colorSample.dstStageMask() & VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        Assertions.assertNotEquals(0, colorSample.dstAccessMask() & VK_ACCESS_SHADER_READ_BIT);
        Assertions.assertFalse(colorSample.initial());
        Assertions.assertEquals("composite", colorSample.passName());
    }

    @Test
    public void testInitialBarrierUsesUndefinedLayout() {
        RenderResource color = RenderResource.color("gbuffer_color");
        RenderResource depth = RenderResource.depth("gbuffer_depth");

        BarrierGenerator.PipelineBarrier initial = BarrierGenerator.generate(twoPassGraph(color, depth)).stream()
                .filter(b -> b.resourceName().equals("gbuffer_color") && b.initial())
                .findFirst().orElseThrow();

        Assertions.assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, initial.fromLayout());
        Assertions.assertEquals(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, initial.toLayout());
        Assertions.assertEquals("gbuffers", initial.passName());
    }

    @Test
    public void testResourceStateTrackerAppliesBarriers() {
        RenderResource color = RenderResource.color("gbuffer_color");
        RenderResource depth = RenderResource.depth("gbuffer_depth");

        List<BarrierGenerator.PipelineBarrier> barriers =
                BarrierGenerator.generate(twoPassGraph(color, depth));

        ResourceStateTracker tracker = new ResourceStateTracker();
        Assertions.assertEquals(VK_IMAGE_LAYOUT_UNDEFINED, tracker.getLayout("gbuffer_color"));
        tracker.applyAll(barriers);

        Assertions.assertEquals(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, tracker.getLayout("gbuffer_color"));
        Assertions.assertEquals(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, tracker.getLayout("gbuffer_depth"));
        Assertions.assertTrue(tracker.trackedCount() >= 2);
    }
}
