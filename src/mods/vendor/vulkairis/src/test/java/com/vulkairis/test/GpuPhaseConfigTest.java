package com.vulkairis.test;

import com.vulkairis.rendering.exec.CompositeChainPlanner;
import com.vulkairis.rendering.exec.GbufferPassConfig;
import com.vulkairis.rendering.exec.PassBinding;
import com.vulkairis.rendering.exec.PresentationTarget;
import com.vulkairis.rendering.exec.ResizeCoordinator;
import com.vulkairis.rendering.exec.ShadowPassConfig;
import com.vulkairis.rendering.exec.ShadowProjection;
import com.vulkairis.rendering.exec.ShadowRenderer;
import com.vulkairis.rendergraph.CompiledRenderGraph;
import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendergraph.RenderGraph;
import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.resource.RenderTargetFormat;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.SamplerPolicy;
import com.vulkairis.resource.TextureResource;
import com.vulkairis.resource.TextureSpec;
import com.vulkairis.rendering.state.VulkairisRenderState;
import com.vulkairis.rendering.sync.BarrierGenerator;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Device-free verification of the GPU-phase configuration and planning: shadow projection/config,
 * gbuffer pass shape, composite ping-pong planning, presentation target, resize lifecycle, and
 * pass-binding/barrier grouping.
 */
public class GpuPhaseConfigTest {

    @Test
    public void testShadowProjectionPlacesCameraAndMapsCenter() {
        ShadowProjection.Result result = ShadowProjection.build(
                new Vector3f(0, 1, 0), new Vector3f(0, 0, 0), 10.0f, 50.0f);

        // The camera sits at center + sunDirection * distance = (0, 50, 0); the center maps to
        // (0, 0, -distance) in view space.
        Vector4f center = new Vector4f(0, 0, 0, 1);
        result.view().transform(center);
        Assertions.assertEquals(0.0f, center.x, 1e-4);
        Assertions.assertEquals(0.0f, center.y, 1e-4);
        Assertions.assertEquals(-50.0f, center.z, 1e-4);
        Assertions.assertNotEquals(new Matrix4f(), result.viewProjection());
    }

    @Test
    public void testShadowConfigDefaultsAndValidation() {
        ShadowPassConfig config = ShadowPassConfig.irisDefault(2048);
        Assertions.assertEquals(2048, config.resolution());
        Assertions.assertTrue(config.depthFormat().isDepth());
        Assertions.assertTrue(config.depthBiasEnabled());
        Assertions.assertTrue(config.cullFrontFaces());

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ShadowPassConfig.irisDefault(2000));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ShadowPassConfig(1024, RenderTargetFormat.RGBA8,
                        0, 0, 0, 1, 100, 0, true));
    }

    @Test
    public void testShadowRendererActivatesAndRestoresProjection() {
        TextureResource shadowDepth = new TextureResource(TextureSpec.of(
                RenderResource.depth("shadowtex0"), RenderTargetFormat.DEPTH32F,
                2048, 2048, SamplerPolicy.SHADOW_COMPARE));
        ShadowRenderer renderer = new ShadowRenderer(shadowDepth, ShadowPassConfig.irisDefault(2048));

        VulkairisRenderState state = VulkairisRenderState.get();
        renderer.beginShadowFrame(new Vector3f(0, 1, 0), new Vector3f(0, 0, 0),
                new Matrix4f().perspective(1.0f, 1.0f, 0.1f, 100.0f));
        Assertions.assertTrue(state.isShadowProjectionActive());
        Assertions.assertEquals(renderer.getConfig(), ShadowPassConfig.irisDefault(2048));

        renderer.endShadowFrame();
        Assertions.assertFalse(state.isShadowProjectionActive());

        ShadowRenderer.setDebugVisualization(true);
        Assertions.assertTrue(ShadowRenderer.isDebugVisualization());
        ShadowRenderer.setDebugVisualization(false);
    }

    @Test
    public void testGbufferConfigOpaqueAndTranslucent() {
        GbufferPassConfig opaque = GbufferPassConfig.opaquePass();
        Assertions.assertEquals(3, opaque.colorAttachmentCount());
        Assertions.assertTrue(opaque.isMrt());
        Assertions.assertTrue(opaque.hasDepth());
        Assertions.assertFalse(opaque.blend());
        Assertions.assertFalse(opaque.translucent());

        GbufferPassConfig translucent = GbufferPassConfig.translucentPass();
        Assertions.assertTrue(translucent.blend());
        Assertions.assertFalse(translucent.depthWrite());
        Assertions.assertTrue(translucent.translucent());

        Assertions.assertThrows(IllegalArgumentException.class, () -> new GbufferPassConfig(
                List.of(RenderTargetFormat.DEPTH32F), RenderTargetFormat.DEPTH32F,
                true, true, false, true, false));
    }

    @Test
    public void testCompositeChainPlannerAlternatesPingPong() {
        List<CompositeChainPlanner.CompositeStep> steps = CompositeChainPlanner.plan(
                List.of("composite", "composite1", "final"),
                "colortex0", "composite_ping", "composite_pong", "final_output");

        Assertions.assertEquals(3, steps.size());
        Assertions.assertEquals("colortex0", steps.get(0).input().name());
        Assertions.assertEquals("composite_ping", steps.get(0).output().name());
        Assertions.assertEquals("composite_pong", steps.get(1).output().name());
        Assertions.assertEquals("final_output", steps.get(2).output().name());
        Assertions.assertTrue(steps.get(2).finalStep());
        Assertions.assertFalse(steps.get(0).finalStep());
    }

    @Test
    public void testCompositeChainBuildsCompilableGraph() {
        List<CompositeChainPlanner.CompositeStep> steps = CompositeChainPlanner.plan(
                List.of("composite", "composite1", "final"),
                "colortex0", "composite_ping", "composite_pong", "final_output");

        RenderGraph graph = CompositeChainPlanner.toRenderGraph(steps);
        CompiledRenderGraph compiled = graph.compile();

        Assertions.assertEquals(
                List.of("composite", "composite1", "final"),
                compiled.getExecutionOrder().stream().map(PassNode::name).toList());
        Assertions.assertTrue(compiled.getExternalResources().contains("colortex0"));
        Assertions.assertFalse(compiled.getExternalResources().contains("final_output"));
    }

    @Test
    public void testCompositeChainRejectsEmpty() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CompositeChainPlanner.plan(List.of(), "a", "b", "c", "d"));
    }

    @Test
    public void testResizeCoordinatorLeavesFixedTargets() {
        RenderTargetRegistry registry = RenderTargetRegistry.standard(100, 100, 64);
        ResizeCoordinator coordinator = new ResizeCoordinator(registry,
                Set.of(RenderTargetRegistry.SHADOW_DEPTH, RenderTargetRegistry.SHADOW_COLOR),
                100, 100);

        Assertions.assertTrue(coordinator.onResize(200, 150));
        Assertions.assertEquals(200, registry.get(RenderTargetRegistry.SCENE_COLOR).orElseThrow().getWidth());
        Assertions.assertEquals(150, registry.get(RenderTargetRegistry.SCENE_COLOR).orElseThrow().getHeight());
        Assertions.assertEquals(64, registry.get(RenderTargetRegistry.SHADOW_DEPTH).orElseThrow().getWidth());
        Assertions.assertFalse(coordinator.onResize(200, 150), "no-op resize must return false");
        Assertions.assertFalse(coordinator.onResize(0, 100), "invalid resize must be ignored");
    }

    @Test
    public void testPresentationTargetFallback() {
        PresentationTarget.Info info = PresentationTarget.current();
        Assertions.assertNotNull(info);
        Assertions.assertNotNull(info.name());
        // In a device-free test there is no swapchain; capture must not throw.
        PresentationTarget.capture();
    }

    @Test
    public void testPassBindingFactories() {
        PassBinding graphics = PassBinding.graphics("gbuffers", 11L, 12L, 13L, 1920, 1080, 36);
        Assertions.assertEquals(PassNode.Kind.GRAPHICS, graphics.kind());
        Assertions.assertEquals(36, graphics.vertexCount());
        Assertions.assertEquals(1920, graphics.viewportWidth());

        PassBinding compute = PassBinding.compute("cull", 21L, 22L, 23L, 4, 2, 1);
        Assertions.assertEquals(PassNode.Kind.COMPUTE, compute.kind());
        Assertions.assertEquals(4, compute.dispatchX());
        Assertions.assertEquals(1, compute.dispatchZ());
    }

    @Test
    public void testBarrierGroupingByPass() {
        List<CompositeChainPlanner.CompositeStep> steps = CompositeChainPlanner.plan(
                List.of("composite", "final"),
                "colortex0", "composite_ping", "composite_pong", "final_output");
        CompiledRenderGraph compiled = CompositeChainPlanner.toRenderGraph(steps).compile();
        List<BarrierGenerator.PipelineBarrier> barriers = BarrierGenerator.generate(compiled);

        Map<String, List<BarrierGenerator.PipelineBarrier>> grouped =
                com.vulkairis.rendering.exec.RenderGraphExecutor.groupBarriersByPass(barriers);

        Assertions.assertFalse(grouped.isEmpty());
        Assertions.assertTrue(grouped.keySet().contains("composite"));
        Assertions.assertTrue(grouped.keySet().contains("final"));
    }
}
