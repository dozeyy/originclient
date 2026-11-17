package com.vulkairis.test;

import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendergraph.RenderGraph;
import com.vulkairis.rendergraph.RenderGraphException;
import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.rendergraph.ResourceKind;
import com.vulkairis.rendergraph.ResourceUsage;
import com.vulkairis.rendergraph.ShaderStage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 11: passes carry viewport, clear, depth, blend, program and ordering contracts, and
 * the compiler enforces explicit ordering edges.
 */
public class PassNodeConfigTest {

    private static PassNode simple(String name) {
        return PassNode.graphics(name)
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color(name + "_out"), ResourceUsage.COLOR_ATTACHMENT)
                .build();
    }

    @Test
    public void testDefaults() {
        PassNode pass = simple("p");
        Assertions.assertEquals(-1, pass.viewportWidth());
        Assertions.assertEquals(-1, pass.viewportHeight());
        Assertions.assertTrue(pass.clearColor());
        Assertions.assertTrue(pass.clearDepth());
        Assertions.assertTrue(pass.depthTest());
        Assertions.assertTrue(pass.depthWrite());
        Assertions.assertEquals(3, pass.depthCompareOp());
        Assertions.assertFalse(pass.blendEnabled());
        Assertions.assertTrue(pass.programNames().isEmpty());
        Assertions.assertTrue(pass.runAfter().isEmpty());
    }

    @Test
    public void testBuilderConfig() {
        PassNode pass = PassNode.graphics("water")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color("water_out"), ResourceUsage.COLOR_ATTACHMENT)
                .viewport(1920, 1080)
                .clear(false, true)
                .depth(true, false, 2)
                .blend(true)
                .programs("gbuffers_water")
                .after("terrain")
                .build();
        Assertions.assertEquals(1920, pass.viewportWidth());
        Assertions.assertEquals(1080, pass.viewportHeight());
        Assertions.assertFalse(pass.clearColor());
        Assertions.assertTrue(pass.clearDepth());
        Assertions.assertTrue(pass.depthTest());
        Assertions.assertFalse(pass.depthWrite());
        Assertions.assertEquals(2, pass.depthCompareOp());
        Assertions.assertTrue(pass.blendEnabled());
        Assertions.assertEquals(List.of("gbuffers_water"), pass.programNames());
        Assertions.assertEquals(List.of("terrain"), pass.runAfter());
        Assertions.assertTrue(pass.describe().contains("1920x1080"));
        Assertions.assertTrue(pass.describe().contains("gbuffers_water"));
    }

    @Test
    public void testBadViewportRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> PassNode.graphics("bad")
                        .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                        .write(RenderResource.color("o"), ResourceUsage.COLOR_ATTACHMENT)
                        .viewport(0, 1080)
                        .build());
    }

    @Test
    public void testRunAfterOrdersPasses() {
        // No shared resources: without the edge, insertion order wins; with it, P1 first.
        PassNode second = PassNode.graphics("second")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color("r2"), ResourceUsage.COLOR_ATTACHMENT)
                .after("first")
                .build();
        PassNode first = PassNode.graphics("first")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color("r1"), ResourceUsage.COLOR_ATTACHMENT)
                .build();
        RenderGraph graph = new RenderGraph();
        graph.addPass(second);
        graph.addPass(first);
        List<String> order = graph.compile().getExecutionOrder().stream()
                .map(PassNode::name).toList();
        Assertions.assertEquals(List.of("first", "second"), order);
    }

    @Test
    public void testRunAfterUnknownPassFails() {
        PassNode lonely = PassNode.graphics("lonely")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color("r"), ResourceUsage.COLOR_ATTACHMENT)
                .after("ghost")
                .build();
        RenderGraph graph = new RenderGraph();
        graph.addPass(lonely);
        RenderGraphException failure = Assertions.assertThrows(RenderGraphException.class,
                graph::compile);
        Assertions.assertTrue(failure.getMessage().contains("ghost"), failure.getMessage());
    }

    @Test
    public void testContradictoryRunAfterIsACycle() {
        PassNode a = PassNode.graphics("a")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color("ra"), ResourceUsage.COLOR_ATTACHMENT)
                .after("b")
                .build();
        PassNode b = PassNode.graphics("b")
                .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                .write(RenderResource.color("rb"), ResourceUsage.COLOR_ATTACHMENT)
                .after("a")
                .build();
        RenderGraph graph = new RenderGraph();
        graph.addPass(a);
        graph.addPass(b);
        RenderGraphException failure = Assertions.assertThrows(RenderGraphException.class,
                graph::compile);
        Assertions.assertTrue(failure.getMessage().contains("cycle"), failure.getMessage());
    }

    @Test
    public void testKindSupportUnchanged() {
        Assertions.assertTrue(ResourceKind.COLOR_IMAGE.supports(ResourceUsage.COLOR_ATTACHMENT));
        Assertions.assertFalse(
                ResourceKind.COLOR_IMAGE.supports(ResourceUsage.DEPTH_ATTACHMENT));
    }
}
