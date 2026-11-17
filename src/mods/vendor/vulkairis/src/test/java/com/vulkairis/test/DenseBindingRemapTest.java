package com.vulkairis.test;

import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.ShaderTranslator.BindingRemap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regression tests for the dense descriptor-binding remap applied on the VulkanMod path.
 *
 * <p>VulkanMod's {@code Pipeline.createDescriptorSetLayout} allocates a binding array of size
 * {@code buffers + imageDescriptors} but indexes it by the <em>raw</em> {@code binding = N} number
 * from the SPIR-V/source. Vulkairis injected its uniform block at binding 100 and kept samplers at
 * their Iris bindings, so the lookup ran past the end of the array and threw
 * {@code IndexOutOfBoundsException}. The remap compacts every bound descriptor into a dense
 * {@code 0..N-1} range and must agree across both stages.</p>
 *
 * <p>These tests are device-free: they exercise the pure {@link ShaderTranslator} helpers only.</p>
 */
public class DenseBindingRemapTest {

    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");

    private static final String INJECTED_BLOCK = """
            layout(std140, binding = 100) uniform VulkairisUniformBlock {
                mat4 projection;
                vec4 fogColor;
            };
            """;

    @Test
    public void testInjectedBlockAndSamplersRemapToDenseZeroBased() {
        String source = "#version 450 core\n"
                + INJECTED_BLOCK
                + "layout (binding = 3) uniform sampler2D texA;\n"
                + "layout (binding = 7) uniform sampler2D texB;\n"
                + "layout (location = 0) in vec2 vUv;\n"
                + "void main() { vec4 c = texture(texA, vUv) + texture(texB, vUv); }\n";

        BindingRemap remap = ShaderTranslator.remapDescriptorBindings(source);

        Assertions.assertEquals(List.of("VulkairisUniformBlock", "texA", "texB"), remap.orderedNames(),
                "Order must be the injected block first, then samplers in declaration order");
        Assertions.assertEquals(Map.of("VulkairisUniformBlock", 0, "texA", 1, "texB", 2), remap.bindings(),
                "Bindings must be dense 0,1,2");

        String rewritten = remap.source();
        Assertions.assertFalse(rewritten.contains("binding = 100"),
                "The injected block must no longer sit at binding 100: " + rewritten);
        Assertions.assertFalse(rewritten.contains("binding = 3"),
                "Sampler binding 3 must have been remapped: " + rewritten);
        Assertions.assertFalse(rewritten.contains("binding = 7"),
                "Sampler binding 7 must have been remapped: " + rewritten);
        Assertions.assertTrue(rewritten.contains("layout(std140, binding = 0) uniform VulkairisUniformBlock"),
                "The std140 block qualifier must be preserved with the new binding: " + rewritten);
        Assertions.assertTrue(rewritten.contains("layout (binding = 1) uniform sampler2D texA;"));
        Assertions.assertTrue(rewritten.contains("layout (binding = 2) uniform sampler2D texB;"));

        Assertions.assertEquals(List.of(0, 1, 2), bindingNumbers(rewritten),
                "Every rewritten binding must be dense 0..2");
    }

    @Test
    public void testOrderedMappingIsDeterministic() {
        String first = "#version 450 core\n" + INJECTED_BLOCK
                + "layout(binding = 5) uniform sampler2D texA;\n"
                + "layout(binding = 9) uniform sampler2D texB;\n";
        String second = "#version 450 core\n" + INJECTED_BLOCK
                + "layout(binding = 1) uniform sampler2D texA;\n"
                + "layout(binding = 2) uniform sampler2D texB;\n";

        BindingRemap a = ShaderTranslator.remapDescriptorBindings(first);
        BindingRemap b = ShaderTranslator.remapDescriptorBindings(second);

        // Different raw bindings, same declaration order -> identical dense assignment.
        Assertions.assertEquals(a.orderedNames(), b.orderedNames(),
                "The ordered name list must come from declaration order, not raw bindings");
        Assertions.assertEquals(a.bindings(), b.bindings(),
                "Identical descriptor sets must remap to the same dense bindings");
        Assertions.assertEquals(3, ShaderTranslator.countDescriptorBindings(first));
        for (int i = 0; i < a.orderedNames().size(); i++) {
            Assertions.assertEquals(i, a.bindings().get(a.orderedNames().get(i)),
                    "Each descriptor must map to its position in the ordering (0-based, dense)");
        }
    }

    @Test
    public void testSharedExplicitMapKeepsCrossStageBindingsConsistent() {
        // A vertex stage that only carries the injected block...
        String vert = "#version 450 core\n" + INJECTED_BLOCK
                + "layout(location = 0) in vec3 Position;\n";
        // ...and a fragment stage with the same block plus two samplers.
        String frag = "#version 450 core\n" + INJECTED_BLOCK
                + "layout(binding = 4) uniform sampler2D albedoTex;\n"
                + "layout(binding = 6) uniform sampler2D lightTex;\n";

        // Build one unified assignment across both stages (vertex first, then fragment), exactly as
        // the bridge does, and apply it to both.
        Map<String, Integer> unified = new LinkedHashMap<>();
        for (String source : List.of(vert, frag)) {
            for (String name : ShaderTranslator.collectDescriptorNames(source)) {
                unified.putIfAbsent(name, unified.size());
            }
        }
        Assertions.assertEquals(Map.of("VulkairisUniformBlock", 0, "albedoTex", 1, "lightTex", 2), unified);

        String vertOut = ShaderTranslator.remapDescriptorBindings(vert, unified);
        String fragOut = ShaderTranslator.remapDescriptorBindings(frag, unified);

        Assertions.assertTrue(vertOut.contains("binding = 0) uniform VulkairisUniformBlock"),
                "Vertex block must use the shared binding 0: " + vertOut);
        Assertions.assertTrue(fragOut.contains("binding = 0) uniform VulkairisUniformBlock"),
                "Fragment block must use the same shared binding 0: " + fragOut);
        Assertions.assertTrue(fragOut.contains("binding = 1) uniform sampler2D albedoTex"));
        Assertions.assertTrue(fragOut.contains("binding = 2) uniform sampler2D lightTex"));

        Assertions.assertEquals(List.of(0), bindingNumbers(vertOut),
                "Vertex stage declares only the shared block");
        Assertions.assertEquals(List.of(0, 1, 2), bindingNumbers(fragOut),
                "Fragment stage declares block plus both samplers");
    }

    @Test
    public void testSameSamplerNamesGetSameSharedBindingsAcrossStages() {
        String stageA = "#version 450 core\n"
                + "layout(binding = 8) uniform sampler2D texA;\n"
                + "layout(binding = 9) uniform sampler2D texB;\n";
        String stageB = "#version 450 core\n"
                + "layout(binding = 2) uniform sampler2D texA;\n"
                + "layout(binding = 5) uniform sampler2D texB;\n";

        // A single explicit map shared by both stages.
        Map<String, Integer> shared = new LinkedHashMap<>();
        for (String source : List.of(stageA, stageB)) {
            for (String name : ShaderTranslator.collectDescriptorNames(source)) {
                shared.putIfAbsent(name, shared.size());
            }
        }
        Assertions.assertEquals(Map.of("texA", 0, "texB", 1), shared);

        String outA = ShaderTranslator.remapDescriptorBindings(stageA, shared);
        String outB = ShaderTranslator.remapDescriptorBindings(stageB, shared);

        Assertions.assertEquals(List.of(0, 1), bindingNumbers(outA));
        Assertions.assertEquals(List.of(0, 1), bindingNumbers(outB));
        Assertions.assertTrue(outA.contains("layout(binding = 0) uniform sampler2D texA;"));
        Assertions.assertTrue(outA.contains("layout(binding = 1) uniform sampler2D texB;"));
        Assertions.assertTrue(outB.contains("layout(binding = 0) uniform sampler2D texA;"));
        Assertions.assertTrue(outB.contains("layout(binding = 1) uniform sampler2D texB;"));
    }

    @Test
    public void testRemapDoesNotDisturbLocationQualifiers() {
        String source = "#version 450 core\n"
                + "layout (location = 0) in vec3 Position;\n"
                + "layout (location = 1) in vec4 Color;\n"
                + "layout (location = 0) out vec2 vUv;\n"
                + "layout (location = 0) out vec4 fragColor;\n"
                + INJECTED_BLOCK
                + "layout(binding = 2) uniform sampler2D tex;\n";

        String rewritten = ShaderTranslator.remapDescriptorBindings(source).source();

        Assertions.assertTrue(rewritten.contains("layout (location = 0) in vec3 Position;"));
        Assertions.assertTrue(rewritten.contains("layout (location = 1) in vec4 Color;"));
        Assertions.assertTrue(rewritten.contains("layout (location = 0) out vec2 vUv;"));
        Assertions.assertTrue(rewritten.contains("layout (location = 0) out vec4 fragColor;"));
        Assertions.assertEquals(3, countOccurrences(rewritten, "location = 0"),
                "None of the existing location = 0 qualifiers may be dropped or renamed");
    }

    @Test
    public void testArrayedSamplerKeepsBaseNameAndGetsOneBinding() {
        String source = "#version 450 core\n" + INJECTED_BLOCK
                + "layout(binding = 3) uniform sampler2D tex[4];\n";

        BindingRemap remap = ShaderTranslator.remapDescriptorBindings(source);

        Assertions.assertEquals(List.of("VulkairisUniformBlock", "tex"), remap.orderedNames(),
                "An arrayed sampler is one descriptor named by its base identifier");
        Assertions.assertTrue(remap.source().contains("uniform sampler2D tex[4];"),
                "The array declarator must be preserved: " + remap.source());
        Assertions.assertTrue(remap.source().contains("layout(binding = 1) uniform sampler2D tex[4];"));
    }

    @Test
    public void testNullAndBlankInputsAreHandled() {
        BindingRemap nullRemap = ShaderTranslator.remapDescriptorBindings((String) null);
        Assertions.assertNotNull(nullRemap, "The convenience remap always returns a result object");
        Assertions.assertNull(nullRemap.source());
        Assertions.assertTrue(nullRemap.orderedNames().isEmpty());
        Assertions.assertTrue(nullRemap.bindings().isEmpty());

        BindingRemap blankRemap = ShaderTranslator.remapDescriptorBindings("");
        Assertions.assertEquals("", blankRemap.source());
        Assertions.assertTrue(blankRemap.orderedNames().isEmpty());

        Assertions.assertEquals("", ShaderTranslator.remapDescriptorBindings("").source());
        Assertions.assertNull(ShaderTranslator.remapDescriptorBindings((String) null, null));
        Assertions.assertEquals(0, ShaderTranslator.countDescriptorBindings(null));
    }

    /** All {@code binding = N} values in encounter order. */
    private static List<Integer> bindingNumbers(String source) {
        List<Integer> values = new ArrayList<>();
        Matcher matcher = BINDING.matcher(source);
        while (matcher.find()) {
            values.add(Integer.parseInt(matcher.group(1)));
        }
        return values;
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
