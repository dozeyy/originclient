package com.vulkairis.test;

import com.vulkairis.rendergraph.CompiledRenderGraph;
import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendergraph.RenderGraph;
import com.vulkairis.rendergraph.RenderGraphException;
import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.rendergraph.ResourceTransition;
import com.vulkairis.rendergraph.ResourceUsage;
import com.vulkairis.rendergraph.ShaderStage;
import com.vulkairis.rendering.exec.CompositeChainPlanner;
import com.vulkairis.resource.RenderTargetRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Device-free verification of the Phase 3 {@link RenderGraph}: dependency-ordered compilation, cycle
 * rejection, MRT, depth-only and compute passes, ping-pong chaining, resource lifetimes and usage
 * transitions. No GPU or shader pack is required.
 */
public class RenderGraphTest {

    private static PassNode graphicsPass(String name, RenderResource reads, RenderResource writes) {
        PassNode.Builder builder = PassNode.graphics(name).stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT);
        if (reads != null) {
            builder.sampled(reads);
        }
        if (writes != null) {
            builder.colorOutput(writes);
        }
        return builder.build();
    }

    /** A three-pass post chain must compile into producer-before-consumer order. */
    @Test
    public void testLinearChainResolvesInDependencyOrder() {
        RenderResource scene = RenderResource.color("scene_color");
        RenderResource gbuffer = RenderResource.color("gbuffer_color");
        RenderResource depth = RenderResource.depth("gbuffer_depth");
        RenderResource composite = RenderResource.color("composite_out");
        RenderResource swapchain = RenderResource.color("swapchain");

        RenderGraph graph = new RenderGraph();
        graph.addExternalResource(scene);
        graph.addExternalResource(swapchain);
        graph.addPass(PassNode.graphics("final")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .sampled(composite)
                .colorOutput(swapchain)
                .build());
        graph.addPass(PassNode.graphics("gbuffers")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .colorOutput(gbuffer)
                .depthOutput(depth)
                .build());
        graph.addPass(PassNode.graphics("composite")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .sampled(gbuffer)
                .colorOutput(composite)
                .build());

        CompiledRenderGraph compiled = graph.compile();
        Assertions.assertEquals(
                List.of("gbuffers", "composite", "final"),
                compiled.getExecutionOrder().stream().map(PassNode::name).toList());
        Assertions.assertTrue(compiled.getDependencies("composite").contains("gbuffers"));
        Assertions.assertTrue(compiled.getDependencies("final").contains("composite"));
    }

    /** A read/write dependency cycle must be rejected with a diagnostic naming both passes. */
    @Test
    public void testCycleIsRejectedWithPath() {
        RenderResource x = RenderResource.color("x");
        RenderResource y = RenderResource.color("y");

        RenderGraph graph = new RenderGraph();
        graph.addPass(PassNode.graphics("a")
                .stages(ShaderStage.FRAGMENT).sampled(y).colorOutput(x).build());
        graph.addPass(PassNode.graphics("b")
                .stages(ShaderStage.FRAGMENT).sampled(x).colorOutput(y).build());

        RenderGraphException exception = Assertions.assertThrows(RenderGraphException.class, graph::compile);
        Assertions.assertTrue(exception.getMessage().toLowerCase().contains("cycle"), exception.getMessage());
        Assertions.assertTrue(exception.getMessage().contains("a"), exception.getMessage());
        Assertions.assertTrue(exception.getMessage().contains("b"), exception.getMessage());
    }

    /** Multiple color-attachment writes are valid MRT. */
    @Test
    public void testMrtPassDeclaresMultipleColorOutputs() {
        RenderResource c0 = RenderResource.color("c0");
        RenderResource c1 = RenderResource.color("c1");
        RenderResource c2 = RenderResource.color("c2");

        PassNode pass = PassNode.graphics("gbuffers")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .colorOutput(c0).colorOutput(c1).colorOutput(c2)
                .build();

        Assertions.assertEquals(3, pass.colorOutputs().size());
        Assertions.assertFalse(pass.isDepthOnly());

        RenderGraph graph = new RenderGraph();
        graph.addPass(pass);
        Assertions.assertEquals(3, graph.compile().getLifetimes().size());
    }

    /** A shadow pass is a graphics pass with only a depth attachment. */
    @Test
    public void testDepthOnlyPassHasNoColorOutputs() {
        RenderResource shadowDepth = RenderResource.depth("shadow_depth");
        PassNode shadow = PassNode.graphics("shadow")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .depthOutput(shadowDepth)
                .build();

        Assertions.assertTrue(shadow.isDepthOnly());
        Assertions.assertTrue(shadow.colorOutputs().isEmpty());
        Assertions.assertTrue(shadow.depthOutput().isPresent());

        RenderGraph graph = new RenderGraph();
        graph.addPass(shadow);
        Assertions.assertEquals(List.of("shadow"),
                graph.compile().getExecutionOrder().stream().map(PassNode::name).toList());
    }

    /** A ping-pong composite chain must preserve order and record the attachment -> sample change. */
    @Test
    public void testPingPongCompositeChain() {
        RenderResource scene = RenderResource.color("scene_color");
        RenderResource ping = RenderResource.transientColor("ping");
        RenderResource pong = RenderResource.transientColor("pong");
        RenderResource swapchain = RenderResource.color("swapchain");

        RenderGraph graph = new RenderGraph();
        graph.addExternalResource(scene);
        graph.addExternalResource(swapchain);
        graph.addPass(PassNode.graphics("composite0")
                .stages(ShaderStage.FRAGMENT).sampled(scene).colorOutput(ping).build());
        graph.addPass(PassNode.graphics("composite1")
                .stages(ShaderStage.FRAGMENT).sampled(ping).colorOutput(pong).build());
        graph.addPass(PassNode.graphics("final")
                .stages(ShaderStage.FRAGMENT).sampled(pong).colorOutput(swapchain).build());

        CompiledRenderGraph compiled = graph.compile();
        Assertions.assertEquals(
                List.of("composite0", "composite1", "final"),
                compiled.getExecutionOrder().stream().map(PassNode::name).toList());

        boolean pingSampledAfterWrite = false;
        for (ResourceTransition transition : compiled.getTransitions()) {
            if (transition.resource().name().equals("ping")
                    && transition.toUsage() == ResourceUsage.SAMPLED
                    && transition.fromUsage().orElse(null) == ResourceUsage.COLOR_ATTACHMENT) {
                pingSampledAfterWrite = true;
            }
        }
        Assertions.assertTrue(pingSampledAfterWrite,
                "ping must transition COLOR_ATTACHMENT -> SAMPLED between composite0 and composite1");
    }

    /** A diamond dependency must order both branches before the join and the join last. */
    @Test
    public void testDiamondDependencyOrdersBeforeJoin() {
        RenderResource x = RenderResource.color("x");
        RenderResource b = RenderResource.color("b");
        RenderResource c = RenderResource.color("c");
        RenderResource out = RenderResource.color("out");

        RenderGraph graph = new RenderGraph();
        graph.addPass(PassNode.graphics("join")
                .stages(ShaderStage.FRAGMENT).sampled(b).sampled(c).colorOutput(out).build());
        graph.addPass(PassNode.graphics("branchB")
                .stages(ShaderStage.FRAGMENT).sampled(x).colorOutput(b).build());
        graph.addPass(PassNode.graphics("branchC")
                .stages(ShaderStage.FRAGMENT).sampled(x).colorOutput(c).build());
        graph.addPass(PassNode.graphics("source")
                .stages(ShaderStage.FRAGMENT).colorOutput(x).build());

        CompiledRenderGraph compiled = graph.compile();
        int source = compiled.indexOf("source");
        int join = compiled.indexOf("join");
        Assertions.assertTrue(source < compiled.indexOf("branchB"));
        Assertions.assertTrue(source < compiled.indexOf("branchC"));
        Assertions.assertTrue(compiled.indexOf("branchB") < join);
        Assertions.assertTrue(compiled.indexOf("branchC") < join);
    }

    /** Transient resources must expose a lifetime and become recyclable after their last use. */
    @Test
    public void testLifetimeAndRecycling() {
        RenderResource scene = RenderResource.color("scene_color");
        RenderResource ping = RenderResource.transientColor("ping");
        RenderResource swapchain = RenderResource.color("swapchain");

        RenderGraph graph = new RenderGraph();
        graph.addExternalResource(scene);
        graph.addExternalResource(swapchain);
        graph.addPass(PassNode.graphics("composite0")
                .stages(ShaderStage.FRAGMENT).sampled(scene).colorOutput(ping).build());
        graph.addPass(PassNode.graphics("final")
                .stages(ShaderStage.FRAGMENT).sampled(ping).colorOutput(swapchain).build());

        CompiledRenderGraph compiled = graph.compile();
        var lifetime = compiled.getLifetimes().get("ping");
        Assertions.assertNotNull(lifetime);
        Assertions.assertTrue(lifetime.isRecyclable());
        int lastPass = compiled.indexOf("final");
        Assertions.assertEquals(lastPass, lifetime.recyclableAfterPassIndex());
        Assertions.assertTrue(compiled.getRecyclableResourcesAfter(lastPass).stream()
                .anyMatch(resource -> resource.name().equals("ping")));
        Assertions.assertFalse(compiled.getRecyclableResourcesAfter(compiled.indexOf("composite0")).stream()
                .anyMatch(resource -> resource.name().equals("ping")),
                "ping must not be recyclable while still needed by the final pass");
    }

    /** Reading a resource that nothing produces and that was not imported must be an error. */
    @Test
    public void testReadWithoutProducerIsRejected() {
        RenderResource ghost = RenderResource.color("ghost");
        RenderResource out = RenderResource.color("out");

        RenderGraph graph = new RenderGraph();
        graph.addPass(PassNode.graphics("consumer")
                .stages(ShaderStage.FRAGMENT).sampled(ghost).colorOutput(out).build());

        RenderGraphException exception = Assertions.assertThrows(RenderGraphException.class, graph::compile);
        Assertions.assertTrue(exception.getMessage().contains("ghost"), exception.getMessage());
    }

    /** Imported resources may be read without a producer. */
    @Test
    public void testExternalResourceReadIsAllowed() {
        RenderResource scene = RenderResource.color("scene_color");
        RenderResource out = RenderResource.color("out");

        RenderGraph graph = new RenderGraph();
        graph.addExternalResource(scene);
        graph.addPass(PassNode.graphics("tone")
                .stages(ShaderStage.FRAGMENT).sampled(scene).colorOutput(out).build());

        CompiledRenderGraph compiled = graph.compile();
        Assertions.assertTrue(compiled.getExternalResources().contains("scene_color"));
    }

    /** Duplicate pass names must be rejected before compilation. */
    @Test
    public void testDuplicatePassNameRejected() {
        RenderGraph graph = new RenderGraph();
        graph.addPass(graphicsPass("dup", null, RenderResource.color("a")));
        RenderGraphException exception = Assertions.assertThrows(
                RenderGraphException.class,
                () -> graph.addPass(graphicsPass("dup", null, RenderResource.color("b"))));
        Assertions.assertTrue(exception.getMessage().contains("dup"), exception.getMessage());
    }

    /** A compute pass declaring a storage image must compile and expose its storage access. */
    @Test
    public void testComputePassWithStorageImage() {
        RenderResource storage = RenderResource.storageImage("cull_list");
        PassNode compute = PassNode.compute("culling")
                .stage(ShaderStage.COMPUTE)
                .storage(storage)
                .build();

        Assertions.assertEquals(PassNode.Kind.COMPUTE, compute.kind());
        Assertions.assertEquals(1, compute.storageAccesses().size());

        RenderGraph graph = new RenderGraph();
        graph.addPass(compute);
        Assertions.assertEquals(1, graph.compile().size());
    }

    /** A color attachment on a depth resource is an invalid contract and must be rejected. */
    @Test
    public void testUsageKindMismatchIsRejected() {
        RenderResource depth = RenderResource.depth("shadow_depth");
        Assertions.assertThrows(IllegalArgumentException.class, () -> PassNode.graphics("bad")
                .stages(ShaderStage.FRAGMENT)
                .colorOutput(depth)
                .build());
    }

    /** The frame-graph dump must name every pass and its resources for diagnostics. */
    @Test
    public void testFrameGraphDumpMentionsPassesAndResources() {
        RenderResource depth = RenderResource.depth("gbuffer_depth");
        RenderResource color = RenderResource.color("gbuffer_color");

        RenderGraph graph = new RenderGraph();
        graph.addPass(PassNode.graphics("gbuffers")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .colorOutput(color).depthOutput(depth).build());

        String dump = graph.compile().describe();
        Assertions.assertTrue(dump.contains("gbuffers"), dump);
        Assertions.assertTrue(dump.contains("gbuffer_color"), dump);
        Assertions.assertTrue(dump.contains("gbuffer_depth"), dump);
    }

    /**
     * Complementary-style ping-pong reuse must compile in declaration order instead of
     * fabricating a cycle. The pack ships {@code composite, composite1, composite3,
     * composite4, composite5, composite6, final} (note the skipped {@code composite2}),
     * so the two alternating intermediates are each written, sampled, then overwritten.
     * Every overwrite is a backed write-after-read (the sampler observed a produced value),
     * which orders reader-before-later-writer; the old inverted edge rejected this linear
     * chain as {@code composite1 -> composite3 -> composite1} and every pack pass was lost
     * with it. Regression for the runClient
     * {@code PackRenderPipeline.buildFromPlan} failure.
     */
    @Test
    public void testPingPongReuseWithSkippedIndexCompilesInOrder() {
        List<String> postPrograms = List.of(
                "composite", "composite1", "composite3", "composite4",
                "composite5", "composite6", "final");
        List<CompositeChainPlanner.CompositeStep> steps = CompositeChainPlanner.plan(
                postPrograms,
                RenderTargetRegistry.SCENE_COLOR,
                RenderTargetRegistry.PING,
                RenderTargetRegistry.PONG,
                RenderTargetRegistry.FINAL);

        RenderGraph graph = CompositeChainPlanner.toRenderGraph(steps);
        CompiledRenderGraph compiled = graph.compile();

        Assertions.assertEquals(postPrograms,
                compiled.getExecutionOrder().stream().map(PassNode::name).toList(),
                "no pack pass may be dropped or reordered to break a false cycle");
    }
}
