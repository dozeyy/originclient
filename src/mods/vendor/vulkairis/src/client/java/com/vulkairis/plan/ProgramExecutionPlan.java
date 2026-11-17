package com.vulkairis.plan;

import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.ShaderPackProgramCatalog;
import com.vulkairis.rendering.backend.vulkanmod.v1211.VulkanModRenderStage;
import com.vulkairis.rendering.exec.PassGates;
import net.irisshaders.iris.shaderpack.loading.ProgramGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 3: Complete, immutable per-dimension execution plan derived from Iris ProgramSet discovery.
 * Lists required and executed programs, fallback resolutions, target reads/writes, and stage ownership.
 */
public final class ProgramExecutionPlan {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ExecutionPlan");

    private final String packName;
    private final String dimension;
    private final long generation;
    private final int windowWidth;
    private final int windowHeight;
    private final int shadowResolution;
    private final Map<String, String> packOptions;
    private final List<PassSpec> passes;
    private final Map<String, ProgramResolution> resolutions;
    private final Map<String, TargetSpec> targets;
    private final Set<PassGates.Gate> requiredGates;
    private final List<String> unsupportedFamilies;
    private final boolean executable;

    public ProgramExecutionPlan(
            String packName,
            String dimension,
            long generation,
            int windowWidth,
            int windowHeight,
            int shadowResolution,
            Map<String, String> packOptions,
            List<PassSpec> passes,
            Map<String, ProgramResolution> resolutions,
            Map<String, TargetSpec> targets,
            Set<PassGates.Gate> requiredGates,
            List<String> unsupportedFamilies,
            boolean executable
    ) {
        this.packName = packName != null ? packName : "None";
        this.dimension = dimension != null ? dimension : "overworld";
        this.generation = generation;
        this.windowWidth = Math.max(1, windowWidth);
        this.windowHeight = Math.max(1, windowHeight);
        this.shadowResolution = Math.max(0, shadowResolution);
        this.packOptions = (packOptions != null) ? Map.copyOf(packOptions) : Map.of();
        this.passes = (passes != null) ? List.copyOf(passes) : List.of();
        this.resolutions = (resolutions != null) ? Map.copyOf(resolutions) : Map.of();
        this.targets = (targets != null) ? Map.copyOf(targets) : Map.of();
        this.requiredGates = (requiredGates != null) ? Collections.unmodifiableSet(EnumSet.copyOf(requiredGates)) : Set.of();
        this.unsupportedFamilies = (unsupportedFamilies != null) ? List.copyOf(unsupportedFamilies) : List.of();
        this.executable = executable;
    }

    public String packName() {
        return packName;
    }

    public String dimension() {
        return dimension;
    }

    public long generation() {
        return generation;
    }

    public int windowWidth() {
        return windowWidth;
    }

    public int windowHeight() {
        return windowHeight;
    }

    public int shadowResolution() {
        return shadowResolution;
    }

    public Map<String, String> packOptions() {
        return packOptions;
    }

    public List<PassSpec> passes() {
        return passes;
    }

    public Map<String, ProgramResolution> resolutions() {
        return resolutions;
    }

    public Map<String, TargetSpec> targets() {
        return targets;
    }

    public Set<PassGates.Gate> requiredGates() {
        return requiredGates;
    }

    public List<String> unsupportedFamilies() {
        return unsupportedFamilies;
    }

    public boolean isExecutable() {
        return executable;
    }

    public PassSpec getPass(String passId) {
        for (PassSpec p : passes) {
            if (p.passId().equals(passId)) {
                return p;
            }
        }
        return null;
    }

    public ProgramResolution getResolution(String programName) {
        return resolutions.get(programName);
    }

    public TargetSpec getTarget(String targetName) {
        return targets.get(targetName);
    }

    /**
     * Builds an immutable execution plan from the discovered program catalog and runtime parameters.
     */
    public static ProgramExecutionPlan build(
            ShaderPackProgramCatalog catalog,
            String packName,
            String dimension,
            long generation,
            int windowWidth,
            int windowHeight,
            int shadowResolution,
            Map<String, String> packOptions
    ) {
        int width = (windowWidth > 0) ? windowWidth : 1920;
        int height = (windowHeight > 0) ? windowHeight : 1080;
        int shadowRes = (shadowResolution > 0) ? shadowResolution : 1024;
        String dim = (dimension != null && !dimension.isBlank()) ? dimension : "overworld";
        String pack = (packName != null && !packName.isBlank()) ? packName : "None";
        Map<String, String> options = (packOptions != null) ? new LinkedHashMap<>(packOptions) : new LinkedHashMap<>();

        if (catalog == null || catalog.isEmpty()) {
            return new ProgramExecutionPlan(
                    pack, dim, generation, width, height, shadowRes, options,
                    List.of(), Map.of(), Map.of(), Set.of(), List.of(), false
            );
        }

        Map<String, ProgramResolution> resolutions = new LinkedHashMap<>();
        List<PassSpec> passList = new ArrayList<>();
        Map<String, TargetSpec> targetMap = new LinkedHashMap<>();
        List<String> unsupportedFamilies = new ArrayList<>();

        // Base targets always declared
        targetMap.put("colortex0", TargetSpec.windowScaled("colortex0", 0, "RGBA8", 1.0f, 1.0f, true));
        targetMap.put("depth", TargetSpec.windowScaled("depth", -1, "DEPTH32F", 1.0f, 1.0f, false));

        if (catalog.hasShadowPass()) {
            targetMap.put("shadowtex0", TargetSpec.fixed("shadowtex0", -1, "DEPTH32F", shadowRes, shadowRes, true));
            targetMap.put("shadowcolor0", TargetSpec.fixed("shadowcolor0", 0, "RGBA8", shadowRes, shadowRes, true));
        }

        int passSequence = 0;
        for (ShaderPackProgram program : catalog.getPrograms()) {
            String name = program.name();
            ProgramGroup group = program.group();

            if (program.isCompute()) {
                resolutions.put(name, new ProgramResolution(
                        name, name, group, VulkanModRenderStage.FINAL_PASS,
                        ProgramResolution.ResolutionStatus.UNSUPPORTED,
                        "Compute shader execution path not yet supported"
                ));
                unsupportedFamilies.add("Compute shader: " + name);
                continue;
            }

            VulkanModRenderStage stage = mapStage(program);
            PassGates.Gate gate = mapGate(program);

            // DH degrade pass: without the DH mod the distant programs can never compile,
            // so they resolve UNSUPPORTED with the labeled reason and schedule no pass.
            // Non-DH programs below are unaffected.
            if (group == ProgramGroup.Dh
                    && !com.vulkairis.compatibility.DistantHorizonsSupport.isAvailable()) {
                String skipReason =
                        com.vulkairis.compatibility.DistantHorizonsSupport.skipReason(name);
                resolutions.put(name, new ProgramResolution(
                        name, name, group, stage,
                        ProgramResolution.ResolutionStatus.UNSUPPORTED,
                        skipReason));
                unsupportedFamilies.add("Distant Horizons program '" + name
                        + "' skipped (DH mod absent)");
                continue;
            }

            // Check if program is explicitly disabled in options
            if (isProgramDisabled(name, options)) {
                resolutions.put(name, new ProgramResolution(
                        name, name, group, stage,
                        ProgramResolution.ResolutionStatus.DISABLED,
                        "Disabled by user shader pack option"
                ));
                continue;
            }

            // Check for fallback program
            String effectiveName = name;
            ProgramResolution.ResolutionStatus status = ProgramResolution.ResolutionStatus.RESOLVED;
            String reason = "Discovered program active";

            if (program instanceof ShaderPackProgram.Graphics graphics && graphics.fallbackName() != null) {
                String fallback = graphics.fallbackName();
                if (!catalog.hasProgram(name) && catalog.hasProgram(fallback)) {
                    effectiveName = fallback;
                    status = ProgramResolution.ResolutionStatus.FALLBACK_APPLIED;
                    reason = "Applied fallback: " + fallback;
                }
            }

            resolutions.put(name, new ProgramResolution(name, effectiveName, group, stage, status, reason));

            // Build PassSpec with appropriate read/write targets
            List<String> reads = new ArrayList<>();
            List<String> writes = new ArrayList<>();
            boolean depthWrite = false;
            boolean depthTest = true;
            String blendMode = "OPAQUE";

            switch (group) {
                case Shadow, ShadowComposite -> {
                    writes.add("shadowtex0");
                    writes.add("shadowcolor0");
                    depthWrite = true;
                }
                case Gbuffers -> {
                    reads.add("shadowtex0");
                    writes.add("colortex0");
                    if (name.contains("water") || name.contains("translucent")) {
                        blendMode = "ALPHA_BLEND";
                        depthWrite = false;
                    } else {
                        depthWrite = true;
                    }
                }
                case Deferred, Composite -> {
                    reads.add("colortex0");
                    reads.add("depth");
                    writes.add("colortex0");
                    depthWrite = false;
                    depthTest = false;
                }
                case Final -> {
                    reads.add("colortex0");
                    writes.add("main");
                    depthWrite = false;
                    depthTest = false;
                }
                default -> {
                    writes.add("colortex0");
                }
            }

            PassSpec pass = new PassSpec(
                    name + "_" + passSequence,
                    group,
                    stage,
                    name,
                    program instanceof ShaderPackProgram.Graphics g ? g.fallbackName() : null,
                    reads,
                    writes,
                    gate,
                    depthWrite,
                    depthTest,
                    blendMode,
                    passSequence++
            );
            passList.add(pass);
        }

        // Sort passes by stage order index, then pass sequence
        passList.sort(Comparator.comparingInt((PassSpec p) -> p.stage().orderIndex())
                .thenComparingInt(PassSpec::executionOrder));

        Set<PassGates.Gate> requiredGates = PassGates.requiredFor(catalog);
        Map<PassGates.Gate, PassGates.GateStatus> gateStatuses = PassGates.implemented();

        for (PassGates.Gate g : requiredGates) {
            PassGates.GateStatus s = gateStatuses.get(g);
            if (s != null && s.status() == PassGates.Status.CLOSED) {
                unsupportedFamilies.add("Gate " + g + " is CLOSED: " + s.note());
            }
        }

        boolean executable = !passList.isEmpty() && unsupportedFamilies.isEmpty();

        LOGGER.info("[Vulkairis/ExecutionPlan] Built plan for '{}' (dim: {}, gen: {}, res: {}x{}, shadow: {}): {} pass(es), {} target(s), executable: {}",
                pack, dim, generation, width, height, shadowRes, passList.size(), targetMap.size(), executable);

        return new ProgramExecutionPlan(
                pack, dim, generation, width, height, shadowRes, options,
                passList, resolutions, targetMap, requiredGates, unsupportedFamilies, executable
        );
    }

    private static VulkanModRenderStage mapStage(ShaderPackProgram program) {
        ProgramGroup group = program.group();
        String name = program.name().toLowerCase();
        return switch (group) {
            case Setup -> VulkanModRenderStage.FRAME_BEGIN;
            case Begin -> VulkanModRenderStage.WORLD_RENDER_HEAD;
            case Shadow, ShadowComposite -> VulkanModRenderStage.SHADOW_PASS;
            case Prepare -> VulkanModRenderStage.CHUNK_SUBMISSION;
            case Gbuffers -> {
                if (name.contains("cutout")) yield VulkanModRenderStage.CUTOUT_TERRAIN;
                if (name.contains("entity") || name.contains("block")) yield VulkanModRenderStage.ENTITIES;
                if (name.contains("sky") || name.contains("cloud") || name.contains("weather")) yield VulkanModRenderStage.SKY_CLOUDS_WEATHER;
                if (name.contains("particle")) yield VulkanModRenderStage.PARTICLES;
                if (name.contains("water") || name.contains("translucent")) yield VulkanModRenderStage.TRANSLUCENT_TERRAIN;
                if (name.contains("hand")) yield VulkanModRenderStage.FIRST_PERSON_HAND;
                yield VulkanModRenderStage.OPAQUE_TERRAIN;
            }
            case Deferred, Composite -> VulkanModRenderStage.COMPOSITE_POST;
            case Final -> VulkanModRenderStage.FINAL_PASS;
            case Dh -> VulkanModRenderStage.OPAQUE_TERRAIN;
        };
    }

    private static PassGates.Gate mapGate(ShaderPackProgram program) {
        ProgramGroup group = program.group();
        String name = program.name().toLowerCase();
        return switch (group) {
            case Shadow, ShadowComposite -> PassGates.Gate.SHADOW;
            case Gbuffers -> {
                if (name.contains("water") || name.contains("translucent")) yield PassGates.Gate.TRANSLUCENT;
                if (name.contains("entity") || name.contains("block")) yield PassGates.Gate.OPAQUE_ENTITIES;
                if (name.contains("hand")) yield PassGates.Gate.FINAL_HUD;
                yield PassGates.Gate.TERRAIN;
            }
            case Deferred, Composite -> PassGates.Gate.DEFERRED_COMPOSITE;
            case Final -> PassGates.Gate.FINAL_HUD;
            case Setup, Begin, Prepare, Dh -> PassGates.Gate.TERRAIN;
        };
    }

    private static boolean isProgramDisabled(String programName, Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return false;
        }
        String opt = options.get(programName.toUpperCase() + "_ENABLED");
        if (opt != null && (opt.equalsIgnoreCase("false") || opt.equals("0") || opt.equalsIgnoreCase("off"))) {
            return true;
        }
        String optLower = options.get(programName.toLowerCase() + "_enabled");
        return optLower != null && (optLower.equalsIgnoreCase("false") || optLower.equals("0") || optLower.equalsIgnoreCase("off"));
    }

    public String describe() {
        return String.format("ProgramExecutionPlan[pack=%s, dim=%s, %dx%d, passes=%d, targets=%d, executable=%b]",
                packName, dimension, windowWidth, windowHeight, passes.size(), targets.size(), executable);
    }
}
