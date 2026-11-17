package com.vulkairis.translation;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless-safe virtual GL query facade (Phase 5).
 *
 * <p>Every shader/program status, log, uniform and attribute query Iris makes at runtime is
 * answered here from registry state plus SPIR-V reflection — never from source-text substring
 * scans, and never from fabricated constants for objects Vulkairis owns. The mixins stay thin
 * delegates so this logic is unit-testable without a GL context.
 *
 * <p>Query policy:
 * <ul>
 *   <li>Known objects answer truthfully from state, reflection and retained logs.</li>
 *   <li>Deleted ids (tombstones, never recycled) fail predictably: status 0, locations -1,
 *       empty names/logs noting the deletion.</li>
 *   <li>Unknown ids (never registered — e.g. foreign objects managed by VulkanMod/vanilla)
 *       keep the legacy constant answers so non-Vulkairis paths keep working.</li>
 * </ul>
 */
public final class VirtualGlFacade {

    // GL query names.
    private static final int GL_COMPILE_STATUS = 35713;
    private static final int GL_LINK_STATUS = 35714;
    private static final int GL_INFO_LOG_LENGTH = 35716;
    private static final int GL_ATTACHED_SHADERS = 35717;
    private static final int GL_ACTIVE_UNIFORMS = 35718;
    private static final int GL_ACTIVE_ATTRIBUTES = 35721;

    /** Stable per-program uniform locations (no collisions, deterministic per query sequence). */
    private static final Map<String, Integer> UNIFORM_LOCATIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger UNIFORM_ID_GEN = new AtomicInteger(100);

    private VirtualGlFacade() {
    }

    /** @return the explicit object state, or {@code null} for unknown ids */
    public static GlObjectState shaderState(int virtualId) {
        if (VulkanShaderRegistry.isDeletedShader(virtualId)) {
            return GlObjectState.DELETED;
        }
        VulkairisShader shader = VulkanShaderRegistry.getShader(virtualId);
        if (shader == null) {
            return null;
        }
        if (shader.isStale()) {
            return GlObjectState.INVALIDATED;
        }
        if (!shader.hasSpirv() || !shader.getReflection().isComplete()) {
            return GlObjectState.FAILED;
        }
        return GlObjectState.COMPILED;
    }

    /** @return the explicit object state, or {@code null} for unknown ids */
    public static GlObjectState programState(int virtualProgramId) {
        if (VulkanShaderRegistry.isDeletedProgram(virtualProgramId)) {
            return GlObjectState.DELETED;
        }
        VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
        if (program == null) {
            return null;
        }
        if (program.isStale()) {
            return GlObjectState.INVALIDATED;
        }
        GlObjectState link = VulkanShaderRegistry.getProgramLinkState(virtualProgramId);
        if (link == GlObjectState.FAILED) {
            return GlObjectState.FAILED;
        }
        for (int id : program.attachedShaderIds()) {
            VulkairisShader stage = VulkanShaderRegistry.getShader(id);
            if (stage == null || VulkanShaderRegistry.isDeletedShader(id)) {
                return GlObjectState.INVALIDATED;
            }
        }
        if (program.attachedShaderIds().isEmpty()) {
            return GlObjectState.CREATED;
        }
        if (link == GlObjectState.LINKED) {
            return GlObjectState.LINKED;
        }
        for (int id : program.attachedShaderIds()) {
            GlObjectState stageState = shaderState(id);
            if (stageState != GlObjectState.COMPILED) {
                return GlObjectState.FAILED;
            }
        }
        return GlObjectState.COMPILED;
    }

    /**
     * Answers {@code glGetShaderi} for virtual shader ids.
     *
     * @param virtualId virtual shader id
     * @param pname     GL query name
     */
    public static int getShaderi(int virtualId, int pname) {
        GlObjectState state = shaderState(virtualId);
        if (state == null) {
            return 1; // Foreign object: preserve legacy behavior, do not break its path.
        }
        if (pname == GL_COMPILE_STATUS) {
            return state == GlObjectState.COMPILED ? 1 : 0;
        }
        if (pname == GL_INFO_LOG_LENGTH) {
            String log = VulkanShaderRegistry.getShaderInfoLog(virtualId);
            return log.isEmpty() ? 0 : log.length() + 1;
        }
        return 0;
    }

    /**
     * Answers {@code glGetProgrami} / {@code getProgramiv} for virtual program ids.
     *
     * @param virtualProgramId virtual program id
     * @param pname            GL query name
     */
    public static int getProgrami(int virtualProgramId, int pname) {
        GlObjectState state = programState(virtualProgramId);
        if (state == null) {
            // Foreign object: preserve the exact legacy constants.
            return switch (pname) {
                case GL_LINK_STATUS -> 1;
                case GL_INFO_LOG_LENGTH -> 0;
                case GL_ATTACHED_SHADERS -> 2;
                case GL_ACTIVE_UNIFORMS -> 0;
                case GL_ACTIVE_ATTRIBUTES -> 8;
                default -> 1;
            };
        }
        if (pname == GL_LINK_STATUS) {
            return state == GlObjectState.LINKED ? 1 : 0;
        }
        if (pname == GL_INFO_LOG_LENGTH) {
            String log = VulkanShaderRegistry.getProgramInfoLog(virtualProgramId);
            return log.isEmpty() ? 0 : log.length() + 1;
        }
        if (pname == GL_ATTACHED_SHADERS) {
            VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
            return program != null ? program.attachedShaderIds().size() : 0;
        }
        if (pname == GL_ACTIVE_UNIFORMS) {
            return activeUniformNames(virtualProgramId).size();
        }
        if (pname == GL_ACTIVE_ATTRIBUTES) {
            return vertexAttributeCount(virtualProgramId);
        }
        return 0;
    }

    /** @return retained shader info log ("" when clean) */
    public static String getShaderInfoLog(int virtualId) {
        return VulkanShaderRegistry.getShaderInfoLog(virtualId);
    }

    /** @return retained program info log ("" when clean) */
    public static String getProgramInfoLog(int virtualProgramId) {
        return VulkanShaderRegistry.getProgramInfoLog(virtualProgramId);
    }

    /**
     * Reflection-backed uniform location: the name must be declared in a complete reflection
     * (exact uniform/sampler name, or a uniform-block member by bare or qualified name). A word
     * inside a comment or a similarly named identifier never counts. Missing uniforms yield -1.
     */
    public static int getUniformLocation(int virtualProgramId, CharSequence name) {
        if (name == null) {
            return -1;
        }
        VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
        if (program == null || VulkanShaderRegistry.isDeletedProgram(virtualProgramId)) {
            return -1;
        }
        String uniformName = name.toString();
        if (!isDeclaredUniform(program, baseName(uniformName))) {
            return -1;
        }
        String key = virtualProgramId + ":" + uniformName;
        return UNIFORM_LOCATIONS.computeIfAbsent(key, k -> UNIFORM_ID_GEN.incrementAndGet());
    }

    /**
     * Reflection-backed attribute location: exact match first, then case-insensitive, else -1.
     * No fuzzy substring guessing and no ad-hoc id allocation.
     */
    public static int getAttribLocation(int virtualProgramId, CharSequence name) {
        if (name == null) {
            return -1;
        }
        VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
        if (program == null || VulkanShaderRegistry.isDeletedProgram(virtualProgramId)) {
            return -1;
        }
        String wanted = name.toString();
        Integer exact = null;
        Integer folded = null;
        for (VulkairisShader stage : program.getAttachedShaders()) {
            if (!stage.getReflection().isComplete()) {
                continue;
            }
            for (ShaderReflection.VertexAttribute attribute
                    : stage.getReflection().getVertexAttributes()) {
                if (attribute.name().equals(wanted)) {
                    exact = attribute.location();
                    break;
                }
                if (folded == null && attribute.name().equalsIgnoreCase(wanted)) {
                    folded = attribute.location();
                }
            }
            if (exact != null) {
                break;
            }
        }
        if (exact != null) {
            return exact;
        }
        return folded != null ? folded : -1;
    }

    /**
     * Reflection-backed active-uniform discovery for the program-builder loop.
     *
     * @return the uniform name at {@code index}, or "" when out of range
     */
    public static String getActiveUniformAt(int virtualProgramId, int index) {
        List<String> names = activeUniformNames(virtualProgramId);
        if (index < 0 || index >= names.size()) {
            return "";
        }
        return names.get(index);
    }

    /**
     * Fills Iris's length/size buffers for a discovered uniform, mirroring
     * {@link #getActiveUniformAt(int, int)}.
     */
    public static void fillActiveUniformBuffers(
            int virtualProgramId, int index, int maxLength, IntBuffer length, IntBuffer size) {
        String name = getActiveUniformAt(virtualProgramId, index);
        if (name.isEmpty()) {
            return;
        }
        if (length != null && length.capacity() > 0) {
            length.put(0, Math.min(name.length(), maxLength));
        }
        if (size != null && size.capacity() > 0) {
            size.put(0, samplerDescriptorCount(virtualProgramId, name));
        }
    }

    /** @return sorted distinct active-uniform names across complete stage reflections */
    public static List<String> activeUniformNames(int virtualProgramId) {
        VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
        if (program == null) {
            return List.of();
        }
        TreeSet<String> names = new TreeSet<>();
        for (VulkairisShader stage : program.getAttachedShaders()) {
            ShaderReflection reflection = stage.getReflection();
            if (!reflection.isComplete()) {
                continue;
            }
            names.addAll(reflection.getUniforms().keySet());
            names.addAll(reflection.getSamplers().keySet());
            for (ShaderReflection.UniformBlock block : reflection.getUniformBlocks()) {
                for (ShaderReflection.UniformInfo member : block.members()) {
                    names.add(member.name());
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(names));
    }

    private static int vertexAttributeCount(int virtualProgramId) {
        VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
        if (program == null) {
            return 0;
        }
        int count = 0;
        for (VulkairisShader stage : program.getAttachedShaders()) {
            if (stage.getReflection().isComplete()) {
                count += stage.getReflection().getVertexAttributes().size();
            }
        }
        return count;
    }

    private static boolean isDeclaredUniform(VulkairisProgram program, String baseName) {
        if (baseName == null || baseName.isEmpty()) {
            return false;
        }
        for (VulkairisShader stage : program.getAttachedShaders()) {
            ShaderReflection reflection = stage.getReflection();
            if (!reflection.isComplete()) {
                continue;
            }
            if (reflection.getUniforms().containsKey(baseName)
                    || reflection.getSamplers().containsKey(baseName)) {
                return true;
            }
            for (ShaderReflection.UniformBlock block : reflection.getUniformBlocks()) {
                for (ShaderReflection.UniformInfo member : block.members()) {
                    if (member.name().equals(baseName)) {
                        return true;
                    }
                }
            }
            for (String qualified : reflection.getUniforms().keySet()) {
                if (qualified.equals(baseName) || qualified.endsWith("." + baseName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int samplerDescriptorCount(int virtualProgramId, String name) {
        VulkairisProgram program = VulkanShaderRegistry.getProgram(virtualProgramId);
        if (program == null) {
            return 1;
        }
        for (VulkairisShader stage : program.getAttachedShaders()) {
            ShaderReflection.SamplerBinding binding =
                    stage.getReflection().getSamplers().get(name);
            if (binding != null) {
                return Math.max(1, binding.descriptorCount());
            }
        }
        return 1;
    }

    private static String baseName(String uniformName) {
        int bracket = uniformName.indexOf('[');
        if (bracket > 0) {
            return uniformName.substring(0, bracket);
        }
        return uniformName;
    }

    /** Clears allocated uniform locations; tests only. */
    public static void clearLocationsForTests() {
        UNIFORM_LOCATIONS.clear();
        UNIFORM_ID_GEN.set(100);
    }
}
