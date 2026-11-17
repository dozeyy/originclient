package com.vulkairis.rendergraph;

import java.util.*;
import java.util.logging.Logger;

/**
 * Manages the execution of the RenderGraph.
 * Handles resource lifetimes, resize events, and render-scale changes.
 */
public final class RenderPassScheduler {
    private static final Logger LOGGER = Logger.getLogger("Vulkairis/Scheduler");
    private CompiledRenderGraph currentGraph;
    private float renderScale = 1.0f;

    public void updateGraph(RenderGraph graph) {
        this.currentGraph = graph.compile();
        LOGGER.info("Render graph updated: " + currentGraph.size() + " passes");
    }

    public void setRenderScale(float scale) {
        this.renderScale = scale;
        // ponytail: trigger resize of all graph resources.
    }

    public void execute() {
        if (currentGraph == null) return;

        for (int i = 0; i < currentGraph.size(); i++) {
            PassNode pass = currentGraph.getExecutionOrder().get(i);
            // ponytail: Bind pass descriptors and execute draw calls.
            executePass(pass);

            // Recycle resources.
            List<RenderResource> recyclable = currentGraph.getRecyclableResourcesAfter(i);
            for (RenderResource res : recyclable) {
                // ponytail: mark for reuse in pool.
            }
        }
    }

    private void executePass(PassNode pass) {
        // ponytail: real execution logic here.
    }
}
