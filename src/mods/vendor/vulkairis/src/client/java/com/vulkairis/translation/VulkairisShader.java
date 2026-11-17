package com.vulkairis.translation;

import com.vulkairis.core.ShaderLifecycleState;
import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

/**
 * First-class encapsulation of a compiled shader module in the Vulkairis bridge.
 * Retains Vulkan VkShaderModule handle, raw SPIR-V bytecode, GLSL source, and reflection metadata.
 *
 * <p>Retention policy (Phase 7): the translated GLSL source and SPIR-V bytes are CPU-owned data
 * retained <em>only</em> while the shader is registered — the VulkanMod bridge recompiles from
 * the source and diagnostics hash it. Deleting the shader from
 * {@code VulkanShaderRegistry} drops the registry reference so both buffers become collectable;
 * nothing retains them beyond the registry. The native module handle is device-owned and freed
 * exactly once via {@link #destroy}.</p>
 */
public class VulkairisShader {
    private static final Logger LIFECYCLE_LOGGER = LoggerFactory.getLogger("Vulkairis/Lifecycle");

    private final int virtualId;
    private final String name;
    private final int stage;
    private long vkShaderModule;
    private final ByteBuffer spirv;
    private final String glslSource;
    private final String entryPoint;
    private final ShaderReflection reflection;
    private volatile ShaderLifecycleState lifecycleState = ShaderLifecycleState.DISCOVERED;
    /** Device generation at creation; see {@link VulkanBackendAdapter#currentGeneration()}. */
    private final long deviceGeneration;

    public VulkairisShader(int virtualId, String name, int stage, long vkShaderModule,
                           ByteBuffer spirv, String glslSource, String entryPoint,
                           ShaderReflection reflection) {
        this.virtualId = virtualId;
        this.name = name != null ? name : "unnamed_shader";
        this.stage = stage;
        this.vkShaderModule = vkShaderModule;
        this.spirv = spirv;
        this.glslSource = glslSource;
        this.entryPoint = (entryPoint != null && !entryPoint.isEmpty()) ? entryPoint : "main";
        this.reflection = reflection != null ? reflection : new ShaderReflection();
        this.deviceGeneration = VulkanBackendAdapter.currentGeneration();
        initializeLifecycleState();
    }

    public VulkairisShader(int virtualId, String name, int stage, long vkShaderModule,
                           ByteBuffer spirv, String glslSource) {
        this(virtualId, name, stage, vkShaderModule, spirv, glslSource, "main", new ShaderReflection());
    }

    public int getVirtualId() {
        return virtualId;
    }

    public int virtualId() {
        return virtualId;
    }

    public String getName() {
        return name;
    }

    public String name() {
        return name;
    }

    public int getStage() {
        return stage;
    }

    public int stage() {
        return stage;
    }

    public long getVkShaderModule() {
        return vkShaderModule;
    }

    public long vkShaderModule() {
        return vkShaderModule;
    }

    public ByteBuffer getSpirv() {
        return spirv;
    }

    public ByteBuffer spirv() {
        return spirv;
    }

    public String getGlslSource() {
        return glslSource;
    }

    public String glslSource() {
        return glslSource;
    }

    public String getEntryPoint() {
        return entryPoint;
    }

    public ShaderReflection getReflection() {
        return reflection;
    }

    /** @return the device generation that created this shader's handles */
    public long getDeviceGeneration() {
        return deviceGeneration;
    }

    /** @return whether the device was recreated since this shader was created */
    public boolean isStale() {
        return VulkanBackendAdapter.isStaleGeneration(deviceGeneration);
    }

    public boolean isValid() {
        return vkShaderModule != 0L;
    }

    public boolean hasSpirv() {
        return spirv != null && spirv.hasRemaining();
    }

    public int getVkStageFlag() {
        // GL constants vs Vulkan stage flags
        return switch (stage) {
            case 35633 -> VK10.VK_SHADER_STAGE_VERTEX_BIT;   // GL_VERTEX_SHADER
            case 35632 -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT; // GL_FRAGMENT_SHADER
            case 36313 -> VK10.VK_SHADER_STAGE_GEOMETRY_BIT; // GL_GEOMETRY_SHADER
            case 37305 -> VK10.VK_SHADER_STAGE_COMPUTE_BIT;  // GL_COMPUTE_SHADER
            case 1 -> VK10.VK_SHADER_STAGE_VERTEX_BIT;       // internal stage index
            case 2 -> VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
            default -> {
                if (name.contains("vert")) yield VK10.VK_SHADER_STAGE_VERTEX_BIT;
                if (name.contains("frag")) yield VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
                if (name.contains("comp")) yield VK10.VK_SHADER_STAGE_COMPUTE_BIT;
                yield VK10.VK_SHADER_STAGE_ALL_GRAPHICS;
            }
        };
    }

    /**
     * Returns the current lifecycle state of this shader. Never {@code null}.
     */
    public ShaderLifecycleState getLifecycleState() {
        return lifecycleState;
    }

    /**
     * Advances this shader along its execution lifecycle. Only forward moves are honoured;
     * attempts to move backwards (or repeat the current state) are ignored. Transitions are logged
     * at INFO on the {@code Vulkairis/Lifecycle} logger.
     *
     * @param next the target state; {@code null} is ignored
     */
    public synchronized void advanceLifecycle(ShaderLifecycleState next) {
        if (next == null) return;

        ShaderLifecycleState prev = lifecycleState;
        if (prev == next || !prev.canTransitionTo(next)) {
            return;
        }

        lifecycleState = next;
        try {
            LIFECYCLE_LOGGER.info("[Vulkairis] Shader '{}' {} -> {}", name, prev, next);
        } catch (Throwable ignored) {
            // Logging must never interfere with shader bookkeeping.
        }
    }

    /**
     * Derives the initial lifecycle state from the data actually present on this shader, then
     * promotes to {@link ShaderLifecycleState#REFLECTED} when complete reflection metadata exists.
     * Called at the end of the main constructor; must never throw.
     */
    private synchronized void initializeLifecycleState() {
        try {
            ShaderLifecycleState initial;
            if (vkShaderModule != 0L) {
                initial = ShaderLifecycleState.MODULE_CREATED;
            } else if (spirv != null && spirv.hasRemaining()) {
                initial = ShaderLifecycleState.SPIRV_VALID;
            } else if (glslSource != null && !glslSource.isBlank()) {
                initial = ShaderLifecycleState.COMPILED;
            } else {
                initial = ShaderLifecycleState.DISCOVERED;
            }
            this.lifecycleState = initial;

            if (reflection != null && reflection.isComplete()
                    && lifecycleState.isAtLeast(ShaderLifecycleState.SPIRV_VALID)) {
                advanceLifecycle(ShaderLifecycleState.REFLECTED);
            }
        } catch (Throwable ignored) {
            // Lifecycle tracking must never break shader construction.
        }
    }

    public synchronized void destroy(VkDevice device) {
        if (vkShaderModule != 0L) {
            ShaderModuleFactory.destroyShaderModule(device, vkShaderModule);
            vkShaderModule = 0L;
        }
    }

    public synchronized void destroy() {
        destroy(VulkanBackendAdapter.getDevice());
    }

    @Override
    public String toString() {
        return "VulkairisShader{" +
                "id=" + virtualId +
                ", name='" + name + '\'' +
                ", stage=" + stage +
                ", vkModule=0x" + Long.toHexString(vkShaderModule) +
                ", spirvBytes=" + (spirv != null ? spirv.remaining() : 0) +
                '}';
    }
}
