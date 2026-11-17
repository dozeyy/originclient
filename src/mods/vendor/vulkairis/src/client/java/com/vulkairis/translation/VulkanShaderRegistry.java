package com.vulkairis.translation;

import com.vulkairis.core.ShaderLifecycleState;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry mapping virtual Iris shader/program IDs to Vulkan VkShaderModule handles,
 * VulkairisShader objects, and VulkairisProgram pipelines.
 */
public class VulkanShaderRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/ShaderRegistry");
    private static final AtomicInteger SHADER_ID_GEN = new AtomicInteger(1000);
    private static final AtomicInteger PROGRAM_ID_GEN = new AtomicInteger(5000);

    public record ShaderData(int virtualId, String name, int stage, long vkShaderModule, ByteBuffer spirv, String glslSource) {}
    public record ProgramData(int virtualId, String name, List<Integer> attachedShaderIds) {}

    private static final Map<Integer, VulkairisShader> SHADERS = new ConcurrentHashMap<>();
    private static final Map<Integer, VulkairisProgram> PROGRAMS = new ConcurrentHashMap<>();

    /** Retained info logs, keyed by virtual shader id (compiler log or rejection reason). */
    private static final Map<Integer, String> SHADER_LOGS = new ConcurrentHashMap<>();
    /** Retained info logs, keyed by virtual program id (link problems or empty on success). */
    private static final Map<Integer, String> PROGRAM_LOGS = new ConcurrentHashMap<>();
    /** Explicit link outcomes, keyed by virtual program id (absent = not yet validated). */
    private static final Map<Integer, GlObjectState> PROGRAM_LINK = new ConcurrentHashMap<>();
    /** Tombstones: deleted ids fail every query predictably and are never recycled. */
    private static final java.util.Set<Integer> DELETED_SHADERS =
            ConcurrentHashMap.newKeySet();
    private static final java.util.Set<Integer> DELETED_PROGRAMS =
            ConcurrentHashMap.newKeySet();

    public static long createVkShaderModule(ByteBuffer spirv) {
        return ShaderModuleFactory.createShaderModule(spirv);
    }

    public static int registerShader(String name, int stage, ByteBuffer spirv, String source) {
        return registerShader(name, stage, spirv, source, new ShaderReflection());
    }

    public static int registerShader(String name, int stage, ByteBuffer spirv, String source, ShaderReflection reflection) {
        int virtualId = SHADER_ID_GEN.incrementAndGet();
        long vkModule = 0L;
        if (spirv != null && spirv.hasRemaining()) {
            try {
                vkModule = ShaderModuleFactory.createShaderModule(spirv);
            } catch (RuntimeException e) {
                LOGGER.error("[Vulkairis] Error creating VkShaderModule for '{}': {}", name, e.getMessage(), e);
            }
        }

        VulkairisShader shader = new VulkairisShader(virtualId, name, stage, vkModule, spirv, source,
                reflection != null ? reflection.getEntryPointName() : "main", reflection);
        SHADERS.put(virtualId, shader);
        com.vulkairis.resource.VulkairisResourceManager.getInstance().createResource(
                com.vulkairis.resource.ManagedResource.ResourceType.SHADER, virtualId, name, "Iris", vkModule);
        LOGGER.info("[Vulkairis] Registered shader handle {} -> '{}' (stage: {}, vkModule: 0x{})",
                virtualId, name, stage, Long.toHexString(vkModule));
        return virtualId;
    }

    public static int registerShader(VulkairisShader shader) {
        SHADERS.put(shader.getVirtualId(), shader);
        com.vulkairis.resource.VulkairisResourceManager.getInstance().createResource(
                com.vulkairis.resource.ManagedResource.ResourceType.SHADER, shader.getVirtualId(), shader.getName(), "Iris", shader.getVkShaderModule());
        return shader.getVirtualId();
    }

    public static int registerProgram(String name, List<Integer> attachedShaderIds) {
        int virtualProgramId = PROGRAM_ID_GEN.incrementAndGet();
        VulkairisProgram program = new VulkairisProgram(virtualProgramId, name, attachedShaderIds);
        PROGRAMS.put(virtualProgramId, program);
        com.vulkairis.resource.VulkairisResourceManager.getInstance().createResource(
                com.vulkairis.resource.ManagedResource.ResourceType.PROGRAM, virtualProgramId, name, "Iris", 0L);
        LOGGER.info("[Vulkairis] Registered program handle {} -> '{}' with shaders {}",
                virtualProgramId, name, attachedShaderIds);
        return virtualProgramId;
    }

    public static VulkairisShader getShader(int virtualId) {
        return SHADERS.get(virtualId);
    }

    public static ShaderData getShaderData(int virtualId) {
        VulkairisShader s = SHADERS.get(virtualId);
        if (s == null) return null;
        return new ShaderData(s.virtualId(), s.name(), s.stage(), s.vkShaderModule(), s.spirv(), s.glslSource());
    }

    public static VulkairisProgram getProgram(int virtualProgramId) {
        return PROGRAMS.get(virtualProgramId);
    }

    /**
     * Finds a registered program by its source name (e.g. {@code composite1}, {@code final}).
     *
     * <p>Iris registers programs by virtual id; the render-graph wiring needs to resolve a
     * discovered program name to the compiled pipeline, so this scans the registry once.</p>
     *
     * @param name the program source name
     * @return the matching program, or {@code null} if none is registered
     */
    public static VulkairisProgram findProgramByName(String name) {
        if (name == null) {
            return null;
        }
        for (VulkairisProgram program : PROGRAMS.values()) {
            if (name.equals(program.getName())) {
                return program;
            }
        }
        return null;
    }

    /** @return the number of registered shaders (diagnostic/architecture-report helper) */
    public static int registeredShaderCount() {
        return SHADERS.size();
    }

    /** @return the number of registered programs (diagnostic/architecture-report helper) */
    public static int registeredProgramCount() {
        return PROGRAMS.size();
    }

    public static ProgramData getProgramData(int virtualProgramId) {
        VulkairisProgram p = PROGRAMS.get(virtualProgramId);
        if (p == null) return null;
        return new ProgramData(p.virtualProgramId(), p.name(), p.attachedShaderIds());
    }

    public static void deleteShader(int virtualId) {
        com.vulkairis.resource.VulkairisResourceManager.getInstance().releaseResource(virtualId);
        VulkairisShader shader = SHADERS.remove(virtualId);
        if (shader != null) {
            shader.destroy();
        }
        SHADER_LOGS.remove(virtualId);
        // Tombstone: the id stays known-deleted so queries fail predictably. Ids are never
        // reused, so a tombstone can never alias a live native handle.
        DELETED_SHADERS.add(virtualId);
    }

    public static void deleteProgram(int virtualProgramId) {
        com.vulkairis.resource.VulkairisResourceManager.getInstance().releaseResource(virtualProgramId);
        VulkairisProgram program = PROGRAMS.remove(virtualProgramId);
        if (program != null) {
            program.destroy();
        }
        PROGRAM_LOGS.remove(virtualProgramId);
        PROGRAM_LINK.remove(virtualProgramId);
        DELETED_PROGRAMS.add(virtualProgramId);
    }

    /**
     * Advances the lifecycle state of a registered shader.
     *
     * @param virtualId the virtual shader id
     * @param next the target lifecycle state
     * @return {@code true} if a registered shader was found (its own forward-only check still applies)
     */
    public static boolean advanceShader(int virtualId, ShaderLifecycleState next) {
        VulkairisShader shader = SHADERS.get(virtualId);
        if (shader == null) return false;
        shader.advanceLifecycle(next);
        return true;
    }

    /**
     * Advances the lifecycle state of a registered program.
     *
     * @param programId the virtual program id
     * @param next the target lifecycle state
     * @return {@code true} if a registered program was found (its own forward-only check still applies)
     */
    public static boolean advanceProgram(int programId, ShaderLifecycleState next) {
        VulkairisProgram program = PROGRAMS.get(programId);
        if (program == null) return false;
        program.advanceLifecycle(next);
        return true;
    }

    public static void cleanUp() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        for (VulkairisProgram program : PROGRAMS.values()) {
            program.destroy(device);
        }
        for (VulkairisShader shader : SHADERS.values()) {
            shader.destroy(device);
        }
        SHADERS.clear();
        PROGRAMS.clear();
        SHADER_LOGS.clear();
        PROGRAM_LOGS.clear();
        PROGRAM_LINK.clear();
        DELETED_SHADERS.clear();
        DELETED_PROGRAMS.clear();
        com.vulkairis.bridge.VulkanModProgramBridge.cleanUp();
        LOGGER.info("[Vulkairis] Cleaned up VulkanShaderRegistry.");
    }

    /** @return whether the shader id is a known-deleted tombstone */
    public static boolean isDeletedShader(int virtualId) {
        return DELETED_SHADERS.contains(virtualId);
    }

    /** @return whether the program id is a known-deleted tombstone */
    public static boolean isDeletedProgram(int virtualProgramId) {
        return DELETED_PROGRAMS.contains(virtualProgramId);
    }

    /** Retains the info log Iris status queries return for a shader ("" = clean). */
    public static void setShaderInfoLog(int virtualId, String log) {
        if (log == null || log.isBlank()) {
            SHADER_LOGS.remove(virtualId);
        } else {
            SHADER_LOGS.put(virtualId, log);
        }
    }

    /** @return the retained shader info log, or "" when clean/unknown */
    public static String getShaderInfoLog(int virtualId) {
        if (DELETED_SHADERS.contains(virtualId)) {
            return "shader " + virtualId + " was deleted";
        }
        return SHADER_LOGS.getOrDefault(virtualId, "");
    }

    /** Retains the info log Iris status queries return for a program ("" = linked clean). */
    public static void setProgramInfoLog(int virtualProgramId, String log) {
        if (log == null || log.isBlank()) {
            PROGRAM_LOGS.remove(virtualProgramId);
        } else {
            PROGRAM_LOGS.put(virtualProgramId, log);
        }
    }

    /** @return the retained program info log, or "" when clean/unknown */
    public static String getProgramInfoLog(int virtualProgramId) {
        if (DELETED_PROGRAMS.contains(virtualProgramId)) {
            return "program " + virtualProgramId + " was deleted";
        }
        return PROGRAM_LOGS.getOrDefault(virtualProgramId, "");
    }

    /** Records the explicit link outcome for a program. */
    public static void setProgramLinkState(int virtualProgramId, GlObjectState state) {
        if (state == GlObjectState.LINKED || state == GlObjectState.FAILED) {
            PROGRAM_LINK.put(virtualProgramId, state);
        } else {
            PROGRAM_LINK.remove(virtualProgramId);
        }
    }

    /** @return the recorded link outcome, or {@code null} when not yet validated */
    public static GlObjectState getProgramLinkState(int virtualProgramId) {
        return PROGRAM_LINK.get(virtualProgramId);
    }
}
