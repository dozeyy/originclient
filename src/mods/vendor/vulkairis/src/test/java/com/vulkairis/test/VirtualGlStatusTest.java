package com.vulkairis.test;

import com.vulkairis.testpack.ShaderTestPacks;
import com.vulkairis.translation.GlObjectState;
import com.vulkairis.translation.VirtualGlFacade;
import com.vulkairis.translation.VulkanProgramLinker;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import com.vulkairis.translation.VulkairisShader;
import net.irisshaders.iris.gl.shader.ShaderType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Phase 5: virtual shader/program status answers truthfully from registry state and SPIR-V
 * reflection. Covers failed compilation, failed program link, deleted objects, missing
 * uniforms, comment-word lookups, attribute resolution and determinism.
 */
public class VirtualGlStatusTest {

    private static final String VERT = """
            #version 150 core
            in vec3 iris_Position;
            in vec2 iris_UV;
            uniform mat4 projection;
            out vec2 vUv;
            void main() {
                vUv = iris_UV;
                gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 150 core
            uniform sampler2D colortex0;
            uniform vec4 tintColor;
            in vec2 vUv;
            out vec4 fragColor;
            // shadowMapHint lives only in this comment and must never resolve.
            void main() {
                fragColor = texture(colortex0, vUv) * tintColor;
            }
            """;

    private static final String BROKEN = """
            #version 150 core
            out vec4 fragColor;
            void main() {
                this is not valid glsl;
            }
            """;

    private VulkairisProgram linkedPair() {
        VulkairisShader vert = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_frag", FRAG, ShaderType.FRAGMENT);
        Assertions.assertEquals(GlObjectState.COMPILED, VirtualGlFacade.shaderState(vert.virtualId()));
        Assertions.assertEquals(GlObjectState.COMPILED, VirtualGlFacade.shaderState(frag.virtualId()));

        int progId = VulkanShaderRegistry.registerProgram(
                "status_prog", List.of(vert.virtualId(), frag.virtualId()));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);
        VulkanProgramLinker.LinkResult result = VulkanProgramLinker.validate(program);
        Assertions.assertTrue(result.valid(), "test pair must link: " + result.errors());
        VulkanShaderRegistry.setProgramLinkState(progId, GlObjectState.LINKED);
        VulkanShaderRegistry.setProgramInfoLog(progId, "");
        return program;
    }

    @Test
    public void testCompiledShaderReportsSuccess() {
        VulkairisProgram program = linkedPair();
        int vertId = program.attachedShaderIds().get(0);
        Assertions.assertEquals(1, VirtualGlFacade.getShaderi(vertId, 35713),
                "COMPILE_STATUS must be true for a compiled shader");
        Assertions.assertEquals("", VirtualGlFacade.getShaderInfoLog(vertId),
                "clean shaders retain an empty log");
        Assertions.assertEquals(0, VirtualGlFacade.getShaderi(vertId, 35716),
                "empty log reports zero length");
    }

    @Test
    public void testFailedCompileReportsFailurePlusLog() {
        VulkairisShader broken = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_broken", BROKEN, ShaderType.FRAGMENT);
        Assertions.assertEquals(GlObjectState.FAILED, VirtualGlFacade.shaderState(broken.virtualId()));
        Assertions.assertEquals(0, VirtualGlFacade.getShaderi(broken.virtualId(), 35713),
                "COMPILE_STATUS must be false for a failed shader");
        Assertions.assertFalse(VirtualGlFacade.getShaderInfoLog(broken.virtualId()).isBlank(),
                "failed shaders retain their compiler message");
        Assertions.assertTrue(VirtualGlFacade.getShaderi(broken.virtualId(), 35716) > 0,
                "log length must cover the retained message");
    }

    @Test
    public void testLinkedProgramReportsLinkPlusCounts() {
        VulkairisProgram program = linkedPair();
        int progId = program.virtualProgramId();
        Assertions.assertEquals(GlObjectState.LINKED, VirtualGlFacade.programState(progId));
        Assertions.assertEquals(1, VirtualGlFacade.getProgrami(progId, 35714),
                "LINK_STATUS must be true only for explicitly linked programs");
        Assertions.assertEquals(2, VirtualGlFacade.getProgrami(progId, 35717),
                "attached-shader count must be real");
        int uniforms = VirtualGlFacade.getProgrami(progId, 35718);
        Assertions.assertTrue(uniforms >= 3,
                "ACTIVE_UNIFORMS must cover projection/tintColor/colortex0, got " + uniforms);
        Assertions.assertTrue(VirtualGlFacade.getProgrami(progId, 35721) >= 1,
                "reflected vertex attributes must be counted");
        Assertions.assertEquals("", VirtualGlFacade.getProgramInfoLog(progId));
    }

    @Test
    public void testFailedLinkReportsFailureWithNamesAndTypes() {
        var pack = ShaderTestPacks.interfaceMismatch();
        VulkairisShader vert = com.vulkairis.translation.SpirvCompiler.compileShader(
                pack.stages().get(0).name(), pack.stages().get(0).glsl(), ShaderType.VERTEX);
        VulkairisShader frag = com.vulkairis.translation.SpirvCompiler.compileShader(
                pack.stages().get(1).name(), pack.stages().get(1).glsl(), ShaderType.FRAGMENT);
        int progId = VulkanShaderRegistry.registerProgram(
                "status_bad_link", List.of(vert.virtualId(), frag.virtualId()));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);

        VulkanProgramLinker.LinkResult result = VulkanProgramLinker.validate(program);
        Assertions.assertFalse(result.valid(), "vec3 vs vec2 interface must not link");
        Assertions.assertTrue(result.errors().stream().anyMatch(e -> e.contains("vColor")),
                "problems must name the interface: " + result.errors());
        VulkanShaderRegistry.setProgramLinkState(progId, GlObjectState.FAILED);
        VulkanShaderRegistry.setProgramInfoLog(progId, result.log());

        Assertions.assertEquals(GlObjectState.FAILED, VirtualGlFacade.programState(progId));
        Assertions.assertEquals(0, VirtualGlFacade.getProgrami(progId, 35714),
                "LINK_STATUS must be false; success is never simulated");
        Assertions.assertFalse(VirtualGlFacade.getProgramInfoLog(progId).isBlank());
    }

    @Test
    public void testProgramWithMissingStageFailsLink() {
        VulkairisShader vert = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_lonely_vert", VERT, ShaderType.VERTEX);
        int progId = VulkanShaderRegistry.registerProgram(
                "status_lonely", List.of(vert.virtualId()));
        VulkanProgramLinker.LinkResult result =
                VulkanProgramLinker.validate(VulkanShaderRegistry.getProgram(progId));
        Assertions.assertFalse(result.valid());
        Assertions.assertTrue(result.errors().stream().anyMatch(e -> e.contains("fragment")),
                "missing fragment stage must be named: " + result.errors());
    }

    @Test
    public void testUniformLookupsAreReflectionBacked() {
        VulkairisProgram program = linkedPair();
        int progId = program.virtualProgramId();
        Assertions.assertTrue(VirtualGlFacade.getUniformLocation(progId, "projection") != -1);
        Assertions.assertTrue(VirtualGlFacade.getUniformLocation(progId, "tintColor") != -1);
        Assertions.assertTrue(VirtualGlFacade.getUniformLocation(progId, "colortex0") != -1);
        Assertions.assertEquals(-1, VirtualGlFacade.getUniformLocation(progId, "doesNotExist"),
                "missing uniforms yield -1");
        Assertions.assertEquals(-1, VirtualGlFacade.getUniformLocation(progId, "shadowMapHint"),
                "a word inside a comment must never resolve");
        Assertions.assertEquals(-1, VirtualGlFacade.getUniformLocation(progId, "tint"),
                "a similarly named identifier must never resolve");
        Assertions.assertEquals(-1, VirtualGlFacade.getUniformLocation(progId, null));
    }

    @Test
    public void testActiveUniformDiscoveryMatchesLocations() {
        VulkairisProgram program = linkedPair();
        int progId = program.virtualProgramId();
        int count = VirtualGlFacade.getProgrami(progId, 35718);
        Assertions.assertTrue(count > 0);
        for (int i = 0; i < count; i++) {
            String name = VirtualGlFacade.getActiveUniformAt(progId, i);
            Assertions.assertFalse(name.isBlank(), "every discovered index names a uniform");
            Assertions.assertTrue(VirtualGlFacade.getUniformLocation(progId, name) != -1,
                    "every discovered uniform resolves: " + name);
        }
        Assertions.assertEquals("", VirtualGlFacade.getActiveUniformAt(progId, count),
                "out-of-range discovery yields empty, not a fabricated name");
    }

    @Test
    public void testUniformLocationsAreDeterministic() {
        VulkairisProgram program = linkedPair();
        int progId = program.virtualProgramId();
        int first = VirtualGlFacade.getUniformLocation(progId, "projection");
        Assertions.assertEquals(first, VirtualGlFacade.getUniformLocation(progId, "projection"));
        Assertions.assertTrue(first != VirtualGlFacade.getUniformLocation(progId, "tintColor"),
                "distinct uniforms keep distinct locations");
    }

    @Test
    public void testAttributeLookupsAreReflectionBacked() {
        VulkairisProgram program = linkedPair();
        int progId = program.virtualProgramId();
        int position = VirtualGlFacade.getAttribLocation(progId, "Position");
        Assertions.assertTrue(position >= 0, "declared attributes resolve");
        Assertions.assertEquals(position, VirtualGlFacade.getAttribLocation(progId, "position"),
                "lookup is case-insensitive");
        Assertions.assertEquals(-1, VirtualGlFacade.getAttribLocation(progId, "NoSuchAttribute"));
        Assertions.assertEquals(-1, VirtualGlFacade.getAttribLocation(progId, null));
    }

    @Test
    public void testDeletedIdsFailPredictably() {
        VulkairisShader doomed = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_doomed", VERT, ShaderType.VERTEX);
        int shaderId = doomed.virtualId();
        VulkanShaderRegistry.deleteShader(shaderId);
        Assertions.assertEquals(GlObjectState.DELETED, VirtualGlFacade.shaderState(shaderId));
        Assertions.assertEquals(0, VirtualGlFacade.getShaderi(shaderId, 35713));
        Assertions.assertFalse(VirtualGlFacade.getShaderInfoLog(shaderId).isBlank());

        VulkairisProgram program = linkedPair();
        int progId = program.virtualProgramId();
        VulkanShaderRegistry.deleteProgram(progId);
        Assertions.assertEquals(GlObjectState.DELETED, VirtualGlFacade.programState(progId));
        Assertions.assertEquals(0, VirtualGlFacade.getProgrami(progId, 35714));
        Assertions.assertEquals(-1, VirtualGlFacade.getUniformLocation(progId, "projection"));
        Assertions.assertEquals("", VirtualGlFacade.getActiveUniformAt(progId, 0));
    }

    @Test
    public void testDanglingProgramIsInvalidated() {
        VulkairisShader vert = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_dangle_vert", VERT, ShaderType.VERTEX);
        VulkairisShader frag = com.vulkairis.translation.SpirvCompiler.compileShader(
                "status_dangle_frag", FRAG, ShaderType.FRAGMENT);
        int progId = VulkanShaderRegistry.registerProgram(
                "status_dangle", List.of(vert.virtualId(), frag.virtualId()));
        VulkanShaderRegistry.setProgramLinkState(progId, GlObjectState.LINKED);
        Assertions.assertEquals(GlObjectState.LINKED, VirtualGlFacade.programState(progId));

        VulkanShaderRegistry.deleteShader(frag.virtualId());
        Assertions.assertEquals(GlObjectState.INVALIDATED, VirtualGlFacade.programState(progId),
                "a program referencing a deleted shader must not report linked");
        Assertions.assertEquals(0, VirtualGlFacade.getProgrami(progId, 35714));
    }

    @Test
    public void testUnknownIdsKeepLegacyAnswers() {
        // Never-registered ids are foreign objects (VulkanMod/vanilla managed): the facade must
        // not break their paths with failure claims.
        Assertions.assertEquals(1, VirtualGlFacade.getProgrami(7, 35714));
        Assertions.assertEquals(1, VirtualGlFacade.getShaderi(7, 35713));
    }
}
