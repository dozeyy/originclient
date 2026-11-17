package com.vulkairis.test;

import com.vulkairis.translation.ShaderTranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Regression tests for {@link ShaderTranslator#injectLayoutLocations}.
 *
 * <p>VulkanMod compiles the raw GLSL string handed to {@code Pipeline.Builder.setShaderSrc}
 * directly with Shaderc, which rejects any user {@code in}/{@code out} without an explicit
 * location ({@code error: 'location' : SPIR-V requires location for user input/output}). Iris
 * relies on {@code glBindAttribLocation} and therefore ships bare declarations, so the
 * VulkanMod-bound path must inject locations. These tests are device-free and exercise the pure
 * translation helper plus its interaction with {@link ShaderTranslator#preprocessGlsl}.</p>
 */
public class LayoutLocationInjectionTest {

    @Test
    public void testVertexInputGetsLocationFromMap() {
        String source = "#version 450 core\n"
                + "in vec3 Position;\n"
                + "void main() { gl_Position = vec4(Position, 1.0); }\n";

        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of("Position", 0), Map.of());

        Assertions.assertTrue(injected.contains("layout(location = 0) in vec3 Position;"),
                "A bare vertex input must receive its mapped location: " + injected);
    }

    @Test
    public void testAlreadyQualifiedDeclarationIsNotModified() {
        String source = "#version 450 core\n"
                + "layout(location = 3) in vec2 uv;\n"
                + "void main() { gl_Position = vec4(uv, 0.0, 1.0); }\n";

        // Both maps contain the name, but the declaration already has layout(location = 3), so it
        // must survive byte-for-byte (no double injection, no overwrite).
        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of("uv", 0), Map.of("uv", 9));

        Assertions.assertEquals(source, injected,
                "An already-qualified declaration must be left exactly as-is");
        Assertions.assertTrue(injected.contains("layout(location = 3) in vec2 uv;"), injected);
        Assertions.assertFalse(injected.contains("layout(location = 0) in vec2 uv;"), injected);
        Assertions.assertFalse(injected.contains("layout(location = 9) in vec2 uv;"), injected);
    }

    @Test
    public void testSharedVaryingLocationsAgreeAcrossStages() {
        Map<String, Integer> varyings = Map.of("texCoord", 2);

        String vert = "#version 450 core\n"
                + "out vec2 texCoord;\n"
                + "void main() { texCoord = vec2(0.0); }\n";
        String frag = "#version 450 core\n"
                + "in vec2 texCoord;\n"
                + "void main() { }\n";

        String vertInjected = ShaderTranslator.injectLayoutLocations(vert, true, Map.of(), varyings);
        String fragInjected = ShaderTranslator.injectLayoutLocations(frag, false, Map.of(), varyings);

        Assertions.assertTrue(vertInjected.contains("layout(location = 2) out vec2 texCoord;"),
                "Vertex output must use the shared varying location: " + vertInjected);
        Assertions.assertTrue(fragInjected.contains("layout(location = 2) in vec2 texCoord;"),
                "Fragment input must use the same shared varying location: " + fragInjected);
    }

    @Test
    public void testFragmentOutputsGetSequentialLocations() {
        String source = "#version 450 core\n"
                + "out vec4 colorA;\n"
                + "out vec4 colorB;\n"
                + "out vec4 colorC;\n"
                + "void main() { colorA = colorB = colorC = vec4(1.0); }\n";

        String injected = ShaderTranslator.injectLayoutLocations(source, false, Map.of(), Map.of());

        Assertions.assertTrue(injected.contains("layout(location = 0) out vec4 colorA;"), injected);
        Assertions.assertTrue(injected.contains("layout(location = 1) out vec4 colorB;"), injected);
        Assertions.assertTrue(injected.contains("layout(location = 2) out vec4 colorC;"), injected);
    }

    @Test
    public void testQualifierFormsAreHandled() {
        String source = "#version 450 core\n"
                + "flat out vec3 n;\n"
                + "centroid in vec2 uv;\n"
                + "highp out vec3 viewDir;\n"
                + "void main() {}\n";

        String injected = ShaderTranslator.injectLayoutLocations(source, true,
                Map.of("uv", 1), Map.of("n", 4, "viewDir", 5));

        Assertions.assertTrue(injected.contains("layout(location = 4) flat out vec3 n;"),
                "Interpolation qualifier before the direction must be preserved: " + injected);
        Assertions.assertTrue(injected.contains("layout(location = 1) centroid in vec2 uv;"),
                "Centroid qualifier before the direction must be preserved: " + injected);
        Assertions.assertTrue(injected.contains("layout(location = 5) highp out vec3 viewDir;"),
                "Precision qualifier before the direction must be preserved: " + injected);
    }

    @Test
    public void testArrayedDeclarationUsesNameBeforeBracket() {
        String source = "#version 450 core\n"
                + "out vec4 c[2];\n"
                + "void main() {}\n";

        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of(), Map.of("c", 5));

        Assertions.assertTrue(injected.contains("layout(location = 5) out vec4 c[2];"),
                "An arrayed varying is keyed by the identifier before '[': " + injected);
    }

    @Test
    public void testMissingVertexInputIsLeftUntouched() {
        String source = "#version 450 core\n"
                + "in vec3 Mystery;\n"
                + "void main() { gl_Position = vec4(Mystery, 1.0); }\n";

        // "Mystery" is absent from the map: the pass must not invent a location.
        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of("Position", 0), Map.of());

        Assertions.assertEquals(source, injected,
                "A vertex input with no known location must be left untouched");
    }

    @Test
    public void testInjectionIsIdempotent() {
        String src = "#version 450 core\n"
                + "in vec3 Position;\n"
                + "out vec2 texCoord;\n"
                + "void main() { texCoord = vec2(0.0); }\n";
        Map<String, Integer> vertexInputs = Map.of("Position", 0);
        Map<String, Integer> varyings = Map.of("texCoord", 1);

        String once = ShaderTranslator.injectLayoutLocations(src, true, vertexInputs, varyings);
        String twice = ShaderTranslator.injectLayoutLocations(once, true, vertexInputs, varyings);

        Assertions.assertEquals(once, twice, "Applying the transform twice must be a no-op");

        String frag = "#version 450 core\nout vec4 color;\nvoid main() {}\n";
        String fragOnce = ShaderTranslator.injectLayoutLocations(frag, false, Map.of(), Map.of());
        String fragTwice = ShaderTranslator.injectLayoutLocations(fragOnce, false, Map.of(), Map.of());
        Assertions.assertEquals(fragOnce, fragTwice,
                "Fragment-output numbering must also be idempotent");
        Assertions.assertEquals(1, countOccurrences(fragOnce, "layout(location = 0)"),
                "Fragment output must be numbered exactly once: " + fragOnce);
    }

    @Test
    public void testUniformAndFunctionDeclarationsAreNotTouched() {
        String source = "#version 450 core\n"
                + "uniform mat4 projection;\n"
                + "layout(std140, binding = 0) uniform VulkairisUniformBlock {\n"
                + "    vec4 fogColor;\n"
                + "};\n"
                + "vec3 helper(in vec3 v) { return v; }\n"
                + "void main() { gl_Position = projection * vec4(helper(vec3(0.0)), 1.0); }\n";

        String injected = ShaderTranslator.injectLayoutLocations(
                source, true, Map.of("Position", 0), Map.of());

        Assertions.assertEquals(source, injected,
                "Uniform blocks and function declarations must be left untouched");
    }

    @Test
    public void testPreprocessOutputUnaffectedByInjection() {
        String vertex = """
                #version 150 core

                in vec3 iris_Position;
                uniform mat4 projection;

                out vec2 texCoord;

                void main() {
                    texCoord = vec2(0.0);
                    gl_Position = projection * vec4(iris_Position, 1.0);
                }
                """;

        String preprocessed = ShaderTranslator.preprocessGlsl(vertex, true);
        Assertions.assertEquals(1, countOccurrences(preprocessed, "uniform VulkairisUniformBlock"),
                "Preprocessing must inject exactly one uniform block: " + preprocessed);

        String injected = ShaderTranslator.injectLayoutLocations(
                preprocessed, true, Map.of("Position", 0), Map.of("texCoord", 0));

        Assertions.assertEquals(1, countOccurrences(injected, "uniform VulkairisUniformBlock"),
                "Location injection must not disturb the injected uniform block: " + injected);
        Assertions.assertTrue(injected.contains("layout(location = 0) in vec3 Position;"),
                "Normalized Iris attribute must get its location: " + injected);
        Assertions.assertTrue(injected.contains("layout(location = 0) out vec2 texCoord;"),
                "Varying must get its location: " + injected);
    }

    @Test
    public void testComputeLocationSlotsAndArrayVaryings() {
        Assertions.assertEquals(1, ShaderTranslator.computeLocationSlots("vec4", "color"));
        Assertions.assertEquals(3, ShaderTranslator.computeLocationSlots("vec4", "irs_texCoords[3]"));
        Assertions.assertEquals(4, ShaderTranslator.computeLocationSlots("mat4", "shadowMatrix"));
        Assertions.assertEquals(8, ShaderTranslator.computeLocationSlots("mat4", "matrices[2]"));

        String source = """
                #version 450 core
                out vec2 vUv;
                out vec4 irs_texCoords[3];
                out vec4 irs_Color;
                """;

        Map<String, Integer> slots = ShaderTranslator.collectInterfaceSlots(source, "out");
        Assertions.assertEquals(1, slots.get("vUv"));
        Assertions.assertEquals(3, slots.get("irs_texCoords"));
        Assertions.assertEquals(1, slots.get("irs_Color"));
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
