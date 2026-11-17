package com.vulkairis.translation;

import java.util.*;

/**
 * Encapsulates reflected metadata from a compiled SPIR-V or GLSL shader.
 * Tracks uniform variables, texture sampler bindings, storage buffers, and vertex attributes.
 */
public class ShaderReflection {
    public record UniformInfo(String name, String type, int offset, int size) {}
    /** {@code descriptorCount} is the number of array elements for an arrayed descriptor (1 otherwise). */
    public record SamplerBinding(String name, int binding, int textureUnit, int descriptorCount) {}
    public record VertexAttribute(String name, int location, String type, int size) {}
    public record UniformBlock(String name, int set, int binding, int stage, int size, List<UniformInfo> members) {}

    /** A reflected shader stage output: its interface {@code location} and vector component count. */
    public record StageOutput(String name, int location, String type, int componentCount) {}
    /** A reflected shader stage input, exposed for cross-stage interface matching. */
    public record StageInput(String name, int location, String type, int componentCount) {}

    private final Map<String, UniformInfo> uniforms = new HashMap<>();
    private final Map<String, SamplerBinding> samplers = new HashMap<>();
    private final List<VertexAttribute> vertexAttributes = new ArrayList<>();
    private final List<UniformBlock> uniformBlocks = new ArrayList<>();
    private final List<StageOutput> stageOutputs = new ArrayList<>();
    private final List<StageInput> stageInputs = new ArrayList<>();
    private int uboBlockSize = 0;
    private int pushConstantSize = 0;
    private boolean complete = true;
    /** Validated entry-point name (defaults to {@code main} when the module names none). */
    private String entryPointName = "main";
    /** SPIR-V execution model of the entry point (-1 when unknown). */
    private int executionModel = -1;

    public ShaderReflection() {}

    public void addUniform(String name, String type, int offset, int size) {
        uniforms.put(name, new UniformInfo(name, type, offset, size));
    }

    public void addSampler(String name, int binding, int textureUnit) {
        addSampler(name, binding, textureUnit, 1);
    }

    public void addSampler(String name, int binding, int textureUnit, int descriptorCount) {
        samplers.put(name, new SamplerBinding(name, binding, textureUnit, Math.max(1, descriptorCount)));
    }

    public void addVertexAttribute(String name, int location, String type, int size) {
        vertexAttributes.add(new VertexAttribute(name, location, type, size));
    }

    public void addStageOutput(String name, int location, String type, int componentCount) {
        stageOutputs.add(new StageOutput(name, location, type, Math.max(1, componentCount)));
    }

    public void addStageInput(String name, int location, String type, int componentCount) {
        stageInputs.add(new StageInput(name, location, type, Math.max(1, componentCount)));
    }

    public void addUniformBlock(String name, int set, int binding, int stage, int size, List<UniformInfo> members) {
        uniformBlocks.add(new UniformBlock(name, set, binding, stage, size,
                Collections.unmodifiableList(new ArrayList<>(members))));
    }

    public List<UniformBlock> getUniformBlocks() {
        return Collections.unmodifiableList(uniformBlocks);
    }

    public void setUboBlockSize(int size) {
        this.uboBlockSize = size;
    }

    public int getUboBlockSize() {
        return uboBlockSize;
    }

    public void setPushConstantSize(int size) {
        this.pushConstantSize = size;
    }

    public int getPushConstantSize() {
        return pushConstantSize;
    }

    public Map<String, UniformInfo> getUniforms() {
        return Collections.unmodifiableMap(uniforms);
    }

    public Map<String, SamplerBinding> getSamplers() {
        return Collections.unmodifiableMap(samplers);
    }

    public List<VertexAttribute> getVertexAttributes() {
        return Collections.unmodifiableList(vertexAttributes);
    }

    public List<StageOutput> getStageOutputs() {
        return Collections.unmodifiableList(stageOutputs);
    }

    public List<StageInput> getStageInputs() {
        return Collections.unmodifiableList(stageInputs);
    }

    public boolean hasUniform(String name) {
        return uniforms.containsKey(name);
    }

    public UniformInfo getUniform(String name) {
        return uniforms.get(name);
    }

    /**
     * Whether reflection parsed the module without error. A reflection that is not complete must
     * be treated as a failure signal by callers instead of a trustworthy (but partial) result.
     */
    public boolean isComplete() {
        return complete;
    }

    /** Marks this reflection as the result of a failed/partial parse. */
    public void markIncomplete() {
        this.complete = false;
    }

    public String getEntryPointName() {
        return entryPointName != null ? entryPointName : "main";
    }

    public void setEntryPointName(String entryPointName) {
        if (entryPointName != null && !entryPointName.isBlank()) {
            this.entryPointName = entryPointName;
        }
    }

    public int getExecutionModel() {
        return executionModel;
    }

    public void setExecutionModel(int executionModel) {
        this.executionModel = executionModel;
    }
}
