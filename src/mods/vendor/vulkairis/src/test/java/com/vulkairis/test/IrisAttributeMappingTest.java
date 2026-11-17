package com.vulkairis.test;

import com.vulkairis.translation.ShaderTranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the Iris -&gt; VulkanMod attribute/type bridge.
 *
 * <p>Two live failures motivated these: VulkanMod's {@code GLSLParser} matches vertex attributes to
 * {@code VertexFormat} element names by name, so Iris' {@code iris_}-prefixed identifiers never
 * matched (logging "Element iris_Position not found in elements [...]"), and its uniform-block
 * converter throws {@code RuntimeException("not admitted type: bool")} on a {@code bool} member.
 * These tests are device-free and exercise the pure translation helpers.</p>
 */
public class IrisAttributeMappingTest {

    @Test
    public void testNormalizeIrisAttributeNamesMapsKnownNames() {
        Assertions.assertEquals("Position", ShaderTranslator.normalizeIrisAttributeNames("iris_Position"));
        Assertions.assertEquals("UV0", ShaderTranslator.normalizeIrisAttributeNames("iris_UV0"));
        Assertions.assertEquals("UV0", ShaderTranslator.normalizeIrisAttributeNames("iris_UV"));
        Assertions.assertEquals("UV1", ShaderTranslator.normalizeIrisAttributeNames("iris_UV1"));
        Assertions.assertEquals("UV2", ShaderTranslator.normalizeIrisAttributeNames("iris_UV2"));
        Assertions.assertEquals("Color", ShaderTranslator.normalizeIrisAttributeNames("iris_Color"));
        Assertions.assertEquals("Normal", ShaderTranslator.normalizeIrisAttributeNames("iris_Normal"));
        // Iris packs entity/lightmap data into the vanilla UV2 slot.
        Assertions.assertEquals("UV2", ShaderTranslator.normalizeIrisAttributeNames("iris_Entity"));
    }

    @Test
    public void testNormalizeIrisAttributeNamesMapsInsideDeclaration() {
        String source = "in vec3 iris_Position;\nin vec4 iris_Color;\nvoid main() {}\n";
        String normalized = ShaderTranslator.normalizeIrisAttributeNames(source);

        Assertions.assertTrue(normalized.contains("in vec3 Position;"), normalized);
        Assertions.assertTrue(normalized.contains("in vec4 Color;"), normalized);
        Assertions.assertFalse(normalized.contains("iris_Position"), normalized);
        Assertions.assertFalse(normalized.contains("iris_Color"), normalized);
    }

    @Test
    public void testUnknownIrisNameIsLeftUntouched() {
        Assertions.assertEquals("iris_Foo", ShaderTranslator.normalizeIrisAttributeNames("iris_Foo"));
        Assertions.assertEquals("in vec3 iris_Mystery;",
                ShaderTranslator.normalizeIrisAttributeNames("in vec3 iris_Mystery;"));
    }

    @Test
    public void testNormalizeDoesNotRewritePrefixedOrMemberIdentifiers() {
        // Not a standalone token: preceded by an identifier character.
        Assertions.assertEquals("myiris_Position",
                ShaderTranslator.normalizeIrisAttributeNames("myiris_Position"));
        // Not a standalone token: reached through a '.' member access.
        Assertions.assertEquals("object.iris_Position",
                ShaderTranslator.normalizeIrisAttributeNames("object.iris_Position"));
        // Partial suffix must not match either.
        Assertions.assertEquals("iris_Color2",
                ShaderTranslator.normalizeIrisAttributeNames("iris_Color2"));
    }

    @Test
    public void testNormalizeIsIdempotent() {
        String source = "in vec3 iris_Position;\nin vec4 iris_Color;\nin vec2 iris_UV0;\n";
        String once = ShaderTranslator.normalizeIrisAttributeNames(source);
        String twice = ShaderTranslator.normalizeIrisAttributeNames(once);
        Assertions.assertEquals(once, twice, "Normalisation must be idempotent");
    }

    @Test
    public void testPreprocessNormalizesIrisAttributeNames() {
        String vertex = """
                #version 150 core

                in vec3 iris_Position;
                in vec2 iris_UV0;
                uniform mat4 projection;

                out vec2 vUv;

                void main() {
                    vUv = iris_UV0;
                    gl_Position = projection * vec4(iris_Position, 1.0);
                }
                """;

        String preprocessed = ShaderTranslator.preprocessGlsl(vertex, true);

        Assertions.assertTrue(preprocessed.contains("Position"),
                "Preprocessed vertex shader must expose the vanilla attribute name: " + preprocessed);
        Assertions.assertFalse(preprocessed.contains("iris_Position"),
                "Iris attribute names must not survive preprocessing: " + preprocessed);
        Assertions.assertFalse(preprocessed.contains("iris_UV0"),
                "Iris attribute names must not survive preprocessing: " + preprocessed);
    }

    @Test
    public void testBoolUniformBecomesIntForVulkanMod() {
        String vertex = """
                #version 150 core

                in vec3 iris_Position;
                uniform bool heavyFog = false;
                uniform float cloudHeight = 192.0;

                out float fogAmount;

                void main() {
                    fogAmount = heavyFog ? cloudHeight : 0.0;
                    gl_Position = vec4(iris_Position, 1.0);
                }
                """;

        // The Shaderc/test path keeps the bool member (asserted by ShaderCompilerTest).
        String preprocessed = ShaderTranslator.preprocessGlsl(vertex, true);
        Assertions.assertTrue(preprocessed.contains("bool heavyFog;"),
                "preprocessGlsl must preserve bool members for the Shaderc path: " + preprocessed);

        // The VulkanMod-bound path converts the injected block member to int so its converter does
        // not throw. Conversion is block-scoped: bool used elsewhere (function signatures, the
        // commented-out original declaration) is deliberately preserved.
        String normalized = ShaderTranslator.normalizeTypesForVulkanMod(preprocessed);
        int blockStart = normalized.indexOf("VulkairisUniformBlock");
        Assertions.assertTrue(blockStart >= 0, "Injected block must be present: " + normalized);
        int braceStart = normalized.indexOf('{', blockStart);
        int braceEnd = normalized.indexOf('}', braceStart);
        String memberRegion = normalized.substring(braceStart, braceEnd);
        Assertions.assertTrue(memberRegion.contains("int heavyFog;"),
                "VulkanMod path must express the boolean member as int: " + memberRegion);
        Assertions.assertFalse(memberRegion.contains("bool"),
                "VulkanMod path must not leave a bool member in the injected block: " + memberRegion);
    }

    @Test
    public void testNormalizeTypesLeavesBoolSubstringsUntouched() {
        Assertions.assertEquals("booleanType", ShaderTranslator.normalizeTypesForVulkanMod("booleanType"));
        Assertions.assertEquals("mybool", ShaderTranslator.normalizeTypesForVulkanMod("mybool"));
        // Outside the injected block the conversion is intentionally a no-op.
        Assertions.assertEquals("bool flag;", ShaderTranslator.normalizeTypesForVulkanMod("bool flag;"));
    }
}
