package com.vulkairis.test;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

public class ShaderCompilerTest {

    @Test
    public void testInProcessShadercCompilation() {
        long compiler = Shaderc.shaderc_compiler_initialize();
        Assertions.assertNotEquals(0L, compiler, "Shaderc compiler should initialize");

        long options = Shaderc.shaderc_compile_options_initialize();
        Assertions.assertNotEquals(0L, options, "Shaderc options should initialize");

        Shaderc.shaderc_compile_options_set_target_env(
                options,
                Shaderc.shaderc_target_env_vulkan,
                Shaderc.shaderc_env_version_vulkan_1_1
        );
        Shaderc.shaderc_compile_options_set_invert_y(options, true);

        String vertSource = """
                #version 450 core
                layout (location = 0) in vec3 Position;
                layout (location = 1) in vec4 Color;
                layout (location = 0) out vec4 vertexColor;
                void main() {
                    gl_Position = vec4(Position, 1.0);
                    vertexColor = Color;
                }
                """;

        long result = Shaderc.shaderc_compile_into_spv(
                compiler,
                vertSource,
                Shaderc.shaderc_glsl_vertex_shader,
                "test_vert.vsh",
                "main",
                options
        );

        Assertions.assertNotEquals(0L, result, "Compilation result pointer should not be 0");

        int status = Shaderc.shaderc_result_get_compilation_status(result);
        if (status != Shaderc.shaderc_compilation_status_success) {
            String errorMsg = Shaderc.shaderc_result_get_error_message(result);
            Shaderc.shaderc_result_release(result);
            Assertions.fail("GLSL -> SPIR-V compilation failed: " + errorMsg);
        }

        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
        Assertions.assertNotNull(spv, "SPIR-V bytes should not be null");
        Assertions.assertTrue(spv.remaining() > 0, "SPIR-V bytecode size must be > 0");

        // Verify SPIR-V magic number (0x07230203) in little endian
        int magic = spv.asIntBuffer().get(0);
        Assertions.assertEquals(0x07230203, magic, "SPIR-V magic number must match 0x07230203");

        System.out.println("[Test] Successfully compiled GLSL to SPIR-V, size: " + spv.remaining() + " bytes, magic: 0x" + Integer.toHexString(magic));

        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(options);
        Shaderc.shaderc_compiler_release(compiler);
    }

    @Test
    public void testCenterDepthCompilation() {
        String vertSource = """
                #version 150 core

                in vec3 iris_Position;
                uniform mat4 projection;

                void main() {
                    gl_Position = projection * vec4(iris_Position, 1.0);
                }
                """;

        String preprocessed = ShaderTranslator.preprocessGlsl(vertSource, true);
        System.out.println("Preprocessed shader:\n" + preprocessed);

        ByteBuffer spv = SpirvCompiler.compileGlslToSpirv("centerDepth.vsh", preprocessed, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertNotNull(spv);
        Assertions.assertTrue(spv.remaining() > 0);
        System.out.println("[Test] centerDepth.vsh successfully compiled to SPIR-V: " + spv.remaining() + " bytes");
    }

    /**
     * Regression: Iris uniforms carry default initializers (e.g. {@code uniform bool heavyFog = false;}).
     * Those initializers are illegal inside the injected uniform block, so preprocessing must drop them
     * and the resulting module must still compile to SPIR-V. Also covers arrayed and multi-declarator
     * uniforms and idempotency of the transform.
     */
    @Test
    public void testDefaultInitializedUniformsCompile() {
        String vertSource = """
                #version 150 core

                in vec3 iris_Position;

                uniform mat4 projection;
                uniform bool heavyFog = false;
                uniform float cloudHeight = 192.0;
                uniform vec4 fogColor = vec4(0.5, 0.6, 0.7, 1.0);
                uniform float weights[4];
                uniform float scaleA, scaleB;

                out float fogAmount;
                out vec4 tint;

                void main() {
                    fogAmount = heavyFog ? cloudHeight : weights[0];
                    tint = fogColor * (scaleA + scaleB);
                    gl_Position = projection * vec4(iris_Position, 1.0);
                }
                """;

        String preprocessed = ShaderTranslator.preprocessGlsl(vertSource, true);

        Assertions.assertTrue(preprocessed.contains("VulkairisUniformBlock"),
                "Preprocessor must inject the uniform block");
        Assertions.assertTrue(preprocessed.contains("bool heavyFog;"),
                "Initializer must be dropped from block member: " + preprocessed);
        Assertions.assertFalse(preprocessed.contains("heavyFog = false"),
                "Default initializer must not survive inside the block");
        Assertions.assertTrue(preprocessed.contains("float cloudHeight;"),
                "Initializer must be dropped from block member: " + preprocessed);
        Assertions.assertTrue(preprocessed.contains("vec4 fogColor;"),
                "Initializer must be dropped from block member: " + preprocessed);
        Assertions.assertTrue(preprocessed.contains("float weights[4];"),
                "Array declarators must be preserved in the block: " + preprocessed);
        Assertions.assertTrue(preprocessed.contains("float scaleA;")
                        && preprocessed.contains("float scaleB;"),
                "Multi-declarator uniforms must become separate members: " + preprocessed);

        // Applying the transform twice must not inject a second block header.
        String twice = ShaderTranslator.preprocessGlsl(preprocessed, true);
        Assertions.assertEquals(1, countOccurrences(twice, "uniform VulkairisUniformBlock"),
                "Preprocessing must be idempotent");

        ByteBuffer spv = SpirvCompiler.compileGlslToSpirv(
                "defaulted_uniforms.vsh", preprocessed, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertNotNull(spv, "SPIR-V must be produced for defaulted uniforms");
        Assertions.assertTrue(spv.remaining() > 0, "SPIR-V bytecode must not be empty");

        ShaderReflection reflection = SpirvReflector.reflect(spv);
        Assertions.assertTrue(reflection.isComplete(), "Reflection of a valid module must be complete");
        System.out.println("[Test] Uniforms: " + reflection.getUniforms().keySet());
        System.out.println("[Test] Blocks: " + reflection.getUniformBlocks().stream()
                .map(b -> b.name() + "@" + b.binding()).toList());
        Assertions.assertTrue(reflection.getUniforms().keySet().stream()
                        .anyMatch(k -> k.equals("heavyFog") || k.endsWith(".heavyFog")),
                "Reflection must expose the block member for heavyFog; keys="
                        + reflection.getUniforms().keySet());
        Assertions.assertTrue(reflection.getUniformBlocks().stream()
                        .anyMatch(b -> b.binding() == 100 && b.members().stream()
                                .anyMatch(m -> m.name().equals("heavyFog"))),
                "Injected block must be reflected at its stable binding with heavyFog as a member");

        System.out.println("[Test] Default-initialized uniforms preprocessed and compiled: "
                + spv.remaining() + " bytes");
    }

    /** Regression: an arrayed sampler is one descriptor binding with count 4, not fabricated [1..3] entries. */
    @Test
    public void testArrayedSamplerReflection() {
        String fragSource = """
                #version 450 core

                layout (binding = 0) uniform sampler2D tex[4];
                layout (location = 0) in vec2 vUv;
                layout (location = 0) out vec4 fragColor;

                void main() {
                    fragColor = texture(tex[0], vUv) + texture(tex[1], vUv)
                              + texture(tex[2], vUv) + texture(tex[3], vUv);
                }
                """;

        ByteBuffer spv = SpirvCompiler.compileGlslToSpirv(
                "arrayed_sampler.fsh", fragSource, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertNotNull(spv, "SPIR-V must be produced for the arrayed sampler");
        Assertions.assertTrue(spv.remaining() > 0, "SPIR-V bytecode must not be empty");

        ShaderReflection reflection = SpirvReflector.reflect(spv);
        Assertions.assertTrue(reflection.isComplete(), "Reflection of a valid module must be complete");
        Assertions.assertEquals(1, reflection.getSamplers().size(),
                "Arrayed sampler must be exactly one binding, not one per element");

        ShaderReflection.SamplerBinding sampler = reflection.getSamplers().get("tex");
        Assertions.assertNotNull(sampler, "Reflection must expose the arrayed sampler by name");
        Assertions.assertEquals(0, sampler.binding(), "Arrayed sampler binding must be preserved");
        Assertions.assertEquals(4, sampler.descriptorCount(),
                "Arrayed sampler must report a descriptor count of 4");

        System.out.println("[Test] Arrayed sampler reflected as binding " + sampler.binding()
                + " with descriptor count " + sampler.descriptorCount());
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
