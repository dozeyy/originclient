package com.vulkairis.diagnostics;

import com.vulkairis.iris.IrisBridge;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendering.exec.ComputePipelineFactory;
import com.vulkairis.rendering.exec.VulkairisFrameCoordinator;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Part I: a subsystem status report distinguishing what is implemented from what is a placeholder,
 * no-op, or not yet wired into the live renderer.
 *
 * <p>The plan's rule is that "shader loaded" must never be confused with "shader rendered". This
 * report answers, in one place, exactly where the pipeline currently stands so a load message is not
 * mistaken for execution.</p>
 */
public final class VulkairisArchitectureReport {

    /** Classification of a subsystem's real state. */
    public enum Status {
        IMPLEMENTED,
        PARTIAL,
        PLACEHOLDER,
        NOOP,
        NOT_WIRED
    }

    /**
     * @param name   subsystem name
     * @param status classification
     * @param detail why
     */
    public record Subsystem(String name, Status status, String detail) {
    }

    /**
     * Live signals used to classify subsystems. Kept as plain values so classification is testable.
     *
     * @param deviceLive        a Vulkan logical device is available
     * @param packActive        a shader-pack program catalog was discovered
     * @param discoveredPrograms number of discovered programs
     * @param registeredShaders  number of compiled shaders registered
     * @param registeredPrograms number of linked programs registered
     * @param computePipelines   number of compute pipelines created
     * @param graphWired         the render graph is driven from the frame
     */
    public record Probe(
            boolean deviceLive,
            boolean packActive,
            int discoveredPrograms,
            int registeredShaders,
            int registeredPrograms,
            int computePipelines,
            boolean graphWired) {
    }

    private final List<Subsystem> subsystems;

    private VulkairisArchitectureReport(List<Subsystem> subsystems) {
        this.subsystems = Collections.unmodifiableList(subsystems);
    }

    /**
     * Classifies every subsystem from a probe.
     *
     * @param probe the live signals
     * @return the report
     */
    public static VulkairisArchitectureReport evaluate(Probe probe) {
        List<Subsystem> list = new ArrayList<>();

        list.add(new Subsystem("GLSL translation", Status.IMPLEMENTED,
                "named ShaderTranslationPipeline (normalize -> compile -> validate -> reflect)"));
        list.add(new Subsystem("SPIR-V compile/validate/reflect", Status.IMPLEMENTED,
                "Shaderc compile + SpirvValidator + SpirvReflector"));
        list.add(new Subsystem("Program discovery", probe.discoveredPrograms > 0 ? Status.IMPLEMENTED : Status.PARTIAL,
                probe.discoveredPrograms + " program(s) discovered across all Iris groups"));
        list.add(new Subsystem("Shader/program registry",
                probe.registeredShaders > 0 ? Status.IMPLEMENTED : Status.NOT_WIRED,
                probe.registeredShaders + " shader(s), " + probe.registeredPrograms + " program(s) registered"));
        list.add(new Subsystem("Render graph", Status.IMPLEMENTED,
                "RenderGraph compiles, orders, rejects cycles, tracks lifetimes"));
        list.add(new Subsystem("Graph execution wiring",
                probe.graphWired ? Status.IMPLEMENTED : Status.NOT_WIRED,
                probe.graphWired
                        ? "RenderGraphExecutor driven from the frame"
                        : "RenderGraphExecutor exists but is not driven from the frame"));
        list.add(new Subsystem("Render targets/resources", Status.IMPLEMENTED,
                "TextureResource/spec/registry with formats, sampler policy, lifetimes"));
        list.add(new Subsystem("Descriptors",
                probe.registeredPrograms > 0 ? Status.PARTIAL : Status.NOT_WIRED,
                "layouts from reflection; per-pass write-back not proven"));
        list.add(new Subsystem("Synchronization/barriers", Status.PARTIAL,
                "BarrierGenerator produces transitions; recording into the live frame is partial"));
        list.add(new Subsystem("Compute",
                probe.computePipelines > 0 ? Status.PARTIAL : Status.PLACEHOLDER,
                "compute pipeline + dispatch code exists; no verified compute pack run"));
        list.add(new Subsystem("Shadow/GBuffer/Composite passes", Status.PARTIAL,
                "projection/config/planner exist; geometry rendering not yet driven through them"));
        list.add(new Subsystem("Color/HDR/gamma", Status.PARTIAL,
                "ColorSpacePolicy defined; not yet enforced across passes"));
        list.add(new Subsystem("Presentation", probe.deviceLive ? Status.PARTIAL : Status.NOT_WIRED,
                "PresentationTarget reads the VulkanMod swapchain; final-pass ownership partial"));
        list.add(new Subsystem("Iris no-op replacement", Status.PARTIAL,
                "uniforms/bindings/barriers/dispatch tracked; several Iris ops still stubs"));
        list.add(new Subsystem("Lifecycle/resize", Status.PARTIAL,
                "ResizeCoordinator + dimension hooks; lifecycle state machine not fully wired"));
        list.add(new Subsystem("Debug/visualization", Status.PARTIAL,
                "visualizer modes + /vulkairis programs|compatibility; intermediate viewers partial"));
        list.add(new Subsystem("Compatibility/fallbacks", Status.IMPLEMENTED,
                "ShaderPackCompatibilityReport with supported/partial/unsupported states"));
        list.add(new Subsystem("Performance profiling", Status.PLACEHOLDER,
                "no GPU timestamps or pass timing yet"));
        list.add(new Subsystem("Vanilla fallback", Status.IMPLEMENTED,
                "no pack active => vanilla path unaffected"));

        return new VulkairisArchitectureReport(list);
    }

    /** @return a report built from live runtime signals (never throws) */
    public static VulkairisArchitectureReport capture() {
        try {
            ShaderPackProgramCatalog catalog = IrisBridge.getProgramCatalog();
            Probe probe = new Probe(
                    VulkanBackendAdapter.isLive(VulkanBackendAdapter.getDevice()),
                    catalog != null,
                    catalog == null ? 0 : catalog.size(),
                    safeShaderCount(),
                    safeProgramCount(),
                    safeComputeCount(),
                    VulkairisFrameCoordinator.isActive());
            return evaluate(probe);
        } catch (Throwable t) {
            return evaluate(new Probe(false, false, 0, 0, 0, 0, false));
        }
    }

    private static int safeShaderCount() {
        try {
            return VulkanShaderRegistry.registeredShaderCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeProgramCount() {
        try {
            return VulkanShaderRegistry.registeredProgramCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int safeComputeCount() {
        try {
            return ComputePipelineFactory.cachedCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    public List<Subsystem> getSubsystems() {
        return subsystems;
    }

    /** @return how many subsystems have the given status */
    public long count(Status status) {
        return subsystems.stream().filter(subsystem -> subsystem.status() == status).count();
    }

    /** @return a multi-line, chat-friendly report */
    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("Vulkairis architecture report: ")
                .append(count(Status.IMPLEMENTED)).append(" implemented, ")
                .append(count(Status.PARTIAL)).append(" partial, ")
                .append(count(Status.PLACEHOLDER)).append(" placeholder, ")
                .append(count(Status.NOT_WIRED)).append(" not wired");
        for (Subsystem subsystem : subsystems) {
            builder.append("\n  ").append(symbol(subsystem.status())).append(' ')
                    .append(subsystem.name()).append(" - ").append(subsystem.detail());
        }
        return builder.toString();
    }

    private static String symbol(Status status) {
        return switch (status) {
            case IMPLEMENTED -> "[OK]";
            case PARTIAL -> "[~]";
            case PLACEHOLDER -> "[.]";
            case NOOP -> "[x]";
            case NOT_WIRED -> "[!]";
        };
    }
}
