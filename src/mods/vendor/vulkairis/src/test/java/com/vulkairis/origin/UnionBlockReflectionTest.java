package com.vulkairis.origin;

import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderCompileResult;
import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.List;

/**
 * Origin (step 3b): the union translator injects an ANONYMOUS {@code VulkairisUniformBlock}; reflection
 * named it "" and the pipeline build failed every Iris program with "Descriptor 'VulkairisUniformBlock'
 * ... has no reflected block" (probe run 44008144043). The block must reflect under its type name.
 */
class UnionBlockReflectionTest {
    @Test
    void injectedUnionBlockReflectsByTypeName() {
        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate("composite", List.of(
                new ProgramTranslator.StageUnit("VERTEX", """
                        #version 130
                        uniform float viewWidth;
                        out vec2 texcoord;
                        void main() {
                            texcoord = gl_MultiTexCoord0.xy * viewWidth;
                            gl_Position = ftransform();
                        }
                        """, "composite.vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", """
                        #version 130
                        uniform float frameTimeCounter;
                        in vec2 texcoord;
                        void main() {
                            gl_FragData[0] = vec4(texcoord, frameTimeCounter, 1.0);
                        }
                        """, "composite.fsh")), "origin-test");
        String frag = program.stages().get("FRAGMENT").glsl();
        ShaderCompileResult f = SpirvCompiler.compileGlslToSpirvDetailed(
                "composite.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
        Assertions.assertTrue(f.succeeded(), f.compilerLog() + "\n" + frag);

        List<ShaderReflection.UniformBlock> blocks = SpirvReflector.reflect(f.spirv()).getUniformBlocks();
        Assertions.assertTrue(blocks.stream().anyMatch(b -> b.name().equals(ProgramTranslator.UNIFORM_BLOCK_NAME)
                        && b.members().stream().anyMatch(m -> m.name().equals("frameTimeCounter"))),
                blocks + "\n" + frag);
    }
}
