package com.vulkairis.origin;

import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import com.vulkairis.vulkan.pipeline.PipelineCompatibilityValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.List;

/**
 * Origin (step 3b): MakeUp Ultra Fast's composite declares its varyings in a different order in each
 * stage. Shaderc's auto-mapping numbered each stage on its own, so the linker saw "fragment input
 * 'volumetricLightColor' expects 3 components but vertex output 'exposure' provides 1" and refused
 * the program. Locations are now assigned per NAME, so the reflected interfaces agree.
 */
class VaryingLocationTest {
    private static final String VERTEX = """
            #version 130
            out vec2 texcoord;
            flat out float exposure;
            out vec3 volumetricLightColor;
            out vec3 extra;
            void main() {
                texcoord = gl_MultiTexCoord0.xy;
                exposure = 1.0;
                volumetricLightColor = vec3(0.5);
                extra = vec3(0.0);
                gl_Position = ftransform();
            }
            """;

    private static final String FRAGMENT = """
            #version 130
            in vec3 volumetricLightColor;
            in vec2 texcoord;
            flat in float exposure;
            void main() {
                gl_FragData[0] = vec4(volumetricLightColor * exposure, texcoord.x);
            }
            """;

    @Test
    void reorderedVaryingsLinkByName() {
        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate("composite", List.of(
                new ProgramTranslator.StageUnit("VERTEX", VERTEX, "composite.vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", FRAGMENT, "composite.fsh")), "origin-test");
        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        ShaderCompileResult v = SpirvCompiler.compileGlslToSpirvDetailed(
                "composite.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        ShaderCompileResult f = SpirvCompiler.compileGlslToSpirvDetailed(
                "composite.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertTrue(v.succeeded(), v.compilerLog() + "\n" + vert);
        Assertions.assertTrue(f.succeeded(), f.compilerLog() + "\n" + frag);

        List<String> problems = PipelineCompatibilityValidator.validate(
                SpirvReflector.reflect(v.spirv()), SpirvReflector.reflect(f.spirv()), 8);
        Assertions.assertEquals(List.of(), problems, vert + "\n" + frag);
    }

    /** Iris's centerDepthSmooth: no varyings at all is a valid program, not "reflection empty". */
    @Test
    void programWithoutVaryingsIsValid() {
        ShaderCompileResult v = SpirvCompiler.compileGlslToSpirvDetailed("cds.vsh", """
                #version 450 core
                layout(location = 0) in vec3 Position;
                void main() { gl_Position = vec4(Position, 1.0); }
                """, Shaderc.shaderc_glsl_vertex_shader);
        ShaderCompileResult f = SpirvCompiler.compileGlslToSpirvDetailed("cds.fsh", """
                #version 450 core
                layout(binding = 0) uniform sampler2D depth;
                layout(location = 0) out vec4 color;
                void main() { color = vec4(texture(depth, vec2(0.5)).r); }
                """, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertTrue(v.succeeded() && f.succeeded(), v.compilerLog() + f.compilerLog());
        Assertions.assertEquals(List.of(), PipelineCompatibilityValidator.validate(
                SpirvReflector.reflect(v.spirv()), SpirvReflector.reflect(f.spirv()), 8));
    }
}
