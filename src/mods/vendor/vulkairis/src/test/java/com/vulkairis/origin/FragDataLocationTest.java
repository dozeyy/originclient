package com.vulkairis.origin;

import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.ShaderTranslator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin (step 4d): gl_FragData[n] must land at location n, in the union translation and after the
 * bridge's own location pass, or a multi-target pass writes each buffer with another output's value
 * (probe run 44036544134: MakeUp's composite put its exposure into colortex0 and 0 into colortex6).
 */
class FragDataLocationTest {
    private static final String LOCATION_PREFIX = "layout \\(location = \\d\\) ";
    private static final Pattern LOCATED_OUT = Pattern.compile(
            "layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)\\s*out\\s+\\w+\\s+(\\w+)\\s*;");

    @Test
    void fragDataIndexIsItsLocation() {
        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate("composite", List.of(
                new ProgramTranslator.StageUnit("VERTEX", """
                        #version 120
                        varying vec2 texcoord;
                        varying float exposure;
                        void main() {
                            texcoord = gl_MultiTexCoord0.xy;
                            exposure = 1.0;
                            gl_Position = ftransform();
                        }
                        """, "composite.vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", """
                        #version 120
                        uniform sampler2D colortex1;
                        varying vec2 texcoord;
                        varying float exposure;
                        void main() {
                            vec4 blockColor = texture2D(colortex1, texcoord);
                            /* DRAWBUFFERS:016 */
                            gl_FragData[0] = blockColor * 0.5;
                            gl_FragData[1] = blockColor;
                            gl_FragData[2] = vec4(exposure, 0.0, 0.0, 0.0);
                        }
                        """, "composite.fsh")), "origin-test");
        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();
        assertFragData(frag, "translator");

        Map<String, Integer> bindings = ShaderTranslator.buildUnifiedBindingMap(vert, frag);
        String vr = ShaderTranslator.ensureDescriptorBindings(ShaderTranslator.remapDescriptorBindings(vert, bindings), bindings);
        String fr = ShaderTranslator.ensureDescriptorBindings(ShaderTranslator.remapDescriptorBindings(frag, bindings), bindings);
        String bridged = ShaderTranslator.injectLayoutLocations(fr, false, Map.of(),
                ProgramTranslator.varyingLocations(vr, fr));
        assertFragData(bridged, "bridge");
    }

    @Test
    void irisFragDataKeepsItsLocationWhenDeclaredInReverse() {
        String located = """
                #version 330 core
                layout (location = 2) out vec4 iris_FragData2;
                layout (location = 1) out vec4 iris_FragData1;
                layout (location = 0) out vec4 iris_FragData0;
                in vec2 texcoord;
                void main() {
                    iris_FragData0 = vec4(texcoord, 0.0, 1.0);
                    iris_FragData1 = vec4(1.0);
                    iris_FragData2 = vec4(0.524, 0.0, 0.0, 0.0);
                }
                """;
        assertIrisPath(located, "explicit");
        assertIrisPath(located.replaceAll(LOCATION_PREFIX, ""), "suffix");
    }

    private static void assertIrisPath(String fragSource, String label) {
        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate("composite", List.of(
                new ProgramTranslator.StageUnit("VERTEX", """
                        #version 330 core
                        in vec3 Position;
                        in vec2 UV0;
                        out vec2 texcoord;
                        void main() {
                            texcoord = UV0;
                            gl_Position = vec4(Position, 1.0);
                        }
                        """, "composite.vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", fragSource, "composite.fsh")), "origin-test");
        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();
        assertNamed(frag, "iris_FragData", 3, label + " translator");
        Map<String, Integer> bindings = ShaderTranslator.buildUnifiedBindingMap(vert, frag);
        String vr = ShaderTranslator.ensureDescriptorBindings(ShaderTranslator.remapDescriptorBindings(vert, bindings), bindings);
        String fr = ShaderTranslator.ensureDescriptorBindings(ShaderTranslator.remapDescriptorBindings(frag, bindings), bindings);
        String bridged = ShaderTranslator.injectLayoutLocations(fr, false, Map.of(),
                ProgramTranslator.varyingLocations(vr, fr));
        assertNamed(bridged, "iris_FragData", 3, label + " bridge");
        // The bridge alone (a source that reached it with no locations) also keys on the suffix.
        String bare = fragSource.replaceAll(LOCATION_PREFIX, "");
        assertNamed(ShaderTranslator.injectLayoutLocations(bare, false, Map.of(), Map.of()),
                "iris_FragData", 3, label + " bridge-only");
    }

    private static void assertFragData(String src, String where) {
        assertNamed(src, "vulkairisFragData", 3, where);
    }

    private static void assertNamed(String src, String prefix, int expected, String where) {
        Matcher m = LOCATED_OUT.matcher(src);
        int seen = 0;
        while (m.find()) {
            String name = m.group(2);
            if (name.startsWith(prefix)) {
                seen++;
                Assertions.assertEquals(name.substring(prefix.length()), m.group(1),
                        where + ": " + name + " at location " + m.group(1) + "\n" + src);
            }
        }
        Assertions.assertEquals(expected, seen, where + ": located outputs\n" + src);
    }
}
