package com.vulkairis.test;

import com.vulkairis.core.DebugRenderMode;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Device-free portion of the end-to-end regression test described in the implementation plan
 * (section 25). It fails if the shader bridge silently degrades to source-only metadata: the
 * SPIR-V must compile, reflection must be non-empty, and an intercepted program must resolve to
 * real {@link VulkairisShader} objects.
 *
 * <p>The live {@code VkShaderModule}/{@code VkPipeline}/draw portion requires a running Vulkan
 * device and is exercised by the controlled renderer at runtime.</p>
 */
public class PipelineRegressionTest {

    private static final String VERT = """
            #version 450 core
            layout (location = 0) in vec3 Position;
            layout (location = 1) in vec4 Color;
            layout (location = 0) out vec2 vUv;
            layout (std140, binding = 2) uniform CameraBlock {
                mat4 mvp;
                vec4 cameraPos;
            } camera;
            void main() {
                gl_Position = camera.mvp * vec4(Position, 1.0);
                vUv = Color.xy;
            }
            """;

    private static final String FRAG = """
            #version 450 core
            layout (binding = 1) uniform sampler2D tex;
            layout (location = 0) in vec2 vUv;
            layout (location = 0) out vec4 fragColor;
            void main() {
                fragColor = texture(tex, vUv);
            }
            """;

    @Test
    public void testSpirvReflectionIsNonEmpty() {
        VulkairisShader vert = SpirvCompiler.compileShader("regression_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("regression_frag", FRAG, ShaderType.FRAGMENT);

        Assertions.assertNotNull(vert, "Vertex shader must be registered");
        Assertions.assertNotNull(frag, "Fragment shader must be registered");
        Assertions.assertTrue(vert.hasSpirv(), "Vertex shader must have real SPIR-V bytecode");
        Assertions.assertTrue(frag.hasSpirv(), "Fragment shader must have real SPIR-V bytecode");

        Assertions.assertFalse(vert.getReflection().getVertexAttributes().isEmpty(),
                "Vertex reflection must discover input attributes from SPIR-V");
        Assertions.assertTrue(vert.getReflection().getVertexAttributes().stream()
                        .anyMatch(a -> a.location() == 0),
                "Vertex reflection must report a location-0 attribute");
        Assertions.assertTrue(vert.getReflection().getUboBlockSize() >= 64,
                "Vertex reflection must size the std140 uniform block (got "
                        + vert.getReflection().getUboBlockSize() + ")");
        Assertions.assertFalse(vert.getReflection().getUniformBlocks().isEmpty(),
                "Vertex reflection must record the uniform block (name/set/binding)");
        Assertions.assertTrue(vert.getReflection().getUniformBlocks().stream()
                        .anyMatch(b -> b.binding() == 2),
                "The CameraBlock must be reflected at binding 2");

        Assertions.assertFalse(frag.getReflection().getSamplers().isEmpty(),
                "Fragment reflection must discover the bound sampler from SPIR-V");

        System.out.println(String.format(
                "[Test] Reflection: vert inputs=%d, ubo=%d bytes; frag samplers=%d",
                vert.getReflection().getVertexAttributes().size(),
                vert.getReflection().getUboBlockSize(),
                frag.getReflection().getSamplers().size()));
    }

    @Test
    public void testInterceptedProgramResolvesRealShaders() {
        VulkairisShader vert = SpirvCompiler.compileShader("regression_prog_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = SpirvCompiler.compileShader("regression_prog_frag", FRAG, ShaderType.FRAGMENT);

        int programId = VulkanShaderRegistry.registerProgram("regression_program",
                java.util.List.of(vert.getVirtualId(), frag.getVirtualId()));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(programId);

        Assertions.assertNotNull(program, "Intercepted program must resolve to a VulkairisProgram");
        Assertions.assertEquals(2, program.getAttachedShaders().size(),
                "Program must resolve both attached Vulkan shader objects");
        Assertions.assertNotNull(program.getVertexShader(), "Program must expose a vertex stage");
        Assertions.assertNotNull(program.getFragmentShader(), "Program must expose a fragment stage");

        VulkanShaderRegistry.deleteProgram(programId);
        VulkanShaderRegistry.deleteShader(vert.getVirtualId());
        VulkanShaderRegistry.deleteShader(frag.getVirtualId());
    }

    @Test
    public void testDebugRenderModeRoundTrip() {
        Assertions.assertEquals(DebugRenderMode.OFF, DebugRenderMode.fromString("off"));
        Assertions.assertEquals(DebugRenderMode.TRIANGLE, DebugRenderMode.fromString("triangle"));
        Assertions.assertEquals(DebugRenderMode.SHADER_TEST, DebugRenderMode.fromString("shader-test"));
        Assertions.assertEquals(DebugRenderMode.NORMAL, DebugRenderMode.fromString("NORMAL"));
        Assertions.assertTrue(DebugRenderMode.SHADER_TEST.isControlled());
        Assertions.assertFalse(DebugRenderMode.NORMAL.isControlled());
    }
}
