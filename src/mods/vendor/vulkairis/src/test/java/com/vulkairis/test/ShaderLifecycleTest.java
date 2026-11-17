package com.vulkairis.test;

import com.vulkairis.core.ShaderLifecycleState;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Device-free tests for the shader-execution lifecycle state machine (Phase 3).
 *
 * <p>These lock in the ordering contract that distinguishes "shader loaded" from "shader
 * executed": transitions may only move forward, {@link ShaderLifecycleState#isAtLeast} reflects
 * the real execution path, and a successfully compiled shader must reach at least
 * {@code SPIRV_VALID} and {@code REFLECTED}. The live {@code MODULE_CREATED}/{@code PIPELINE_CREATED}
 * and later stages require a running Vulkan device and are exercised at runtime.</p>
 */
public class ShaderLifecycleTest {

    private static final String VERT = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 0) out vec2 vUv;
            void main() {
                gl_Position = vec4(Position, 1.0);
                vUv = Position.xy;
            }
            """;

    @Test
    public void testForwardOnlyTransitions() {
        Assertions.assertTrue(ShaderLifecycleState.DISCOVERED.canTransitionTo(ShaderLifecycleState.COMPILED),
                "Adjacent forward move must be allowed");
        Assertions.assertTrue(ShaderLifecycleState.DISCOVERED.canTransitionTo(ShaderLifecycleState.PRESENTED),
                "Skipping stages forward must be allowed");
        Assertions.assertTrue(ShaderLifecycleState.COMPILED.canTransitionTo(ShaderLifecycleState.COMPILED),
                "No-op same-state move must be allowed");

        Assertions.assertFalse(ShaderLifecycleState.COMPILED.canTransitionTo(ShaderLifecycleState.DISCOVERED),
                "Backwards move must be rejected");
        Assertions.assertFalse(ShaderLifecycleState.PRESENTED.canTransitionTo(ShaderLifecycleState.BOUND),
                "Any move off the final state must be rejected");
        Assertions.assertFalse(ShaderLifecycleState.DRAW_EXECUTED.canTransitionTo(null),
                "Null target must be rejected");
    }

    @Test
    public void testIsAtLeast() {
        Assertions.assertTrue(ShaderLifecycleState.REFLECTED.isAtLeast(ShaderLifecycleState.REFLECTED),
                "A state is at least itself");
        Assertions.assertTrue(ShaderLifecycleState.PIPELINE_CREATED.isAtLeast(ShaderLifecycleState.SPIRV_VALID),
                "Later state must satisfy earlier predicates");
        Assertions.assertFalse(ShaderLifecycleState.COMPILED.isAtLeast(ShaderLifecycleState.MODULE_CREATED),
                "Earlier state must not satisfy later predicates");
        Assertions.assertFalse(ShaderLifecycleState.DISCOVERED.isAtLeast(null),
                "Null comparison must be false");
    }

    @Test
    public void testCompiledShaderReachesSpirvValidAndReflected() {
        VulkairisShader shader = SpirvCompiler.compileShader("lifecycle_vert", VERT, ShaderType.VERTEX);

        Assertions.assertNotNull(shader, "Compiled shader must be registered");
        ShaderLifecycleState state = shader.getLifecycleState();
        Assertions.assertNotNull(state, "Lifecycle state must never be null");
        Assertions.assertTrue(state.isAtLeast(ShaderLifecycleState.SPIRV_VALID),
                "Compiled shader must be at least SPIRV_VALID, was " + state);
        Assertions.assertTrue(state.isAtLeast(ShaderLifecycleState.REFLECTED),
                "Reflected shader must be at least REFLECTED, was " + state);

        System.out.println("[Test] Compiled shader lifecycle: " + state);
    }

    @Test
    public void testAdvanceLifecycleIsForwardOnly() {
        VulkairisShader shader = SpirvCompiler.compileShader("lifecycle_advance_vert", VERT, ShaderType.VERTEX);
        Assertions.assertNotNull(shader, "Compiled shader must be registered");

        ShaderLifecycleState before = shader.getLifecycleState();

        // Attempting to move backwards must be ignored.
        shader.advanceLifecycle(ShaderLifecycleState.DISCOVERED);
        Assertions.assertEquals(before, shader.getLifecycleState(),
                "Backwards lifecycle transition must be ignored");

        // A forward (possibly skipping) move must be accepted.
        shader.advanceLifecycle(ShaderLifecycleState.DRAW_EXECUTED);
        Assertions.assertEquals(ShaderLifecycleState.DRAW_EXECUTED, shader.getLifecycleState(),
                "Forward lifecycle transition must be accepted");

        // Even attempting to move backwards from a high state stays put.
        shader.advanceLifecycle(ShaderLifecycleState.REFLECTED);
        Assertions.assertEquals(ShaderLifecycleState.DRAW_EXECUTED, shader.getLifecycleState(),
                "State must not regress after a forward move");

        VulkanShaderRegistry.deleteShader(shader.getVirtualId());
    }
}
