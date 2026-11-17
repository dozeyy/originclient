package com.vulkairis.translation;

import com.vulkairis.vulkan.pipeline.PipelineCompatibilityValidator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Real program-link validation (Phase 5).
 *
 * <p>A program links only when its stage set is complete (vertex + fragment, no unsupported
 * stages), every stage carries usable SPIR-V plus complete reflection, vertex outputs match
 * fragment inputs by location <em>and</em> type, and fragment outputs fit the target
 * configuration. Anything else fails with the offending names and types in the diagnostic —
 * link success is never simulated to keep the call stack moving.
 *
 * <p>Descriptor-number disagreements (same sampler/block name, different auto-assigned binding
 * per stage) are reported as <em>warnings</em>, not failures: shaderc assigns those numbers
 * independently per stage, and the per-program reconciled layout that would resolve them lands
 * with the descriptor work. Promote them to errors once that layout exists.
 */
public final class VulkanProgramLinker {

    /** Maximum fragment color outputs accepted, matching the reported GL capabilities. */
    public static final int MAX_FRAGMENT_OUTPUTS = 16;

    private VulkanProgramLinker() {
    }

    /** Outcome of validating one program: errors fail the link, warnings travel with it. */
    public record LinkResult(boolean valid, List<String> errors, List<String> warnings) {
        public LinkResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** @return errors followed by warnings, joined for info logs */
        public String log() {
            List<String> all = new ArrayList<>(errors);
            for (String warning : warnings) {
                all.add("warning: " + warning);
            }
            return String.join("; ", all);
        }
    }

    /**
     * Validates the program's stages, interfaces and descriptor agreement.
     *
     * @param program the program to validate; {@code null} fails
     * @return the result; {@code valid} only when a pipeline may be built
     */
    public static LinkResult validate(VulkairisProgram program) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (program == null) {
            errors.add("cannot link a null program");
            return new LinkResult(false, errors, warnings);
        }
        String name = program.getName();
        program.resolveAttachedShaders();

        if (program.attachedShaderIds().isEmpty()) {
            errors.add("program '" + name + "' has no attached stages");
            return new LinkResult(false, errors, warnings);
        }
        for (int id : program.attachedShaderIds()) {
            if (VulkanShaderRegistry.getShader(id) == null) {
                errors.add("program '" + name + "' references shader id " + id
                        + ", which has no shader object (deleted or never compiled)");
            }
        }

        VulkairisShader vert = program.getVertexShader();
        VulkairisShader frag = program.getFragmentShader();
        if (vert == null) {
            errors.add("program '" + name + "' has no vertex stage");
        }
        if (frag == null) {
            errors.add("program '" + name + "' has no fragment stage");
        }
        for (VulkairisShader stage : program.getAttachedShaders()) {
            if (isUnsupportedStage(stage)) {
                errors.add("program '" + name + "' attaches unsupported stage '"
                        + stage.getName() + "' (GL stage " + stage.getStage()
                        + "): tessellation/geometry/compute dispatch is not implemented");
            }
        }
        if (vert != null) {
            checkStageReady(name, vert, errors);
        }
        if (frag != null) {
            checkStageReady(name, frag, errors);
        }
        if (!errors.isEmpty()) {
            return new LinkResult(false, errors, warnings);
        }

        // Locations, component counts and fragment-output capacity.
        errors.addAll(PipelineCompatibilityValidator.validate(
                vert.getReflection(), frag.getReflection(), MAX_FRAGMENT_OUTPUTS));

        // Type agreement on matched locations.
        Map<Integer, ShaderReflection.StageOutput> outputsByLocation = new HashMap<>();
        for (ShaderReflection.StageOutput output : vert.getReflection().getStageOutputs()) {
            outputsByLocation.putIfAbsent(output.location(), output);
        }
        for (ShaderReflection.StageInput input : frag.getReflection().getStageInputs()) {
            ShaderReflection.StageOutput match = outputsByLocation.get(input.location());
            if (match != null && !match.type().equals(input.type())) {
                errors.add("stage interface type mismatch at location " + input.location()
                        + ": vertex output '" + match.name() + "' is " + match.type()
                        + " but fragment input '" + input.name() + "' expects " + input.type());
            }
        }

        // Descriptor agreement across stages (warnings until the reconciled layout lands).
        checkSamplerAgreement(vert, frag, warnings);
        checkBlockAgreement(vert, frag, warnings);

        // Bridge texture-unit budget: more distinct samplers than supported units cannot bind.
        java.util.TreeSet<String> samplers = new java.util.TreeSet<>();
        samplers.addAll(vert.getReflection().getSamplers().keySet());
        samplers.addAll(frag.getReflection().getSamplers().keySet());
        int budget = com.vulkairis.rendering.state.TextureUnitPolicy.SUPPORTED_UNIT_COUNT;
        if (samplers.size() > budget) {
            errors.add("program '" + name + "' needs " + samplers.size() + " samplers "
                    + samplers + " but the bridge supports " + budget
                    + " texture units (0.." + (budget - 1) + "); refusing the program");
        }

        return new LinkResult(errors.isEmpty(), errors, warnings);
    }

    private static void checkStageReady(String program, VulkairisShader stage, List<String> errors) {
        if (!stage.hasSpirv()) {
            errors.add("program '" + program + "' stage '" + stage.getName()
                    + "' has no SPIR-V bytecode (compilation failed)");
        } else if (!stage.getReflection().isComplete()) {
            errors.add("program '" + program + "' stage '" + stage.getName()
                    + "' has incomplete reflection and cannot be trusted");
        }
    }

    private static boolean isUnsupportedStage(VulkairisShader stage) {
        int id = stage.getStage();
        if (id == 36313 || id == 37305 || id == 36488 || id == 36487) {
            return true; // geometry / compute / tessellation control / tessellation evaluation
        }
        if (id == 35633 || id == 35632 || id == 1 || id == 2) {
            return false;
        }
        // Unknown stage ids (including 0 = never compiled) cannot link.
        return true;
    }

    private static void checkSamplerAgreement(
            VulkairisShader vert, VulkairisShader frag, List<String> warnings) {
        Map<String, ShaderReflection.SamplerBinding> vertSamplers =
                vert.getReflection().getSamplers();
        Map<String, ShaderReflection.SamplerBinding> fragSamplers =
                frag.getReflection().getSamplers();
        for (Map.Entry<String, ShaderReflection.SamplerBinding> entry : vertSamplers.entrySet()) {
            ShaderReflection.SamplerBinding other = fragSamplers.get(entry.getKey());
            if (other == null) {
                continue;
            }
            ShaderReflection.SamplerBinding self = entry.getValue();
            if (self.binding() != other.binding()) {
                warnings.add("sampler '" + entry.getKey() + "' is bound at "
                        + self.binding() + " in the vertex stage but " + other.binding()
                        + " in the fragment stage; stages were compiled separately");
            }
        }
    }

    private static void checkBlockAgreement(
            VulkairisShader vert, VulkairisShader frag, List<String> warnings) {
        Map<String, ShaderReflection.UniformBlock> fragBlocks = new HashMap<>();
        for (ShaderReflection.UniformBlock block : frag.getReflection().getUniformBlocks()) {
            fragBlocks.put(block.name(), block);
        }
        for (ShaderReflection.UniformBlock block : vert.getReflection().getUniformBlocks()) {
            ShaderReflection.UniformBlock other = fragBlocks.get(block.name());
            if (other == null) {
                continue;
            }
            if (block.binding() != other.binding() || block.set() != other.set()) {
                warnings.add("uniform block '" + block.name() + "' is at set "
                        + block.set() + "/binding " + block.binding()
                        + " in the vertex stage but set " + other.set() + "/binding "
                        + other.binding() + " in the fragment stage");
            }
        }
    }
}
