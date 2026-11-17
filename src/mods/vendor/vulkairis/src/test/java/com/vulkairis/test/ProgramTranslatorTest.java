package com.vulkairis.test;

import com.vulkairis.translation.ProgramTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.ShaderCompileResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.util.List;
import java.util.Map;

/**
 * Phase B: golden program-together translation fixtures plus the location-collision
 * regression test.
 *
 * <p>Four vertex/fragment pairs (terrain, entity, cloud, sky) cover every legacy mapping:
 * {@code texture2D*}/{@code texture2DLod}&#8594;{@code texture*}/{@code textureLod},
 * {@code shadow2D*} wrapping, {@code gl_FragData[n]}/{@code gl_FragColor} indexed outputs,
 * {@code gl_TextureMatrix}/{@code ftransform}/{@code gl_Color}/{@code gl_Normal} mappings and
 * depth/volume conversion. Each translated stage must carry no {@code layout(binding =)},
 * no {@code layout(location =)} except fragment outputs, and must compile to SPIR-V — the
 * fix5 legacy errors go to zero before anything touches the driver.</p>
 */
public class ProgramTranslatorTest {

    // ------------------------------------------------------------------
    // Golden fixtures: legacy Iris-style sources per program.
    // ------------------------------------------------------------------

    static String terrainVertex() {
        return """
                #version 120
                attribute vec3 Position;
                attribute vec2 UV0;
                uniform mat4 projection;
                uniform vec3 sunPosition;
                uniform sampler2D occlusionTex;
                varying vec2 texCoord;
                void main() {
                    texCoord = UV0;
                    vec4 uvTransform = gl_TextureMatrix[1] * vec4(UV0, 0.0, 1.0);
                    float occluded = texture2DLod(occlusionTex, uvTransform.xy, 0.0).r;
                    vec4 worldPos = vec4(Position + sunPosition * occluded, 1.0);
                    gl_Position = projection * worldPos + ftransform() * 0.0;
                }
                """;
    }

    static String terrainFragment() {
        return """
                #version 120
                uniform sampler2D gtexture;
                uniform sampler2DShadow shadowtex0;
                uniform float rainStrength = 1.0;
                varying vec2 texCoord;
                void main() {
                    vec4 albedo = texture2D(gtexture, texCoord);
                    float shadow = shadow2D(shadowtex0, vec3(texCoord, 0.5));
                    gl_FragData[0] = albedo * shadow * rainStrength;
                }
                """;
    }

    static String entityVertex() {
        return """
                #version 120
                attribute vec3 Position;
                attribute vec2 UV0;
                uniform sampler2D gtexture;
                varying vec2 texCoord;
                varying vec3 normalOut;
                varying vec4 colorOut;
                void main() {
                    texCoord = UV0;
                    normalOut = gl_Normal;
                    colorOut = gl_Color;
                    vec4 projected = texture2DProj(gtexture, vec4(UV0, 0.0, 1.0));
                    gl_Position = gl_ModelViewProjectionMatrix * vec4(Position + projected.xyz * 0.01, 1.0);
                }
                """;
    }

    static String entityFragment() {
        return """
                #version 120
                uniform sampler2D gtexture;
                uniform sampler2D lightmap;
                varying vec2 texCoord;
                varying vec3 normalOut;
                varying vec4 colorOut;
                void main() {
                    vec4 albedo = texture2DLod(gtexture, texCoord, 2.0);
                    vec4 light = texture(lightmap, normalOut.xy);
                    gl_FragData[0] = albedo * colorOut;
                    gl_FragData[1] = light;
                }
                """;
    }

    static String cloudVertex() {
        return """
                #version 120
                varying vec2 texCoord;
                void main() {
                    texCoord = gl_MultiTexCoord0.xy;
                    gl_Position = ftransform();
                }
                """;
    }

    static String cloudFragment() {
        return """
                #version 120
                uniform sampler3D cloudVolume;
                varying vec2 texCoord;
                void main() {
                    vec4 density = texture3D(cloudVolume, vec3(texCoord, 0.5));
                    vec4 levelled = texture3DLod(cloudVolume, vec3(texCoord, 0.25), 1.0);
                    gl_FragColor = density + levelled;
                }
                """;
    }

    static String skyVertex() {
        return """
                #version 120
                attribute vec3 Position;
                varying vec3 skyDir;
                void main() {
                    skyDir = (gl_TextureMatrix[0] * vec4(Position, 1.0)).xyz;
                    gl_Position = vec4(Position.xy, 0.0, 1.0);
                }
                """;
    }

    static String skyFragment() {
        return """
                #version 120
                uniform sampler2DShadow shadowtex1;
                uniform samplerCube skybox;
                uniform sampler2D gtexture;
                uniform bool useDetail = true;
                varying vec3 skyDir;
                void main() {
                    float shadow = shadow2DProj(shadowtex1, vec4(skyDir.xy, 0.5, 1.0));
                    vec4 sky = textureCubeLod(skybox, skyDir, 1.0);
                    vec4 detail = texture2DBias(gtexture, skyDir.xy, -1.0);
                    vec4 shaded = sky * shadow;
                    if (useDetail != 0) {
                        shaded += detail * 0.01;
                    }
                    gl_FragData[0] = shaded;
                }
                """;
    }

    // ------------------------------------------------------------------
    // Golden assertions shared by every pair.
    // ------------------------------------------------------------------

    private static ProgramTranslator.TranslatedProgram translatePair(
            String program, String vertex, String fragment) {
        ProgramTranslator.clearCacheForTests();
        return ProgramTranslator.translate(program, List.of(
                new ProgramTranslator.StageUnit("VERTEX", vertex, program + ".vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", fragment, program + ".fsh")),
                "golden-pack");
    }

    private static void assertFix5ErrorsGone(String translated) {
        // "texture2D" covers texture2DProj/Bias/Lod/Rect as substrings; "texture(" itself
        // must survive, so the bare call form is asserted with its parenthesis.
        for (String legacy : List.of("texture2D", "texture3D", "textureCube", "shadow2D",
                "gl_FragData", "gl_FragColor", "gl_TextureMatrix", "ftransform(",
                "gl_Color", "gl_Normal", "gl_MultiTexCoord", "gl_ModelViewProjectionMatrix",
                "gl_Vertex")) {
            Assertions.assertFalse(translated.contains(legacy),
                    "Legacy builtin must not survive translation: " + legacy + "\n" + translated);
        }
    }

    private static void assertBindingPolicy(
            String vertex, String fragment, int expectedFragmentOutputs) {
        Assertions.assertFalse(vertex.contains("layout(binding"), vertex);
        Assertions.assertFalse(fragment.contains("layout(binding"), fragment);
        Assertions.assertFalse(vertex.contains("layout(location"), vertex);

        int fragmentLocations = countOccurrences(fragment, "layout(location = ");
        Assertions.assertEquals(expectedFragmentOutputs, fragmentLocations,
                "Only fragment outputs may carry locations, one each:\n" + fragment);
    }

    private static void assertCompiles(String name, String glsl, int shadercKind) {
        ShaderCompileResult result =
                SpirvCompiler.compileGlslToSpirvDetailed(name, glsl, shadercKind);
        Assertions.assertTrue(result.succeeded(),
                "Translated stage must compile: " + name + ": " + result.compilerLog());
        Assertions.assertNotNull(result.spirv());
        Assertions.assertTrue(result.spirv().remaining() > 0);
    }

    // ------------------------------------------------------------------
    // The four golden pairs.
    // ------------------------------------------------------------------

    @Test
    public void testTerrainPairTranslatesAndCompiles() {
        ProgramTranslator.TranslatedProgram program =
                translatePair("gbuffers_terrain", terrainVertex(), terrainFragment());

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        assertFix5ErrorsGone(vert);
        assertFix5ErrorsGone(frag);
        Assertions.assertTrue(vert.contains("textureLod(occlusionTex, uvTransform.xy, 0.0)"), vert);
        Assertions.assertTrue(vert.contains("vulkairis_TextureMatrix[1]"), vert);
        Assertions.assertTrue(frag.contains("texture(gtexture, texCoord)"), frag);
        Assertions.assertTrue(frag.contains("texture(shadowtex0, vec3(texCoord, 0.5))"), frag);
        Assertions.assertTrue(frag.contains("vulkairisFragData0"), frag);

        // One union block shared by both stages: projection/sunPosition from the vertex side,
        // rainStrength from the fragment side, plus the legacy matrix uniforms.
        for (String member : List.of("mat4 projection;", "vec3 sunPosition;",
                "float rainStrength;", "mat4 vulkairis_TextureMatrix[8];",
                "mat4 vulkairis_ModelViewProjectionMatrix;")) {
            Assertions.assertTrue(vert.contains(member), "Vertex misses union member " + member);
            Assertions.assertTrue(frag.contains(member), "Fragment misses union member " + member);
        }
        Assertions.assertTrue(program.unionVaryings().get("texCoord").equals("both"), program.toString());

        assertBindingPolicy(vert, frag, 1);
        Assertions.assertEquals(Map.of("vulkairisFragData0", 0), program.fragmentOutputLocations());

        assertCompiles("gbuffers_terrain.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("gbuffers_terrain.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    public void testEntityPairTranslatesAndCompiles() {
        ProgramTranslator.TranslatedProgram program =
                translatePair("gbuffers_entity", entityVertex(), entityFragment());

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        assertFix5ErrorsGone(vert);
        assertFix5ErrorsGone(frag);
        Assertions.assertTrue(vert.contains("vulkairis_Normal"), vert);
        Assertions.assertTrue(vert.contains("vulkairis_Color"), vert);
        Assertions.assertTrue(vert.contains("textureProj(gtexture, vec4(UV0, 0.0, 1.0))"), vert);
        Assertions.assertTrue(vert.contains("vulkairis_ModelViewProjectionMatrix"), vert);
        Assertions.assertTrue(frag.contains("textureLod(gtexture, texCoord, 2.0)"), frag);

        // Two indexed outputs: gl_FragData[0] and gl_FragData[1] keep their indices.
        Assertions.assertEquals(Map.of("vulkairisFragData0", 0, "vulkairisFragData1", 1),
                program.fragmentOutputLocations());
        assertBindingPolicy(vert, frag, 2);

        assertCompiles("gbuffers_entity.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("gbuffers_entity.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    public void testCloudPairTranslatesAndCompiles() {
        ProgramTranslator.TranslatedProgram program =
                translatePair("gbuffers_cloud", cloudVertex(), cloudFragment());

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        assertFix5ErrorsGone(vert);
        assertFix5ErrorsGone(frag);
        Assertions.assertTrue(vert.contains("vulkairis_MultiTexCoord0"), vert);
        Assertions.assertTrue(vert.contains("(vulkairis_ModelViewProjectionMatrix * vulkairis_Vertex)"),
                vert);
        Assertions.assertTrue(vert.contains("in vec4 vulkairis_Vertex;"), vert);
        Assertions.assertTrue(frag.contains("texture(cloudVolume, vec3(texCoord, 0.5))"), frag);
        Assertions.assertTrue(frag.contains("textureLod(cloudVolume, vec3(texCoord, 0.25), 1.0)"),
                frag);

        assertBindingPolicy(vert, frag, 1);

        assertCompiles("gbuffers_cloud.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("gbuffers_cloud.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    public void testSkyPairTranslatesAndCompiles() {
        ProgramTranslator.TranslatedProgram program =
                translatePair("gbuffers_sky", skyVertex(), skyFragment());

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        assertFix5ErrorsGone(vert);
        assertFix5ErrorsGone(frag);
        Assertions.assertTrue(vert.contains("vulkairis_TextureMatrix[0]"), vert);
        Assertions.assertTrue(frag.contains("textureProj(shadowtex1, vec4(skyDir.xy, 0.5, 1.0))"),
                frag);
        Assertions.assertTrue(frag.contains("textureLod(skybox, skyDir, 1.0)"), frag);
        Assertions.assertTrue(frag.contains("texture(gtexture, skyDir.xy, -1.0)"), frag);
        // The defaulted bool uniform joins the union block as int (VulkanMod-bound rule).
        // Scope the check to the block body: the stubbed original survives as a comment.
        int blockStart = frag.indexOf("VulkairisUniformBlock");
        Assertions.assertTrue(blockStart >= 0, frag);
        String members = frag.substring(frag.indexOf('{', blockStart), frag.indexOf('}', blockStart));
        Assertions.assertTrue(members.contains("int useDetail;"), members);
        Assertions.assertFalse(members.contains("bool"), members);

        assertBindingPolicy(vert, frag, 1);

        assertCompiles("gbuffers_sky.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("gbuffers_sky.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    // ------------------------------------------------------------------
    // Location-collision regression: overlapping user locations collapse.
    // ------------------------------------------------------------------

    @Test
    public void testOverlappingLocationsDoNotCollideAfterTranslation() {
        String vertex = """
                #version 120
                layout(location = 0) in vec3 first;
                layout(location = 0) in vec3 second;
                out vec3 bridge;
                void main() {
                    bridge = first + second;
                    gl_Position = vec4(bridge, 1.0);
                }
                """;
        String fragment = """
                #version 120
                layout(location = 0) in vec3 bridge;
                layout(location = 0) out vec4 targetA;
                layout(location = 1) out vec4 targetB;
                void main() {
                    targetA = vec4(bridge, 1.0);
                    targetB = vec4(bridge, 1.0);
                }
                """;

        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(
                "collision", List.of(
                        new ProgramTranslator.StageUnit("VERTEX", vertex, "collision.vsh"),
                        new ProgramTranslator.StageUnit("FRAGMENT", fragment, "collision.fsh")),
                "golden-pack");

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        // The vertex stage keeps no locations at all: nothing can collide there.
        Assertions.assertFalse(vert.contains("layout(location"), vert);
        Assertions.assertTrue(vert.contains("in vec3 first;"), vert);
        Assertions.assertTrue(vert.contains("in vec3 second;"), vert);

        // Fragment outputs are renumbered densely from the stripped sources.
        Assertions.assertEquals(Map.of("targetA", 0, "targetB", 1),
                program.fragmentOutputLocations());
        Assertions.assertEquals(1, countOccurrences(frag, "layout(location = 0)"), frag);
        Assertions.assertEquals(1, countOccurrences(frag, "layout(location = 1)"), frag);
        Assertions.assertTrue(frag.contains("layout(location = 0) out vec4 targetA;"), frag);
        Assertions.assertTrue(frag.contains("layout(location = 1) out vec4 targetB;"), frag);
        Assertions.assertFalse(frag.contains("layout(binding"), frag);

        assertCompiles("collision.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("collision.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    // ------------------------------------------------------------------
    // Generation gating: a pack/dimension change invalidates the cache.
    // ------------------------------------------------------------------

    @Test
    public void testGenerationChangeInvalidatesProgramCache() {
        ProgramTranslator.clearCacheForTests();
        List<ProgramTranslator.StageUnit> units = List.of(
                new ProgramTranslator.StageUnit("VERTEX", terrainVertex(), "gated.vsh"),
                new ProgramTranslator.StageUnit("FRAGMENT", terrainFragment(), "gated.fsh"));

        ProgramTranslator.TranslatedProgram first =
                ProgramTranslator.translate("gated", units, "pack", 7001);
        ProgramTranslator.TranslatedProgram cached =
                ProgramTranslator.translate("gated", units, "pack", 7001);

        Assertions.assertSame(first, cached, "Same generation must hit the program cache");
        Assertions.assertEquals(7001, cached.generation());

        ProgramTranslator.TranslatedProgram next =
                ProgramTranslator.translate("gated", units, "pack", 7002);

        Assertions.assertNotSame(first, next, "A new generation must never reuse the old entry");
        Assertions.assertEquals(7002, next.generation());
        Assertions.assertEquals(first.translationKey(), next.translationKey(),
                "Only the generation differs; the content key is stable");
    }

    @Test
    public void testStripHelpersAndNullSafety() {
        Assertions.assertNull(ProgramTranslator.stripBindingQualifiers(null));
        Assertions.assertNull(ProgramTranslator.stripAllLocationQualifiers(null));
        Assertions.assertEquals("", ProgramTranslator.stripBindingQualifiers(""));
        Assertions.assertEquals("", ProgramTranslator.stripAllLocationQualifiers(""));

        String bound = "layout(binding = 100) uniform sampler2D tex;\n";
        String stripped = ProgramTranslator.stripBindingQualifiers(bound);
        Assertions.assertFalse(stripped.contains("binding"), stripped);
        Assertions.assertTrue(stripped.contains("uniform sampler2D tex;"), stripped);

        // std140 survives while the binding goes: the block stays valid for reflection.
        String block = "layout(std140, binding = 100) uniform VulkairisUniformBlock {\n    mat4 m;\n};\n";
        String blockStripped = ProgramTranslator.stripBindingQualifiers(block);
        Assertions.assertTrue(blockStripped.contains("layout(std140) uniform VulkairisUniformBlock"),
                blockStripped);
    }

    // ------------------------------------------------------------------
    // Phase B gaps B-4/B-5 golden: shadow2DProjLod wrap plus precision strip
    // and non-constant const demotion, end to end with SPIR-V compiles.
    // ------------------------------------------------------------------

    @Test
    public void testShadowProjLodPrecisionAndConstDemotionPairCompiles() {
        String vertex = """
                #version 120
                precision mediump float;
                attribute vec3 Position;
                attribute vec2 UV0;
                varying vec2 texCoord;
                const float SCALE = 2.0;
                const int STEPS = 3;
                void main() {
                    texCoord = UV0 * SCALE;
                    gl_Position = vec4(Position, 1.0);
                }
                """;
        String fragment = """
                #version 120
                precision mediump float;
                precision lowp int;
                uniform sampler2DShadow shadowtex0;
                uniform float frameTime;
                varying vec2 texCoord;
                const float PI = 3.14159;
                void main() {
                    const float exposure = frameTime * 2.0;
                    float shadow = shadow2DProjLod(shadowtex0, vec4(texCoord, 0.5, 1.0), 1.0);
                    gl_FragData[0] = vec4(shadow * exposure * PI);
                }
                """;

        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(
                "shadowlod", List.of(
                        new ProgramTranslator.StageUnit("VERTEX", vertex, "shadowlod.vsh"),
                        new ProgramTranslator.StageUnit("FRAGMENT", fragment, "shadowlod.fsh")),
                "golden-pack");

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        assertFix5ErrorsGone(vert);
        assertFix5ErrorsGone(frag);
        Assertions.assertFalse(frag.contains("shadow2D"), frag);
        Assertions.assertTrue(frag.contains("textureProjLod(shadowtex0, vec4(texCoord, 0.5, 1.0), 1.0)"),
                frag);
        Assertions.assertFalse(vert.contains("precision mediump float;"), vert);
        Assertions.assertFalse(frag.contains("precision mediump float;"), frag);
        Assertions.assertFalse(frag.contains("precision lowp int;"), frag);
        Assertions.assertTrue(vert.contains("const float SCALE = 2.0;"), vert);
        Assertions.assertTrue(frag.contains("const float PI = 3.14159;"), frag);
        Assertions.assertTrue(frag.contains("float exposure = frameTime * 2.0;"), frag);
        Assertions.assertFalse(frag.contains("const float exposure"), frag);

        assertBindingPolicy(vert, frag, 1);
        assertCompiles("shadowlod.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("shadowlod.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    // ------------------------------------------------------------------
    // Plan §4 remainder (§3.2 + §5): mat4 + array + struct varying split pair,
    // one-sided-varying survival, and bool-varying conversion, end to end.
    // ------------------------------------------------------------------

    @Test
    public void testMatArrayStructVaryingPairSplitsEndToEnd() {
        String structDef = """
                struct Surface {
                    vec3 albedo;
                    float roughness;
                };
                """;
        String vertex = """
                #version 120
                attribute vec3 Position;
                attribute vec2 UV0;
                varying mat4 shadowMatrix;
                varying vec4 texCoords[2];
                varying vec2 texCoord;
                """
                + structDef + """
                varying Surface surface;
                void main() {
                    shadowMatrix = mat4(1.0);
                    texCoords[0] = vec4(UV0, 0.0, 1.0);
                    texCoords[1] = vec4(UV0, 1.0, 0.0);
                    surface.albedo = vec3(1.0, 0.5, 0.25);
                    surface.roughness = 0.5;
                    texCoord = UV0;
                    gl_Position = shadowMatrix * vec4(Position, 1.0);
                }
                """;
        String fragment = """
                #version 120
                varying mat4 shadowMatrix;
                varying vec4 texCoords[2];
                varying vec2 texCoord;
                """
                + structDef + """
                varying Surface surface;
                void main() {
                    vec4 base = shadowMatrix * vec4(texCoords[0].xyz, 1.0);
                    vec3 a = surface.albedo;
                    float r = surface.roughness;
                    vec4 extra = texCoords[1] + vec4(a * r, 1.0);
                    gl_FragData[0] = base + extra + vec4(texCoord, 0.0, 1.0);
                }
                """;

        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(
                "splitvaryings", List.of(
                        new ProgramTranslator.StageUnit("VERTEX", vertex, "splitvaryings.vsh"),
                        new ProgramTranslator.StageUnit("FRAGMENT", fragment, "splitvaryings.fsh")),
                "golden-pack");

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        assertFix5ErrorsGone(vert);
        assertFix5ErrorsGone(frag);

        // Matrix scalarizes to one vec4 leaf per column on both stages, with a
        // reconstruction define so every read keeps working.
        for (String leaf : List.of("shadowMatrix_c0", "shadowMatrix_c1",
                "shadowMatrix_c2", "shadowMatrix_c3")) {
            Assertions.assertTrue(vert.contains(leaf), "Vertex misses split leaf " + leaf);
            Assertions.assertTrue(frag.contains(leaf), "Fragment misses split leaf " + leaf);
        }
        Assertions.assertFalse(vert.contains("mat4 shadowMatrix;"), vert);
        Assertions.assertFalse(frag.contains("mat4 shadowMatrix;"), frag);
        Assertions.assertTrue(vert.contains("#define shadowMatrix mat4("), vert);
        Assertions.assertTrue(frag.contains("#define shadowMatrix mat4("), frag);
        // The whole-matrix write decomposes per column.
        Assertions.assertTrue(vert.contains("shadowMatrix_c0 = (mat4(1.0))[0];"), vert);

        // Array scalarizes to one leaf per element; literal subscripts rewrite.
        Assertions.assertTrue(vert.contains("texCoords_0"), vert);
        Assertions.assertTrue(vert.contains("texCoords_1"), vert);
        Assertions.assertTrue(frag.contains("texCoords_0"), frag);
        Assertions.assertFalse(vert.contains("texCoords[2]"), vert);
        Assertions.assertFalse(frag.contains("texCoords[2]"), frag);

        // Struct scalarizes per member; member accesses rewrite.
        Assertions.assertTrue(vert.contains("surface_m0"), vert);
        Assertions.assertTrue(vert.contains("surface_m1"), vert);
        Assertions.assertTrue(frag.contains("surface_m0"), frag);
        Assertions.assertFalse(vert.contains("Surface surface;"), vert);
        Assertions.assertFalse(frag.contains("Surface surface;"), frag);

        // Union inventory stays base-named with rank==location slot discipline:
        // mat4 takes 4, the vec4 array takes 2, the struct takes 2, vec2 takes 1.
        Assertions.assertEquals("both", program.unionVaryings().get("shadowMatrix"));
        Assertions.assertEquals("both", program.unionVaryings().get("texCoords"));
        Assertions.assertEquals("both", program.unionVaryings().get("surface"));
        Assertions.assertEquals("both", program.unionVaryings().get("texCoord"));

        assertBindingPolicy(vert, frag, 1);
        Assertions.assertEquals(Map.of("vulkairisFragData0", 0), program.fragmentOutputLocations());

        assertCompiles("splitvaryings.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("splitvaryings.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    public void testOneSidedVaryingSurvivesLink() {
        String vertex = """
                #version 120
                attribute vec3 Position;
                attribute vec2 UV0;
                varying vec2 texCoord;
                varying vec3 soloOut;
                void main() {
                    texCoord = UV0;
                    soloOut = vec3(1.0, 0.5, 0.25);
                    gl_Position = vec4(Position + soloOut * 0.01, 1.0);
                }
                """;
        String fragment = """
                #version 120
                varying vec2 texCoord;
                void main() {
                    gl_FragData[0] = vec4(texCoord, 0.0, 1.0);
                }
                """;

        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(
                "onesided", List.of(
                        new ProgramTranslator.StageUnit("VERTEX", vertex, "onesided.vsh"),
                        new ProgramTranslator.StageUnit("FRAGMENT", fragment, "onesided.fsh")),
                "golden-pack");

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        Assertions.assertEquals("both", program.unionVaryings().get("texCoord"));
        Assertions.assertEquals("vertex-out", program.unionVaryings().get("soloOut"));
        Assertions.assertTrue(program.notes().stream()
                .anyMatch(note -> note.contains("soloOut") && note.contains("only one side")),
                "One-sided varying must be noted, got: " + program.notes());

        // The one-sided declaration survives instead of failing the link.
        Assertions.assertTrue(vert.contains("soloOut"), vert);

        assertBindingPolicy(vert, frag, 1);
        assertCompiles("onesided.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("onesided.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
    }

    @Test
    public void testBoolVaryingConvertsToIntEndToEnd() {
        String vertex = """
                #version 120
                attribute vec3 Position;
                varying vec2 texCoord;
                varying bool useDetail;
                bool IsDay() { return true; }
                void main() {
                    texCoord = vec2(0.5);
                    useDetail = true;
                    gl_Position = vec4(Position + vec3(float(IsDay() ? 1 : 0) * 0.001), 1.0);
                }
                """;
        String fragment = """
                #version 120
                varying vec2 texCoord;
                varying bool useDetail;
                bool IsDay() { return true; }
                void main() {
                    vec4 color = vec4(texCoord, 0.0, 1.0);
                    if (useDetail) {
                        color += vec4(0.1);
                    }
                    if (!useDetail) {
                        color -= vec4(0.05);
                    }
                    gl_FragData[0] = color + vec4(float(IsDay() ? 1 : 0) * 0.001);
                }
                """;

        ProgramTranslator.clearCacheForTests();
        ProgramTranslator.TranslatedProgram program = ProgramTranslator.translate(
                "boolvarying", List.of(
                        new ProgramTranslator.StageUnit("VERTEX", vertex, "boolvarying.vsh"),
                        new ProgramTranslator.StageUnit("FRAGMENT", fragment, "boolvarying.fsh")),
                "golden-pack");

        String vert = program.stages().get("VERTEX").glsl();
        String frag = program.stages().get("FRAGMENT").glsl();

        // Varying bools become flat ints on both stages; writes use 1/0 and
        // conditions gain an explicit comparison (GLSL needs a real boolean).
        Assertions.assertTrue(vert.contains("int useDetail;"), vert);
        Assertions.assertTrue(frag.contains("int useDetail;"), frag);
        Assertions.assertTrue(vert.contains("flat out int useDetail;"), vert);
        Assertions.assertTrue(frag.contains("flat in int useDetail;"), frag);
        Assertions.assertFalse(vert.contains("bool useDetail"), vert);
        Assertions.assertFalse(frag.contains("bool useDetail"), frag);
        Assertions.assertTrue(vert.contains("useDetail = 1;"), vert);
        Assertions.assertTrue(frag.contains("if (useDetail != 0)"), frag);
        Assertions.assertTrue(frag.contains("(useDetail == 0)"), frag);

        // Function signatures intentionally untouched.
        Assertions.assertTrue(vert.contains("bool IsDay()"), vert);
        Assertions.assertTrue(frag.contains("bool IsDay()"), frag);
        Assertions.assertTrue(vert.contains("return true;"), vert);
        Assertions.assertTrue(frag.contains("return true;"), frag);

        Assertions.assertEquals("both", program.unionVaryings().get("useDetail"));

        assertBindingPolicy(vert, frag, 1);
        assertCompiles("boolvarying.vsh", vert, Shaderc.shaderc_glsl_vertex_shader);
        assertCompiles("boolvarying.fsh", frag, Shaderc.shaderc_glsl_fragment_shader);
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
