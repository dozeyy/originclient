package com.vulkairis.rendering.exec;

import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendergraph.CompiledRenderGraph;
import com.vulkairis.rendergraph.RenderGraph;
import com.vulkairis.resource.RenderTargetRegistry;
import com.vulkairis.resource.TextureResource;
import com.vulkairis.rendering.sync.ResourceStateTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Part III/X: binds a discovered shader pack to executable render-graph work.
 *
 * <p>This is the missing link between program discovery and execution: it allocates the standard
 * target set, builds a ping-pong composite/final render graph from the pack's programs, resolves a
 * {@link PassBinding} per program (via a {@link BindingResolver}), and drives
 * {@link RenderGraphExecutor} into VulkanMod's active command buffer. When any piece is unavailable
 * it degrades to a no-op rather than emitting invalid commands.</p>
 */
public final class PackRenderPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/PackPipeline");

    /** Default resolver that maps discovered program names to linked GraphicsPipelines. */
    public static final BindingResolver DEFAULT_RESOLVER = (programName, w, h, t) -> {
        try {
            com.vulkairis.translation.VulkairisProgram program =
                    com.vulkairis.translation.VulkanShaderRegistry.findProgramByName(programName);
            if (program == null) {
                return null;
            }
            net.vulkanmod.vulkan.shader.GraphicsPipeline gp =
                    com.vulkairis.bridge.VulkanModProgramBridge.getLinkedPipeline(program.getVirtualProgramId());
            if (gp == null) {
                return null;
            }
            long layout = gp.getLayout();
            long descSet = 0L;
            var vertShader = program.getVertexShader();
            var refl = vertShader != null ? vertShader.getReflection() : null;
            long gen = com.vulkairis.iris.SourceProvenance.currentGeneration();
            var targetPlan = com.vulkairis.plan.TargetPlan.buildStandard(w, h, 1024, t, gen);
            var samplerPlan = com.vulkairis.plan.SamplerPlan.build(programName, refl != null ? refl.getSamplers() : null, targetPlan, gen);
            var uniformPlan = com.vulkairis.plan.UniformPlan.build(programName, refl != null && !refl.getUniformBlocks().isEmpty() ? refl.getUniformBlocks().get(0) : null, gen);
            var report = com.vulkairis.plan.VulkanDescriptorManager.preparePassDescriptors(programName, refl, samplerPlan, uniformPlan, targetPlan, gen);
            if (report != null && report.valid()) {
                descSet = report.descriptorSetHandle();
            }
            return PassBinding.graphics(programName, 0L, layout, descSet, w, h, 4);
        } catch (Throwable ex) {
            LOGGER.debug("[Vulkairis/PackPipeline] Default resolver failed for {}: {}", programName, ex.getMessage());
            return null;
        }
    };

    /** Resolves a discovered program name to concrete GPU bindings, or {@code null} if unavailable. */
    @FunctionalInterface
    public interface BindingResolver {
        PassBinding resolve(String programName, int width, int height, RenderTargetRegistry targets);
    }

    private final RenderTargetRegistry targets;
    private final CompiledRenderGraph graph;
    private final Map<String, PassBinding> bindings;
    private final ResourceStateTracker tracker = new ResourceStateTracker();
    /** Phase E: the precomputed chain plan this pipeline was built from (never {@code null} from builders). */
    private final PackChainPlan chainPlan;
    /** Offscreen framebuffers keyed by target name (best-effort, may be empty). */
    private final java.util.Map<String, net.vulkanmod.vulkan.framebuffer.Framebuffer> framebuffers =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Offscreen render passes keyed by target name (best-effort, may be empty). */
    private final java.util.Map<String, net.vulkanmod.vulkan.framebuffer.RenderPass> renderPasses =
            new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.Map<String, net.vulkanmod.vulkan.framebuffer.Framebuffer> getFramebuffers() {
        return framebuffers;
    }
    public java.util.Map<String, net.vulkanmod.vulkan.framebuffer.RenderPass> getRenderPasses() {
        return renderPasses;
    }

    private PackRenderPipeline(RenderTargetRegistry targets, CompiledRenderGraph graph,
                                Map<String, PassBinding> bindings) {
        this(targets, graph, bindings, null);
    }

    private PackRenderPipeline(RenderTargetRegistry targets, CompiledRenderGraph graph,
                                Map<String, PassBinding> bindings, PackChainPlan chainPlan) {
        this.targets = targets;
        this.graph = graph;
        this.bindings = Collections.unmodifiableMap(bindings);
        this.chainPlan = chainPlan;
    }

    /**
     * Builds an executable pipeline for {@code catalog} at the given resolution.
     *
     * @param catalog          discovered pack, or {@code null}
     * @param width            screen width
     * @param height           screen height
     * @param shadowResolution shadow map resolution
     * @param resolver         per-program binding resolver (may be {@code null})
     * @return the pipeline; {@link #hasGraph()} is false when the pack has no post chain
     */
    public static PackRenderPipeline build(
            ShaderPackProgramCatalog catalog,
            int width,
            int height,
            int shadowResolution,
            BindingResolver resolver) {
        PackChainPlan plan = PackChainPlan.resolve(catalog, "unknown-pack", "unknown-dimension",
                width, height, shadowResolution,
                com.vulkairis.iris.SourceProvenance.currentGeneration());
        return buildFromPlan(plan, resolver);
    }

    /**
     * Phase E: builds an executable pipeline by replaying a precomputed {@link PackChainPlan}.
     * The plan's ordered post programs drive the same ping-pong graph construction as
     * {@link #build}; a substitute-only plan (no real post programs) yields a pipeline with no
     * graph, exactly like a pack with no post chain. Deterministic for a given plan.
     *
     * @param plan     the precomputed chain plan (must not be {@code null})
     * @param resolver per-program binding resolver (may be {@code null})
     * @return the pipeline; {@link #hasGraph()} is false when the plan has no executable chain
     */
    public static PackRenderPipeline buildFromPlan(
            PackChainPlan plan,
            BindingResolver resolver) {
        Objects.requireNonNull(plan, "plan");
        RenderTargetRegistry targets = com.vulkairis.bridge.TerrainTargets.create(
                plan.width(), plan.height(), 1, plan.shadowResolution());

        // (framebuffers populated on the returned pipeline below)

        List<String> postPrograms = plan.postPrograms();
        PackRenderPipeline early = new PackRenderPipeline(targets, null, Map.of(), plan);
        populateFramebuffers(early, targets);
        if (postPrograms.isEmpty()) {
            return early;
        }

        List<CompositeChainPlanner.CompositeStep> steps = CompositeChainPlanner.plan(
                postPrograms,
                RenderTargetRegistry.SCENE_COLOR,
                RenderTargetRegistry.PING,
                RenderTargetRegistry.PONG,
                RenderTargetRegistry.FINAL);
        RenderGraph graph = CompositeChainPlanner.toRenderGraph(steps);
        CompiledRenderGraph compiled = graph.compile();

        BindingResolver effectiveResolver = resolver != null ? resolver : DEFAULT_RESOLVER;
        Map<String, PassBinding> resolved = new LinkedHashMap<>();
        for (CompositeChainPlanner.CompositeStep step : steps) {
            PassBinding binding = effectiveResolver.resolve(step.programName(),
                    plan.width(), plan.height(), targets);
            if (binding != null) {
                resolved.put(step.programName(), binding);
            }
        }

        LOGGER.info("[Vulkairis/PackPipeline] Built pipeline from {} ({} post-program(s); resolved {} binding(s))",
                plan, postPrograms.size(), resolved.size());
        PackRenderPipeline built = new PackRenderPipeline(targets, compiled, resolved, plan);
        populateFramebuffers(built, targets);
        return built;
    }

    public boolean hasGraph() {
        return graph != null;
    }

    private static void populateFramebuffers(PackRenderPipeline pipeline, RenderTargetRegistry targets) {
        if (pipeline == null || targets == null) return;
        try {
            for (com.vulkairis.resource.TextureResource tr : targets.all()) {
                if (tr.getSpec().format().isDepth()) continue;
                try { tr.ensureBacking(); } catch (Throwable ignored) {}
                Object[] fb = com.vulkairis.bridge.VulkanModFramebufferFactory.create(tr);
                if (fb != null && fb.length == 2
                        && fb[0] instanceof net.vulkanmod.vulkan.framebuffer.Framebuffer f
                        && fb[1] instanceof net.vulkanmod.vulkan.framebuffer.RenderPass r) {
                    pipeline.framebuffers.put(tr.getSpec().resource().name(), f);
                    pipeline.renderPasses.put(tr.getSpec().resource().name(), r);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public RenderTargetRegistry getTargets() {
        return targets;
    }

    public CompiledRenderGraph getGraph() {
        return graph;
    }

    public net.vulkanmod.vulkan.shader.GraphicsPipeline getLinkedPipeline() {
        // ponytail: we return the first linked pipeline from the bindings map as a representative
        if (bindings.isEmpty()) return null;
        PassBinding first = bindings.values().iterator().next();
        // The binding resolver used DEFAULT_RESOLVER which linked via VulkanModProgramBridge
        // We need to recover the pipeline. In a real system, the PassBinding would store it.
        // For now, we assume the first program's pipeline is a good proxy for the global UBO update.
        return com.vulkairis.bridge.VulkanModProgramBridge.getLinkedPipeline(
                com.vulkairis.translation.VulkanShaderRegistry.findProgramByName(first.passName()).getVirtualProgramId());
    }

    /**
     * Phase E: the precomputed chain plan this pipeline replays, or {@code null} for pipelines
     * installed directly (tests) rather than built from a plan.
     */
    public PackChainPlan getChainPlan() {
        return chainPlan;
    }

    /**
     * Records this pack's passes for one frame into VulkanMod's active command buffer.
     *
     * @return the execution report
     */
    public RenderGraphExecutor.ExecutionReport executeOnce() {
        return executeTraced(null, null);
    }

    /**
     * Records one frame, collecting per-pass outcomes for the frame trace.
     *
     * @param recordedOut filled with recorded pass names, or {@code null}
     * @param skippedOut  filled with skipped pass names, or {@code null}
     * @return the execution report
     */
    public RenderGraphExecutor.ExecutionReport executeTraced(
            List<String> recordedOut, List<String> skippedOut) {
        return executeTraced(recordedOut, skippedOut, framebuffers, renderPasses);
    }

    public RenderGraphExecutor.ExecutionReport executeTraced(
            List<String> recordedOut, List<String> skippedOut,
            java.util.Map<String, net.vulkanmod.vulkan.framebuffer.Framebuffer> fbs,
            java.util.Map<String, net.vulkanmod.vulkan.framebuffer.RenderPass> rps) {
        if (graph == null) {
            return new RenderGraphExecutor.ExecutionReport(0, 0, false, "no composite/final programs");
        }
        List<String> unbacked = new ArrayList<>();
        for (TextureResource resource : targets.all()) {
            if (!resource.ensureBacking()) {
                unbacked.add(resource.getSpec().resource().name());
            }
        }
        // GUI plan Phase 8: with resource validation on, a frame whose targets lack backing
        // is skipped with a diagnostic instead of recording blind. Disable the flag only to
        // diagnose validation itself.
        boolean validate = true;
        try {
            validate = com.vulkairis.config.VulkairisConfigManager.getConfig()
                    .isResourceValidation();
        } catch (Throwable ignored) {
        }
        if (validate && !unbacked.isEmpty()) {
            com.vulkairis.diagnostics.StageDiagnostics.warn("Pack execution",
                    "skipping frame; targets without backing: " + String.join(", ", unbacked));
            return new RenderGraphExecutor.ExecutionReport(0, 0, false,
                    "targets without backing: " + String.join(", ", unbacked));
        }
        return RenderGraphExecutor.execute(graph, bindings, imageHandles(), tracker,
                recordedOut, skippedOut, fbs, rps);
    }

    private Map<String, Long> imageHandles() {
        Map<String, Long> handles = new LinkedHashMap<>();
        for (TextureResource resource : targets.all()) {
            long handle = resource.getImageHandle();
            if (handle != 0L) {
                handles.put(resource.getSpec().resource().name(), handle);
            }
        }
        return handles;
    }
}
