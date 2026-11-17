package com.vulkairis.testpack;

import org.lwjgl.util.shaderc.Shaderc;

import java.util.List;

/**
 * One shader stage of a synthetic test pack. Fixtures exist so every failure can be localized to
 * discovery, translation, SPIR-V validation, reflection, or (later) resource binding, rather than
 * hiding inside a whole real shader pack.
 *
 * @param name                stage identity, e.g. {@code "gbuffers_terrain.frag"}
 * @param shadercKind         the Shaderc stage constant
 * @param vertex              whether this is a vertex stage (drives preprocessing)
 * @param glsl                the raw Iris-style GLSL source
 * @param expectedSamplers    sampler names that must appear in reflection
 * @param expectedBlockMembers uniform block members that must appear in reflection
 */
public record ShaderStageFixture(
        String name,
        int shadercKind,
        boolean vertex,
        String glsl,
        List<String> expectedSamplers,
        List<String> expectedBlockMembers) {

    public ShaderStageFixture {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("stage name must be non-blank");
        }
        if (glsl == null || glsl.isBlank()) {
            throw new IllegalArgumentException("stage '" + name + "' has blank GLSL");
        }
        expectedSamplers = expectedSamplers == null ? List.of() : List.copyOf(expectedSamplers);
        expectedBlockMembers = expectedBlockMembers == null ? List.of() : List.copyOf(expectedBlockMembers);
    }

    /** @return a vertex stage fixture */
    public static ShaderStageFixture vertex(String name, String glsl, List<String> expectedBlockMembers) {
        return new ShaderStageFixture(
                name, Shaderc.shaderc_glsl_vertex_shader, true, glsl, List.of(), expectedBlockMembers);
    }

    /** @return a fragment stage fixture */
    public static ShaderStageFixture fragment(
            String name, String glsl, List<String> expectedSamplers, List<String> expectedBlockMembers) {
        return new ShaderStageFixture(
                name, Shaderc.shaderc_glsl_fragment_shader, false, glsl,
                expectedSamplers, expectedBlockMembers);
    }
}
