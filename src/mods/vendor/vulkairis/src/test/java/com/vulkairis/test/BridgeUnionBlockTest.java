package com.vulkairis.test;

import com.vulkairis.bridge.VulkanModProgramBridge;
import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.UnifiedTranslationService;
import com.vulkairis.translation.VulkanShaderRegistry;
import com.vulkairis.translation.VulkairisProgram;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase B gap B-1: the driver path must link against one shared cross-stage uniform block.
 *
 * <p>Before the fix, {@code VulkanModProgramBridge.link()} ran per-stage
 * {@code preprocessGlsl} plus a private binding-map duplicate, so the vertex stage could carry
 * {@code projection} while the fragment stage carried {@code rainStrength} with both blocks
 * sharing dense binding 0 — the descriptor mismatch the union model exists to prevent. This
 * test resolves the device-free part of that path
 * ({@link VulkanModProgramBridge#resolveDriverSources}) and asserts both stages share one
 * identical {@code VulkairisUniformBlock}, plus the shared dense {@code 0..N-1} contract
 * (injected block first, then first-seen vertex-&gt;fragment).
 */
public class BridgeUnionBlockTest {

    private static final Pattern BINDING = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");

    @BeforeEach
    public void setUp() {
        UnifiedTranslationService.reset();
        ProgramTranslator.clearCacheForTests();
        VulkanShaderRegistry.cleanUp();
    }

    @Test
    public void testDriverPathSharesIdenticalUnionBlock() {
        String vertex = ProgramTranslatorTest.terrainVertex();
        String fragment = ProgramTranslatorTest.terrainFragment();

        int vertId = VulkanShaderRegistry.registerShader("gbuffers_terrain.vsh", 35633, null, vertex);
        int fragId = VulkanShaderRegistry.registerShader("gbuffers_terrain.fsh", 35632, null, fragment);
        UnifiedTranslationService.bufferRawSource(vertId, "gbuffers_terrain.vsh", vertex, "VERTEX");
        UnifiedTranslationService.bufferRawSource(fragId, "gbuffers_terrain.fsh", fragment, "FRAGMENT");

        int progId = VulkanShaderRegistry.registerProgram("gbuffers_terrain", List.of(vertId, fragId));
        VulkairisProgram program = VulkanShaderRegistry.getProgram(progId);
        Assertions.assertNotNull(program);

        VulkanModProgramBridge.DriverSources base =
                VulkanModProgramBridge.resolveDriverSources(program);

        Assertions.assertTrue(base.unified(),
                "The driver path must resolve through the program-together union translation");
        String vertGlsl = base.vertGlsl();
        String fragGlsl = base.fragGlsl();
        Assertions.assertNotNull(vertGlsl);
        Assertions.assertNotNull(fragGlsl);

        // The union block is identical on both stages: projection/sunPosition (vertex-only
        // uniforms) and rainStrength (fragment-only) are all present in both.
        List<String> vertMembers = uniformBlockMembers(vertGlsl);
        List<String> fragMembers = uniformBlockMembers(fragGlsl);
        Assertions.assertFalse(vertMembers.isEmpty(), "Vertex stage must carry the union block");
        Assertions.assertEquals(vertMembers, fragMembers,
                "Both driver stages must share one identical union block:\nvertex=" + vertMembers
                        + "\nfragment=" + fragMembers);
        for (String member : List.of("mat4 projection;", "vec3 sunPosition;",
                "float rainStrength;")) {
            Assertions.assertTrue(vertGlsl.contains(member), "Vertex misses union member " + member);
            Assertions.assertTrue(fragGlsl.contains(member), "Fragment misses union member " + member);
        }

        // Union output carries no bindings (reflection assigns later, except fragment outputs);
        // the per-stage binding-100 input form must be gone from the driver path.
        Assertions.assertFalse(vertGlsl.contains("binding = 100"), vertGlsl);
        Assertions.assertFalse(fragGlsl.contains("binding = 100"), fragGlsl);

        // The shared dense contract applied to the resolved sources: block at 0, dense 0..N-1,
        // identical on both stages after remap.
        Map<String, Integer> bindings =
                ShaderTranslator.buildUnifiedBindingMap(vertGlsl, fragGlsl);
        Assertions.assertEquals(Integer.valueOf(0), bindings.get(ShaderTranslator.UNIFORM_BLOCK_NAME),
                "The injected block must stay pinned at binding 0: " + bindings);
        for (int i = 0; i < bindings.size(); i++) {
            Assertions.assertTrue(bindings.containsValue(i),
                    "Bindings must be dense 0.." + (bindings.size() - 1) + ": " + bindings);
        }

        String vertRemapped = ShaderTranslator.ensureDescriptorBindings(
                ShaderTranslator.remapDescriptorBindings(vertGlsl, bindings), bindings);
        String fragRemapped = ShaderTranslator.ensureDescriptorBindings(
                ShaderTranslator.remapDescriptorBindings(fragGlsl, bindings), bindings);
        Assertions.assertTrue(vertRemapped.contains("binding = 0) uniform VulkairisUniformBlock"),
                "Vertex block must use the shared binding 0: " + vertRemapped);
        Assertions.assertTrue(fragRemapped.contains("binding = 0) uniform VulkairisUniformBlock"),
                "Fragment block must use the same shared binding 0: " + fragRemapped);
        Assertions.assertEquals(bindingNumbers(vertRemapped + "\n" + fragRemapped).stream()
                .collect(TreeSet<Integer>::new, TreeSet::add, TreeSet::addAll).size(),
                bindings.size(), "Remapped stages must cover exactly the dense range");
    }

    @Test
    public void testSharedBindingHelperContract() {
        String vert = "#version 450 core\n"
                + "layout(std140) uniform VulkairisUniformBlock {\n    mat4 projection;\n};\n"
                + "layout(binding = 3) uniform sampler2D texA;\n";
        String frag = "#version 450 core\n"
                + "layout(std140) uniform VulkairisUniformBlock {\n    mat4 projection;\n};\n"
                + "layout(binding = 7) uniform sampler2D texB;\n";

        Map<String, Integer> bindings = ShaderTranslator.buildUnifiedBindingMap(vert, frag);

        Assertions.assertEquals(
                Map.of(ShaderTranslator.UNIFORM_BLOCK_NAME, 0, "texA", 1, "texB", 2), bindings,
                "Contract: injected block first, then first-seen vertex->fragment, dense 0..N-1");

        // Block declared only on the fragment side is still pinned at 0.
        String bareVert = "#version 450 core\nlayout(binding = 3) uniform sampler2D texA;\n";
        Map<String, Integer> oneSided = ShaderTranslator.buildUnifiedBindingMap(bareVert, frag);
        Assertions.assertEquals(Integer.valueOf(0), oneSided.get(ShaderTranslator.UNIFORM_BLOCK_NAME));
        Assertions.assertEquals(3, oneSided.size());
    }

    /** Sorted union-block member lines ({@code type name;} minus surrounding whitespace). */
    private static List<String> uniformBlockMembers(String glsl) {
        int nameIndex = glsl.indexOf(ShaderTranslator.UNIFORM_BLOCK_NAME);
        Assertions.assertTrue(nameIndex >= 0, "Source must declare the union block");
        int open = glsl.indexOf('{', nameIndex);
        int close = glsl.indexOf("};", open);
        Assertions.assertTrue(open >= 0 && close > open, "Union block must be well-formed");
        List<String> members = new ArrayList<>();
        for (String line : glsl.substring(open + 1, close).split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                members.add(trimmed);
            }
        }
        members.sort(String::compareTo);
        return members;
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
}
