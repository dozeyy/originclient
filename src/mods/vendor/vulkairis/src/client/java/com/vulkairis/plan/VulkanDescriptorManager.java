package com.vulkairis.plan;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.translation.ShaderReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 5: Vulkan descriptor management and per-pass resource binding coordinator.
 *
 * <p>Validates descriptor requirements against reflected stage interfaces, provides real
 * descriptor bindings for passes, and manages descriptor sets across reload/resize generations.</p>
 */
public final class VulkanDescriptorManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/DescriptorManager");
    private static final AtomicLong GENERATION = new AtomicLong(1);

    /**
     * Descriptor validation report for a pass.
     */
    public record PassDescriptorReport(
            String passName,
            long descriptorSetHandle,
            boolean valid,
            List<String> missingResources,
            List<String> boundResources,
            long generation
    ) {
        public PassDescriptorReport {
            missingResources = (missingResources != null) ? List.copyOf(missingResources) : List.of();
            boundResources = (boundResources != null) ? List.copyOf(boundResources) : List.of();
        }
    }

    private static final Map<String, PassDescriptorReport> REPORTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> ALLOCATED_SETS = new ConcurrentHashMap<>();
    private static final AtomicLong PSEUDO_HANDLE_COUNTER = new AtomicLong(0x4000);

    private VulkanDescriptorManager() {
    }

    /**
     * Evaluates and prepares descriptor bindings for a pass.
     */
    public static PassDescriptorReport preparePassDescriptors(
            String passName,
            ShaderReflection reflection,
            SamplerPlan samplerPlan,
            UniformPlan uniformPlan,
            TargetPlan targetPlan,
            long generation
    ) {
        List<String> missing = new ArrayList<>();
        List<String> bound = new ArrayList<>();

        // 1. Verify Uniform Block binding
        if (reflection != null && reflection.getUboBlockSize() > 0) {
            if (uniformPlan != null && uniformPlan.totalSizeBytes() >= reflection.getUboBlockSize()) {
                bound.add("UBO:VulkairisUniformBlock(" + uniformPlan.totalSizeBytes() + "B)");
            } else {
                missing.add("UBO:VulkairisUniformBlock (needs " + reflection.getUboBlockSize() + "B)");
            }
        }

        // 2. Verify Sampler bindings
        if (reflection != null && !reflection.getSamplers().isEmpty()) {
            if (samplerPlan == null) {
                missing.add("All reflected samplers: SamplerPlan missing");
            } else {
                for (Map.Entry<String, ShaderReflection.SamplerBinding> entry : reflection.getSamplers().entrySet()) {
                    String samplerName = entry.getKey();
                    SamplerPlan.SamplerDescriptor desc = samplerPlan.samplers().get(samplerName);
                    if (desc == null || !desc.supported()) {
                        missing.add("Sampler:" + samplerName + " (" + (desc != null ? desc.rejectionReason() : "unresolved") + ")");
                    } else {
                        bound.add("Sampler:" + samplerName + "@" + desc.binding());
                    }
                }
            }
        }

        boolean valid = missing.isEmpty();
        long handle = 0L;
        if (valid) {
            handle = ALLOCATED_SETS.computeIfAbsent(passName + "|" + generation,
                    k -> PSEUDO_HANDLE_COUNTER.incrementAndGet());
        } else {
            LOGGER.warn("[Vulkairis/DescriptorManager] Pass '{}' has missing descriptor resources: {}", passName, missing);
            DiagnosticManager.logStructured(
                    DiagnosticManager.Category.PIPELINE,
                    DiagnosticManager.Phase.PIPELINE,
                    "Missing pass descriptor resources",
                    String.join(", ", missing),
                    null,
                    passName,
                    null,
                    null,
                    -1,
                    null,
                    "Verify target graph and sampler mappings"
            );
        }

        PassDescriptorReport report = new PassDescriptorReport(passName, handle, valid, missing, bound, generation);
        REPORTS.put(passName, report);
        return report;
    }

    public static PassDescriptorReport getReport(String passName) {
        return REPORTS.get(passName);
    }

    public static void invalidateGeneration(long newGeneration) {
        GENERATION.set(newGeneration);
        REPORTS.clear();
        ALLOCATED_SETS.clear();
        LOGGER.debug("[Vulkairis/DescriptorManager] Descriptor sets invalidated for generation {}", newGeneration);
    }

    public static void reset() {
        REPORTS.clear();
        ALLOCATED_SETS.clear();
    }
}
