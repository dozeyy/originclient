package com.vulkairis.test;

import com.vulkairis.translation.ShaderTranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression tests for {@link ShaderTranslator#ensureDescriptorBindings}.
 *
 * <p>VulkanMod's {@code Pipeline.Builder} compiles the raw GLSL string handed to
 * {@code Pipeline.Builder.setShaderSrc} directly with Shaderc, without enabling
 * {@code shaderc_compile_options_set_auto_bind_uniforms}. SPIR-V therefore rejects every
 * sampler/texture/image uniform that declares no {@code layout(binding = X)}:
 * <pre>
 *   centerDepthSmooth.fsh:11: error: 'binding' : sampler/texture/image requires layout(binding=X)
 * </pre>
 * Iris ships those descriptors bare ({@code uniform sampler2D depth;}), relying on GL-era
 * auto-binding, so the VulkanMod-bound path must inject a dense binding into each opaque declaration
 * using the same {@code name -> binding} map the descriptor config is built from.</p>
 *
 * <p>These tests are device-free: they exercise the pure {@link ShaderTranslator} helper only.</p>
 */
public class SamplerBindingInjectionTest {

    @Test
    public void testBareSamplerGetsInjectedBinding() {
        String source = "#version 450 core\n"
                + "uniform sampler2D depth;\n"
                + "void main() {}\n";

        String out = ShaderTranslator.ensureDescriptorBindings(source, Map.of("depth", 1));

        Assertions.assertTrue(out.contains("layout(binding = 1) uniform sampler2D depth;"),
                "A bare sampler must receive its mapped binding: " + out);
        Assertions.assertEquals(1, countOccurrences(out, "sampler2D depth;"),
                "The declaration must appear exactly once, now bound: " + out);
    }

    @Test
    public void testExistingBindingIsRewrittenToMappedValue() {
        String source = "#version 450 core\n"
                + "layout(binding = 7) uniform sampler2D tex;\n";

        String out = ShaderTranslator.ensureDescriptorBindings(source, Map.of("tex", 2));

        Assertions.assertTrue(out.contains("layout(binding = 2) uniform sampler2D tex;"),
                "The existing binding must be rewritten to the mapped value: " + out);
        Assertions.assertFalse(out.contains("binding = 7"),
                "The stale binding must not survive: " + out);
    }

    @Test
    public void testLayoutWithoutBindingGainsOneAndKeepsOtherQualifiers() {
        String source = "#version 450 core\n"
                + "layout(set = 0) uniform sampler2D x;\n";

        String out = ShaderTranslator.ensureDescriptorBindings(source, Map.of("x", 0));

        Assertions.assertTrue(out.contains("layout(set = 0, binding = 0) uniform sampler2D x;"),
                "binding must be added inside the existing layout while set = 0 survives: " + out);
    }

    @Test
    public void testArrayedSamplerGetsOneBindingAndKeepsArrayDeclarator() {
        String source = "#version 450 core\n"
                + "uniform sampler2D tex[4];\n";

        String out = ShaderTranslator.ensureDescriptorBindings(source, Map.of("tex", 3));

        Assertions.assertTrue(out.contains("layout(binding = 3) uniform sampler2D tex[4];"),
                "The array base name is keyed and its declarator preserved: " + out);
        Assertions.assertEquals(1, countOccurrences(out, "binding ="),
                "An arrayed sampler is one descriptor and must get exactly one binding: " + out);
    }

    @Test
    public void testPrecisionQualifierIsPreserved() {
        String source = "#version 450 core\n"
                + "uniform highp sampler2D t;\n";

        String out = ShaderTranslator.ensureDescriptorBindings(source, Map.of("t", 4));

        Assertions.assertTrue(out.contains("layout(binding = 4) uniform highp sampler2D t;"),
                "A precision qualifier must be preserved while the binding is injected: " + out);
    }

    @Test
    public void testInjectionIsIdempotent() {
        String source = "#version 450 core\n"
                + "uniform sampler2D depth;\n"
                + "uniform sampler2D altDepth;\n"
                + "layout(set = 1) uniform image2D storage;\n";

        Map<String, Integer> bindings = new LinkedHashMap<>();
        bindings.put("depth", 0);
        bindings.put("altDepth", 1);
        bindings.put("storage", 2);

        String once = ShaderTranslator.ensureDescriptorBindings(source, bindings);
        String twice = ShaderTranslator.ensureDescriptorBindings(once, bindings);

        Assertions.assertEquals(once, twice, "Applying the injection twice must be a no-op");
        Assertions.assertEquals(1, countOccurrences(once, "binding = 0"),
                "The output must carry exactly one binding per descriptor: " + once);
    }

    @Test
    public void testNonOpaqueUniformIsLeftUntouched() {
        String source = "#version 450 core\n"
                + "uniform float decay;\n"
                + "uniform mat4 projection;\n";

        String out = ShaderTranslator.ensureDescriptorBindings(source, Map.of("decay", 0, "projection", 1));

        Assertions.assertEquals(source, out,
                "Non-opaque uniforms are owned by the injected block and must not gain a layout: " + out);
    }

    @Test
    public void testCollectDescriptorNamesSeesBareSamplers() {
        String source = "#version 450 core\n"
                + "uniform sampler2D depth;\n"
                + "uniform sampler2D altDepth;\n";

        List<String> names = ShaderTranslator.collectDescriptorNames(source);

        Assertions.assertEquals(List.of("depth", "altDepth"), names,
                "Bare samplers must be discoverable so the unified map can assign them bindings");
    }

    @Test
    public void testCenterDepthStyleSourceGetsDenseBindings() {
        // The shape that triggered the runtime failure: Iris' centerDepthSmooth fragment stage
        // declares two bare samplers and no layout(binding) at all.
        String source = "#version 450 core\n"
                + "uniform sampler2D depth;\n"
                + "uniform sampler2D altDepth;\n"
                + "uniform float lastFrameTime;\n"
                + "uniform float decay;\n"
                + "out float iris_fragColor;\n"
                + "void main() { iris_fragColor = texture(depth, vec2(0.5)).r * decay; }\n";

        // Mirror the bridge: discover the descriptors, then assign dense 0..N-1 bindings.
        List<String> names = ShaderTranslator.collectDescriptorNames(source);
        Map<String, Integer> bindings = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            bindings.put(names.get(i), i);
        }

        String out = ShaderTranslator.ensureDescriptorBindings(source, bindings);

        Assertions.assertTrue(out.contains("layout(binding = 0) uniform sampler2D depth;"),
                "centerDepth's first sampler must be bound to 0: " + out);
        Assertions.assertTrue(out.contains("layout(binding = 1) uniform sampler2D altDepth;"),
                "centerDepth's second sampler must be bound to 1: " + out);
        Assertions.assertEquals(2, countOccurrences(out, "layout(binding ="),
                "Every bare sampler must now carry a binding: " + out);
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
