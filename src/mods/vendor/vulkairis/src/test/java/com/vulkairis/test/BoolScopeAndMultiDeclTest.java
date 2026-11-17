package com.vulkairis.test;

import com.vulkairis.translation.ShaderTranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Regression tests for two Vulkairis/VulkanMod translation defects.
 *
 * <p><b>1. Scoped {@code bool} conversion.</b> {@link ShaderTranslator#normalizeTypesForVulkanMod}
 * used to rewrite <em>every</em> {@code bool} token (including function return types) to {@code int}.
 * A function such as {@code bool CheckForColor(...) { return a == b; }} then became an {@code int}
 * function returning a {@code bool} expression, which Shaderc rejects with
 * {@code 'return' : type does not match}. The conversion must apply to the injected
 * {@code VulkairisUniformBlock} body only.</p>
 *
 * <p><b>2. Multi-declarator interface statements.</b> {@code injectLayoutLocations} and
 * {@code collectInterfaceNames} only handled a single declared name, so a varying such as
 * {@code flat out vec3 upVec, sunVec, eastVec;} never received a {@code layout(location)} and
 * Shaderc rejected it with {@code 'location' : SPIR-V requires location for user input/output}.
 * Every declarator must be collected and located.</p>
 *
 * <p>These tests are device-free and exercise the pure {@link ShaderTranslator} helpers.</p>
 */
public class BoolScopeAndMultiDeclTest {

    private static final String BLOCK_WITH_BOOL = "#version 450 core\n"
            + "layout(std140, binding = 0) uniform VulkairisUniformBlock {\n"
            + "    bool heavyFog;\n"
            + "    float x;\n"
            + "};\n"
            + "bool IsDay() { return true; }\n"
            + "void main() {}\n";

    private static final String MULTI_DECL = "#version 450 core\n"
            + "flat out vec3 upVec, sunVec, eastVec;\n"
            + "void main() {}\n";

    // ------------------------------------------------------------------
    // Bug 1: normalizeTypesForVulkanMod is scoped to the injected block
    // ------------------------------------------------------------------

    @Test
    public void testBoolConversionIsScopedToInjectedBlock() {
        String normalized = ShaderTranslator.normalizeTypesForVulkanMod(BLOCK_WITH_BOOL);

        Assertions.assertTrue(normalized.contains("int heavyFog;"),
                "The block's bool member must become int: " + normalized);
        Assertions.assertFalse(normalized.contains("bool heavyFog;"),
                "No bool block member may remain: " + normalized);
        Assertions.assertTrue(normalized.contains("bool IsDay()"),
                "A function return type outside the block must stay bool: " + normalized);
        Assertions.assertFalse(normalized.contains("int IsDay()"),
                "A function return type must not be converted to int: " + normalized);
        Assertions.assertTrue(normalized.contains("return true;"),
                "A bool literal outside the block must stay true: " + normalized);
        Assertions.assertFalse(normalized.contains("return 1;"),
                "A bool literal outside the block must not become 1: " + normalized);
    }

    @Test
    public void testBoolConversionLeavesSourcesWithoutInjectedBlockUntouched() {
        String source = "#version 450 core\n"
                + "bool IsDay() { return true; }\n"
                + "bool flag = false;\n"
                + "void main() {}\n";

        Assertions.assertEquals(source, ShaderTranslator.normalizeTypesForVulkanMod(source),
                "Without the injected block no bool token may be rewritten");
    }

    @Test
    public void testBoolLiteralInsideBlockIsConvertedDefensively() {
        String source = "#version 450 core\n"
                + "layout(std140, binding = 0) uniform VulkairisUniformBlock {\n"
                + "    bool on = true;\n"
                + "    bool off = false;\n"
                + "};\n";

        String normalized = ShaderTranslator.normalizeTypesForVulkanMod(source);

        Assertions.assertTrue(normalized.contains("int on = 1;"), normalized);
        Assertions.assertTrue(normalized.contains("int off = 0;"), normalized);
    }

    @Test
    public void testNormalizeTypesIsIdempotent() {
        String once = ShaderTranslator.normalizeTypesForVulkanMod(BLOCK_WITH_BOOL);
        String twice = ShaderTranslator.normalizeTypesForVulkanMod(once);

        Assertions.assertEquals(once, twice, "Normalising twice must be a no-op");
    }

    @Test
    public void testNormalizeTypesHandlesNullAndEmpty() {
        Assertions.assertNull(ShaderTranslator.normalizeTypesForVulkanMod(null));
        Assertions.assertEquals("", ShaderTranslator.normalizeTypesForVulkanMod(""));
    }

    // ------------------------------------------------------------------
    // Bug 2: multi-declarator interface statements
    // ------------------------------------------------------------------

    @Test
    public void testMultiDeclaratorStatementIsSplitIntoSeparateLocatedDeclarations() {
        String injected = ShaderTranslator.injectLayoutLocations(
                MULTI_DECL, true, Map.of(), Map.of("upVec", 2, "sunVec", 3, "eastVec", 4));

        Assertions.assertTrue(injected.contains("layout(location = 2) flat out vec3 upVec;"), injected);
        Assertions.assertTrue(injected.contains("layout(location = 3) flat out vec3 sunVec;"), injected);
        Assertions.assertTrue(injected.contains("layout(location = 4) flat out vec3 eastVec;"), injected);
        Assertions.assertFalse(injected.contains("flat out vec3 upVec, sunVec, eastVec;"),
                "The multi-declarator statement must be fully split: " + injected);
    }

    @Test
    public void testCollectInterfaceNamesReturnsEveryDeclarator() {
        Assertions.assertEquals(List.of("upVec", "sunVec", "eastVec"),
                ShaderTranslator.collectInterfaceNames(MULTI_DECL, "out"),
                "Every declarator of a multi-declarator out statement must be collected");

        String inputs = "#version 450 core\n"
                + "flat in vec3 upVec, sunVec, eastVec;\n"
                + "void main() {}\n";
        Assertions.assertEquals(List.of("upVec", "sunVec", "eastVec"),
                ShaderTranslator.collectVertexInputNames(inputs),
                "collectVertexInputNames must collect every declarator too");
    }

    @Test
    public void testMultiDeclaratorArraysKeepTheirOwnSpecifier() {
        String source = "#version 450 core\n"
                + "out vec4 a[2], b[2];\n"
                + "void main() {}\n";

        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of(), Map.of("a", 5, "b", 6));

        Assertions.assertTrue(injected.contains("layout(location = 5) out vec4 a[2];"), injected);
        Assertions.assertTrue(injected.contains("layout(location = 6) out vec4 b[2];"), injected);
    }

    @Test
    public void testMultiDeclaratorInjectionIsIdempotent() {
        Map<String, Integer> varyings = Map.of("upVec", 2, "sunVec", 3, "eastVec", 4);

        String once = ShaderTranslator.injectLayoutLocations(MULTI_DECL, true, Map.of(), varyings);
        String twice = ShaderTranslator.injectLayoutLocations(once, true, Map.of(), varyings);

        Assertions.assertEquals(once, twice, "Applying the split pass twice must be a no-op");
    }

    @Test
    public void testMultiDeclaratorWithMissingLocationIsLeftUntouched() {
        // eastVec is absent from the map: the whole statement must be left alone rather than
        // inventing a location or emitting a partially-split statement.
        String injected = ShaderTranslator.injectLayoutLocations(
                MULTI_DECL, true, Map.of(), Map.of("upVec", 2, "sunVec", 3));

        Assertions.assertEquals(MULTI_DECL, injected,
                "A multi-declarator statement with an uncovered name must stay untouched");
    }

    @Test
    public void testMultiDeclaratorPreservesIndentationAndTrailingComment() {
        String source = "#version 450 core\n"
                + "    flat out vec3 upVec, sunVec; // varyings\n"
                + "void main() {}\n";

        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of(), Map.of("upVec", 2, "sunVec", 3));

        Assertions.assertTrue(injected.contains("    layout(location = 2) flat out vec3 upVec;"),
                "Indentation must be preserved on every split declaration: " + injected);
        Assertions.assertTrue(injected.contains("    layout(location = 3) flat out vec3 sunVec; // varyings"),
                "The trailing comment must be kept on the last split declaration: " + injected);
    }

    @Test
    public void testSingleDeclaratorBehaviourIsUnchanged() {
        String source = "#version 450 core\n"
                + "out vec2 texCoord;\n"
                + "void main() {}\n";

        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of(), Map.of("texCoord", 1));

        Assertions.assertTrue(injected.contains("layout(location = 1) out vec2 texCoord;"), injected);
    }

    @Test
    public void testInjectLayoutLocationsHandlesNull() {
        Assertions.assertNull(ShaderTranslator.injectLayoutLocations(null, true, Map.of(), Map.of()));
    }
}
