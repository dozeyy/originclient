package com.vulkairis.rendering.exec;

import com.vulkairis.rendergraph.PassNode;
import com.vulkairis.rendergraph.RenderGraph;
import com.vulkairis.rendergraph.RenderResource;
import com.vulkairis.rendergraph.ShaderStage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 8: plans a composite/post-processing chain as a ping-pong render graph.
 *
 * <p>Bloom, tone mapping, color grading and the rest are the shader pack's own GLSL. Vulkairis does
 * not implement them individually; it executes the pack's composite programs against alternating
 * intermediate targets and produces the final image. This planner turns an ordered list of composite
 * program names into those pass steps and a compilable {@link RenderGraph}.</p>
 */
public final class CompositeChainPlanner {

    /**
     * One composite step.
     *
     * @param programName composite program to run
     * @param input       the target it samples
     * @param output      the target it writes
     * @param finalStep   whether this is the last step (writes the presentation target)
     */
    public record CompositeStep(
            String programName, RenderResource input, RenderResource output, boolean finalStep) {
    }

    private CompositeChainPlanner() {
    }

    /**
     * Builds a ping-pong plan for the given programs.
     *
     * @param programs    ordered composite program names
     * @param sceneColor  the scene color target the first pass samples
     * @param ping        first intermediate target name
     * @param pong        second intermediate target name
     * @param finalOutput the presentation target the last pass writes
     * @return the ordered steps
     */
    public static List<CompositeStep> plan(
            List<String> programs, String sceneColor, String ping, String pong, String finalOutput) {
        Objects.requireNonNull(programs, "programs");
        if (programs.isEmpty()) {
            throw new IllegalArgumentException("composite chain requires at least one program");
        }
        for (String program : programs) {
            if (program == null || program.isBlank()) {
                throw new IllegalArgumentException("composite program names must be non-blank");
            }
        }

        RenderResource scene = RenderResource.color(sceneColor);
        RenderResource pingResource = RenderResource.transientColor(ping);
        RenderResource pongResource = RenderResource.transientColor(pong);
        RenderResource output = RenderResource.color(finalOutput);

        List<CompositeStep> steps = new ArrayList<>();
        RenderResource input = scene;
        for (int i = 0; i < programs.size(); i++) {
            boolean last = i == programs.size() - 1;
            RenderResource target = last
                    ? output
                    : (i % 2 == 0 ? pingResource : pongResource);
            steps.add(new CompositeStep(programs.get(i), input, target, last));
            input = target;
        }
        return List.copyOf(steps);
    }

    /**
     * Builds a compilable render graph from planned steps. The first input (and any input not
     * produced by an earlier step) is imported as an external resource.
     *
     * @param steps the planned steps
     * @return the render graph (call {@link RenderGraph#compile()})
     */
    public static RenderGraph toRenderGraph(List<CompositeStep> steps) {
        Objects.requireNonNull(steps, "steps");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("composite chain requires at least one step");
        }
        Set<String> produced = new HashSet<>();
        RenderGraph graph = new RenderGraph();
        for (CompositeStep step : steps) {
            if (!produced.contains(step.input().name())) {
                graph.addExternalResource(step.input());
            }
            graph.addPass(PassNode.graphics(step.programName())
                    .stages(ShaderStage.VERTEX, ShaderStage.FRAGMENT)
                    .sampled(step.input())
                    .colorOutput(step.output())
                    .build());
            produced.add(step.output().name());
        }
        return graph;
    }
}
