package com.vulkairis.test;

import com.vulkairis.translation.LegacyGlsl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase B: unit tests for {@link LegacyGlsl} (mapping table, frag-data, fixed-function
 * state, depth/volume conversion, varying-split helpers).
 *
 * <p>All tests are device-free and exercise the pure token-based rewrite plus its helpers.
 * Compile-level proof lives in {@link ProgramTranslatorTest}, which runs the golden pairs
 * through Shaderc.</p>
 */
public class LegacyGlslTest {

    // ------------------------------------------------------------------
    // Texture / shadow / volume lookup table.
    // ------------------------------------------------------------------

    @Test
    public void testTexture2DFamilyMapsToCoreCallees() {
        String source = """
                #version 450 core
                uniform sampler2D gtexture;
                uniform sampler2D lightmap;
                void main() {
                    vec4 a = texture2D(gtexture, vec2(0.5));
                    vec4 b = texture2DLod(lightmap, vec2(0.25), 2.0);
                    vec4 c = texture2DProj(gtexture, vec4(0.5, 0.5, 0.0, 1.0));
                    vec4 d = texture2DBias(gtexture, vec2(0.1), -1.0);
                }
                """;

        String rewritten = LegacyGlsl.rewriteTextureLookups(source);

        Assertions.assertTrue(rewritten.contains("texture(gtexture, vec2(0.5))"), rewritten);
        Assertions.assertTrue(rewritten.contains("textureLod(lightmap, vec2(0.25), 2.0)"), rewritten);
        Assertions.assertTrue(rewritten.contains("textureProj(gtexture, vec4(0.5, 0.5, 0.0, 1.0))"),
                rewritten);
        Assertions.assertTrue(rewritten.contains("texture(gtexture, vec2(0.1), -1.0)"), rewritten);
        Assertions.assertFalse(rewritten.contains("texture2D"), rewritten);
    }

    @Test
    public void testShadowLookupsAreWrappedNotRenamed() {
        String source = """
                #version 450 core
                uniform sampler2DShadow shadowtex0;
                void main() {
                    float a = shadow2D(shadowtex0, vec3(0.5, 0.5, 0.25));
                    float b = shadow2DProj(shadowtex0, vec4(0.5, 0.5, 0.25, 1.0));
                    float c = shadow2DLod(shadowtex0, vec3(0.1), 1.0);
                }
                """;

        String rewritten = LegacyGlsl.rewriteTextureLookups(source);

        Assertions.assertTrue(rewritten.contains("texture(shadowtex0, vec3(0.5, 0.5, 0.25))"),
                rewritten);
        Assertions.assertTrue(rewritten.contains("textureProj(shadowtex0, vec4(0.5, 0.5, 0.25, 1.0))"),
                rewritten);
        Assertions.assertTrue(rewritten.contains("textureLod(shadowtex0, vec3(0.1), 1.0)"), rewritten);
        Assertions.assertFalse(rewritten.contains("shadow2D"), rewritten);
        // The shadow sampler type itself is already core and must survive untouched.
        Assertions.assertTrue(rewritten.contains("sampler2DShadow"), rewritten);
    }

    @Test
    public void testVolumeLookupsModernize() {
        String source = """
                #version 450 core
                uniform sampler3D cloudVolume;
                void main() {
                    vec4 a = texture3D(cloudVolume, vec3(0.5));
                    vec4 b = texture3DLod(cloudVolume, vec3(0.25), 1.0);
                    vec4 c = textureCube(skybox, vec3(0.0, 1.0, 0.0));
                    vec4 d = textureCubeLod(skybox, vec3(0.0, 1.0, 0.0), 2.0);
                }
                """;

        String rewritten = LegacyGlsl.rewriteTextureLookups(source);

        Assertions.assertTrue(rewritten.contains("texture(cloudVolume, vec3(0.5))"), rewritten);
        Assertions.assertTrue(rewritten.contains("textureLod(cloudVolume, vec3(0.25), 1.0)"), rewritten);
        Assertions.assertTrue(rewritten.contains("texture(skybox, vec3(0.0, 1.0, 0.0))"), rewritten);
        Assertions.assertTrue(rewritten.contains("textureLod(skybox, vec3(0.0, 1.0, 0.0), 2.0)"),
                rewritten);
        Assertions.assertFalse(rewritten.contains("texture3D"), rewritten);
        Assertions.assertFalse(rewritten.contains("textureCube"), rewritten);
    }

    @Test
    public void testDepthBuiltinsPassThrough() {
        String source = """
                #version 450 core
                void main() {
                    float d = gl_FragCoord.z;
                    gl_FragDepth = d;
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, false);

        Assertions.assertTrue(result.source().contains("gl_FragCoord"), result.source());
        Assertions.assertTrue(result.source().contains("gl_FragDepth"), result.source());
        Assertions.assertTrue(result.fragDataIndices().isEmpty(), result.source());
    }

    @Test
    public void testRewriteIsIdempotent() {
        String source = """
                #version 450 core
                uniform sampler2D gtexture;
                uniform sampler2DShadow shadowtex0;
                void main() {
                    vec4 a = texture2D(gtexture, vec2(0.5));
                    float b = shadow2D(shadowtex0, vec3(0.5));
                    gl_FragData[0] = a * b;
                }
                """;

        String once = LegacyGlsl.rewriteTextureLookups(source);
        String twice = LegacyGlsl.rewriteTextureLookups(once);

        Assertions.assertEquals(once, twice, "Mapped callees must never match the table again");
    }

    @Test
    public void testCommentsStringsAndDirectivesAreNeverRewritten() {
        String source = """
                #version 450 core
                #define SAMPLE texture2D(gtexture, uv)
                // texture2D(gtexture, uv) and shadow2D(s, c) and gl_FragData[0]
                /* texture2DLod(a, b, c) */
                void main() {
                    String s = "texture2D";
                    vec4 a = texture2D(gtexture, vec2(0.5));
                }
                """;

        String rewritten = LegacyGlsl.rewriteTextureLookups(source);

        Assertions.assertTrue(rewritten.contains("#define SAMPLE texture2D(gtexture, uv)"),
                "Macro bodies live in directives and must be left alone: " + rewritten);
        Assertions.assertTrue(
                rewritten.contains("// texture2D(gtexture, uv) and shadow2D(s, c) and gl_FragData[0]"),
                rewritten);
        Assertions.assertTrue(rewritten.contains("/* texture2DLod(a, b, c) */"), rewritten);
        Assertions.assertTrue(rewritten.contains("\"texture2D\""), rewritten);
        Assertions.assertTrue(rewritten.contains("texture(gtexture, vec2(0.5))"), rewritten);
    }

    // ------------------------------------------------------------------
    // gl_FragData / gl_FragColor.
    // ------------------------------------------------------------------

    @Test
    public void testFragDataIndicesBecomeIndexedOutputs() {
        String source = """
                #version 450 core
                void main() {
                    gl_FragData[0] = vec4(1.0);
                    gl_FragData[2] = vec4(0.5);
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, false);

        Assertions.assertTrue(result.source().contains("vulkairisFragData0 = vec4(1.0)"),
                result.source());
        Assertions.assertTrue(result.source().contains("vulkairisFragData2 = vec4(0.5)"),
                result.source());
        Assertions.assertFalse(result.source().contains("gl_FragData"), result.source());
        Assertions.assertEquals(Set.of(0, 2), result.fragDataIndices());
    }

    @Test
    public void testFragColorBecomesIndexedOutputZero() {
        String source = """
                #version 450 core
                void main() {
                    gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, false);

        Assertions.assertTrue(result.source().contains("vulkairisFragData0 = vec4(1.0, 0.0, 0.0, 1.0)"),
                result.source());
        Assertions.assertFalse(result.source().contains("gl_FragColor"), result.source());
        Assertions.assertEquals(Set.of(0), result.fragDataIndices());
    }

    @Test
    public void testNonLiteralFragDataSubscriptIsLeftUntouched() {
        String source = "void main() { gl_FragData[i] = vec4(1.0); }\n";

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, false);

        Assertions.assertTrue(result.source().contains("gl_FragData[i]"), result.source());
        Assertions.assertTrue(result.fragDataIndices().isEmpty(), result.source());
    }

    @Test
    public void testFragOutputDeclarations() {
        String declarations = LegacyGlsl.fragOutputDeclarations(Set.of(2, 0));

        Assertions.assertTrue(declarations.contains("layout(location = 0) out vec4 vulkairisFragData0;"),
                declarations);
        Assertions.assertTrue(declarations.contains("layout(location = 2) out vec4 vulkairisFragData2;"),
                declarations);
        Assertions.assertEquals("", LegacyGlsl.fragOutputDeclarations(Set.of()));
        Assertions.assertEquals("", LegacyGlsl.fragOutputDeclarations(null));
    }

    @Test
    public void testCollectFragDataIndices() {
        Assertions.assertEquals(Set.of(0, 1),
                LegacyGlsl.collectFragDataIndices("gl_FragData[1] = gl_FragData[0];\n"));
        Assertions.assertEquals(Set.of(), LegacyGlsl.collectFragDataIndices(null));
        Assertions.assertEquals(Set.of(), LegacyGlsl.collectFragDataIndices("void main() {}\n"));
    }

    // ------------------------------------------------------------------
    // Fixed-function state: matrices, ftransform, color, normal.
    // ------------------------------------------------------------------

    @Test
    public void testTextureMatrixMapsToUniformArray() {
        String source = """
                void main() {
                    vec4 t = gl_TextureMatrix[1] * vec4(uv, 0.0, 1.0);
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, true);

        Assertions.assertTrue(result.source().contains("vulkairis_TextureMatrix[1]"), result.source());
        Assertions.assertFalse(result.source().contains("gl_TextureMatrix"), result.source());
        Assertions.assertTrue(result.needsTextureMatrix());
    }

    @Test
    public void testFtransformExpandsToExplicitMatrices() {
        String source = "void main() { gl_Position = ftransform(); }\n";

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, true);

        Assertions.assertTrue(result.source().contains(
                "gl_Position = (vulkairis_ModelViewProjectionMatrix * vulkairis_Vertex);"),
                result.source());
        Assertions.assertFalse(result.source().contains("ftransform"), result.source());
        Assertions.assertTrue(result.needsModelViewProjection());
        Assertions.assertTrue(result.needsVertexAttrib());
    }

    @Test
    public void testFtransformWithArgumentsIsLeftUntouched() {
        // ftransform() takes no arguments; a call with arguments is someone else's function.
        String source = "void main() { x = ftransform(y); }\n";

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, true);

        Assertions.assertTrue(result.source().contains("ftransform(y)"), result.source());
        Assertions.assertFalse(result.needsModelViewProjection());
    }

    @Test
    public void testColorAndNormalMapToAttributes() {
        String source = "void main() { vColor = gl_Color; vNormal = gl_Normal; }\n";

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, true);

        Assertions.assertTrue(result.source().contains("vColor = vulkairis_Color;"), result.source());
        Assertions.assertTrue(result.source().contains("vNormal = vulkairis_Normal;"), result.source());
        Assertions.assertTrue(result.needsColorAttrib());
        Assertions.assertTrue(result.needsNormalAttrib());
    }

    @Test
    public void testMatrixFamilyMapsToUniforms() {
        String source = """
                void main() {
                    vec4 a = gl_ModelViewProjectionMatrix * gl_Vertex;
                    vec4 b = gl_ModelViewMatrix * vec4(gl_Normal, 1.0);
                    vec4 c = gl_ProjectionMatrix * a;
                    vec3 n = gl_NormalMatrix * gl_Normal;
                    vec2 t0 = gl_MultiTexCoord0.xy;
                    vec2 t1 = gl_MultiTexCoord1.xy;
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, true);

        Assertions.assertTrue(result.source().contains("vulkairis_ModelViewProjectionMatrix"),
                result.source());
        Assertions.assertTrue(result.source().contains("vulkairis_ModelViewMatrix"), result.source());
        Assertions.assertTrue(result.source().contains("vulkairis_ProjectionMatrix"), result.source());
        Assertions.assertTrue(result.source().contains("vulkairis_NormalMatrix"), result.source());
        Assertions.assertTrue(result.source().contains("vulkairis_MultiTexCoord0"), result.source());
        Assertions.assertTrue(result.source().contains("vulkairis_MultiTexCoord1"), result.source());
        Assertions.assertFalse(result.source().contains("gl_ModelView"), result.source());
        Assertions.assertTrue(result.needsModelViewProjection());
        Assertions.assertTrue(result.needsModelView());
        Assertions.assertTrue(result.needsProjection());
        Assertions.assertTrue(result.needsNormalMatrix());
        Assertions.assertTrue(result.needsTexCoord0());
        Assertions.assertTrue(result.needsTexCoord1());
    }

    @Test
    public void testNullInputIsHandled() {
        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(null, true);

        Assertions.assertNull(result.source());
        Assertions.assertTrue(result.fragDataIndices().isEmpty());
        Assertions.assertNull(LegacyGlsl.rewriteTextureLookups(null));
    }

    // ------------------------------------------------------------------
    // Varying-split helpers.
    // ------------------------------------------------------------------

    @Test
    public void testLocationSlotsAccountForRank() {
        Assertions.assertEquals(1, LegacyGlsl.locationSlotsFor("vec4", 1));
        Assertions.assertEquals(3, LegacyGlsl.locationSlotsFor("vec4", 3));
        Assertions.assertEquals(4, LegacyGlsl.locationSlotsFor("mat4", 1));
        Assertions.assertEquals(8, LegacyGlsl.locationSlotsFor("mat4", 2));
        Assertions.assertEquals(2, LegacyGlsl.locationSlotsFor("mat2", 1));
        Assertions.assertEquals(4, LegacyGlsl.locationSlotsFor("mat4x3", 1));
    }

    @Test
    public void testMatrixVaryingSplitsIntoColumnLeaves() {
        List<LegacyGlsl.SplitLeaf> leaves = LegacyGlsl.splitVarying("mat4", "shadowMatrix", 1);

        Assertions.assertEquals(4, leaves.size());
        Assertions.assertEquals("shadowMatrix_c0", leaves.get(0).name());
        Assertions.assertEquals("vec4", leaves.get(0).type());
        Assertions.assertEquals("shadowMatrix_c3", leaves.get(3).name());

        Map<String, Integer> locations = LegacyGlsl.assignDenseLocations(leaves);
        Assertions.assertEquals(Map.of(
                "shadowMatrix_c0", 0, "shadowMatrix_c1", 1,
                "shadowMatrix_c2", 2, "shadowMatrix_c3", 3), locations);
    }

    @Test
    public void testArrayVaryingSplitsIntoElementLeaves() {
        List<LegacyGlsl.SplitLeaf> leaves = LegacyGlsl.splitVarying("vec4", "texCoords", 3);

        Assertions.assertEquals(3, leaves.size());
        Assertions.assertEquals("texCoords_0", leaves.get(0).name());
        Assertions.assertEquals("texCoords_2", leaves.get(2).name());

        Map<String, Integer> locations = LegacyGlsl.assignDenseLocations(leaves);
        Assertions.assertEquals(Map.of("texCoords_0", 0, "texCoords_1", 1, "texCoords_2", 2),
                locations);
    }

    @Test
    public void testStructVaryingSplitsPerMember() {
        List<LegacyGlsl.SplitLeaf> leaves =
                LegacyGlsl.splitStructVarying("surface", List.of("vec3", "float"));

        Assertions.assertEquals(2, leaves.size());
        Assertions.assertEquals("surface_m0", leaves.get(0).name());
        Assertions.assertEquals("vec3", leaves.get(0).type());
        Assertions.assertEquals("surface_m1", leaves.get(1).name());
    }

    @Test
    public void testDenseLocationsAccumulateAcrossMixedLeaves() {
        // A mat4 (4 slots once split) followed by a vec2 must start at location 4.
        List<LegacyGlsl.SplitLeaf> leaves = new java.util.ArrayList<>(
                LegacyGlsl.splitVarying("mat4", "m", 1));
        leaves.addAll(LegacyGlsl.splitVarying("vec2", "uv", 1));

        Map<String, Integer> locations = LegacyGlsl.assignDenseLocations(leaves);

        Assertions.assertEquals(0, locations.get("m_c0"));
        Assertions.assertEquals(3, locations.get("m_c3"));
        Assertions.assertEquals(4, locations.get("uv"));
        Assertions.assertEquals(5, new java.util.HashSet<>(locations.values()).size(),
                "Every leaf must own a distinct location");
    }

    @Test
    public void testUnionVaryingHelpers() {
        Map<String, Integer> outs = Map.of("texCoord", 1, "color", 1);
        Map<String, Integer> ins = Map.of("texCoord", 1, "normal", 1);

        Map<String, Integer> union = LegacyGlsl.unionVaryingSlots(outs, ins);

        Assertions.assertEquals(Set.of("texCoord", "color", "normal"), union.keySet());
        Assertions.assertEquals(Set.of("color", "normal"), LegacyGlsl.oneSidedVaryings(outs, ins));
        Assertions.assertEquals(Set.of(),
                LegacyGlsl.oneSidedVaryings(Map.of("a", 1), Map.of("a", 1)));
    }

    // ------------------------------------------------------------------
    // Phase B gaps B-4/B-5: shadow2DProjLod wrap, precision strip, const demotion.
    // ------------------------------------------------------------------

    @Test
    public void testShadow2DProjLodIsWrapped() {
        String source = """
                #version 450 core
                uniform sampler2DShadow shadowtex0;
                void main() {
                    float a = shadow2DProjLod(shadowtex0, vec4(0.5, 0.5, 0.25, 1.0), 2.0);
                }
                """;

        String rewritten = LegacyGlsl.rewriteTextureLookups(source);

        Assertions.assertTrue(
                rewritten.contains("textureProjLod(shadowtex0, vec4(0.5, 0.5, 0.25, 1.0), 2.0)"),
                rewritten);
        Assertions.assertFalse(rewritten.contains("shadow2D"), rewritten);
        Assertions.assertTrue(rewritten.contains("sampler2DShadow"), rewritten);

        String twice = LegacyGlsl.rewriteTextureLookups(rewritten);
        Assertions.assertEquals(rewritten, twice, "The wrap must be idempotent");
    }

    @Test
    public void testPrecisionStatementsAreStripped() {
        String source = """
                #version 450 core
                precision mediump float;
                precision lowp int;
                // precision mediump float; in a comment must survive
                void main() {
                    vec4 a = vec4(1.0);
                }
                """;

        String stripped = LegacyGlsl.stripPrecisionStatements(source);

        Assertions.assertFalse(stripped.contains("\nprecision mediump float;"),
                "The pack precision default must go: " + stripped);
        Assertions.assertFalse(stripped.contains("\nprecision lowp int;"), stripped);
        Assertions.assertTrue(stripped.contains("// precision mediump float; in a comment must survive"),
                "Comments must never be rewritten: " + stripped);
        Assertions.assertTrue(stripped.contains("vec4 a = vec4(1.0);"), stripped);

        Assertions.assertEquals(stripped, LegacyGlsl.stripPrecisionStatements(stripped),
                "Stripping twice must be a no-op");
        Assertions.assertNull(LegacyGlsl.stripPrecisionStatements(null));
    }

    @Test
    public void testPrecisionDirectivesAreNeverTouched() {
        String source = "#version 450 core\n"
                + "#define SAMPLE precision mediump float\n"
                + "void main() {}\n";

        Assertions.assertTrue(LegacyGlsl.stripPrecisionStatements(source).contains(
                "#define SAMPLE precision mediump float"));
    }

    @Test
    public void testNonConstantConstIsDemoted() {
        String source = """
                #version 450 core
                uniform float frameTime;
                const float PI = 3.14159;
                const vec3 AXIS = vec3(0.0, 1.0, 0.0);
                void main() {
                    const float exposure = frameTime * 2.0;
                    const vec3 tint = vec3(PI) + AXIS;
                    gl_Position = vec4(exposure);
                }
                """;

        String demoted = LegacyGlsl.demoteNonConstantConsts(source);

        Assertions.assertTrue(demoted.contains("const float PI = 3.14159;"),
                "A literal const must stay const: " + demoted);
        Assertions.assertTrue(demoted.contains("const vec3 AXIS = vec3(0.0, 1.0, 0.0);"),
                "A constructor-of-literals const must stay const: " + demoted);
        Assertions.assertTrue(demoted.contains("float exposure = frameTime * 2.0;"),
                "A runtime const must lose its const: " + demoted);
        Assertions.assertFalse(demoted.contains("const float exposure"), demoted);

        Assertions.assertEquals(demoted, LegacyGlsl.demoteNonConstantConsts(demoted),
                "Demoting twice must be a no-op");
        Assertions.assertNull(LegacyGlsl.demoteNonConstantConsts(null));
    }

    @Test
    public void testConstantConstChainIsKept() {
        String source = """
                #version 450 core
                const int N = 4;
                const int M = N + 1;
                void main() {}
                """;

        String demoted = LegacyGlsl.demoteNonConstantConsts(source);

        Assertions.assertTrue(demoted.contains("const int N = 4;"), demoted);
        Assertions.assertTrue(demoted.contains("const int M = N + 1;"),
                "A const built from an earlier constant const must stay const: " + demoted);
    }

    @Test
    public void testConstParamsAndDirectivesAreNeverDemoted() {
        String source = "#version 450 core\n"
                + "#define VALUE const float x = y\n"
                + "// const float z = w;\n"
                + "void apply(const int level) {}\n"
                + "void main() {}\n";

        String demoted = LegacyGlsl.demoteNonConstantConsts(source);

        Assertions.assertTrue(demoted.contains("#define VALUE const float x = y"), demoted);
        Assertions.assertTrue(demoted.contains("// const float z = w;"), demoted);
        Assertions.assertTrue(demoted.contains("void apply(const int level) {}"), demoted);
    }

    @Test
    public void testTranslateLegacyBuiltinsStripsAndDemotesTogether() {
        String source = """
                precision mediump float;
                uniform sampler2DShadow shadowtex0;
                const float PI = 3.14;
                void main() {
                    const float s = shadow2DProjLod(shadowtex0, vec4(0.1), 1.0);
                    gl_FragColor = vec4(s * PI);
                }
                """;

        LegacyGlsl.LegacyResult result = LegacyGlsl.translateLegacyBuiltins(source, false);

        Assertions.assertFalse(result.source().contains("precision mediump float;"), result.source());
        Assertions.assertTrue(result.source().contains("textureProjLod(shadowtex0, vec4(0.1), 1.0)"),
                result.source());
        Assertions.assertTrue(result.source().contains("const float PI = 3.14;"), result.source());
        Assertions.assertTrue(result.source().contains("float s = textureProjLod("), result.source());
        Assertions.assertEquals(Set.of(0), result.fragDataIndices());
    }

    // ------------------------------------------------------------------
    // Plan §4 remainder: union-path split application helpers.
    // ------------------------------------------------------------------

    @Test
    public void testParseStructMembers() {
        String source = """
                #version 450 core
                struct Surface {
                    vec3 albedo;
                    float roughness;
                };
                out Surface surface;
                void main() {}
                """;

        List<LegacyGlsl.StructMember> members =
                LegacyGlsl.parseStructMembers(source, "Surface");

        Assertions.assertEquals(2, members.size());
        Assertions.assertEquals("vec3", members.get(0).type());
        Assertions.assertEquals("albedo", members.get(0).name());
        Assertions.assertEquals("float", members.get(1).type());
        Assertions.assertEquals("roughness", members.get(1).name());
        Assertions.assertTrue(LegacyGlsl.parseStructMembers(source, "Missing").isEmpty());
        Assertions.assertTrue(LegacyGlsl.parseStructMembers(null, "Surface").isEmpty());
    }

    @Test
    public void testSplitLeafAndBaseLocationsStayDense() {
        String vertex = """
                #version 450 core
                struct Surface {
                    vec3 albedo;
                    float roughness;
                };
                out mat4 shadowMatrix;
                out vec4 texCoords[2];
                out Surface surface;
                out vec2 texCoord;
                void main() {}
                """;

        List<LegacyGlsl.VaryingDecl> decls = LegacyGlsl.collectVaryingDecls(vertex, "out");

        Assertions.assertEquals(List.of("shadowMatrix", "texCoords", "surface", "texCoord"),
                decls.stream().map(LegacyGlsl.VaryingDecl::name).toList());

        Map<String, Integer> leaves = LegacyGlsl.assignSplitLeafLocations(decls);
        // mat4 takes 4, vec4[2] takes 2, struct takes 2, vec2 takes 1: dense 0..8.
        Assertions.assertEquals(9, leaves.size());
        Assertions.assertEquals(0, leaves.get("shadowMatrix_c0"));
        Assertions.assertEquals(3, leaves.get("shadowMatrix_c3"));
        Assertions.assertEquals(4, leaves.get("texCoords_0"));
        Assertions.assertEquals(5, leaves.get("texCoords_1"));
        Assertions.assertEquals(6, leaves.get("surface_m0"));
        Assertions.assertEquals(7, leaves.get("surface_m1"));
        Assertions.assertEquals(8, leaves.get("texCoord"));

        Map<String, Integer> bases = LegacyGlsl.assignSplitBaseLocations(decls);
        Assertions.assertEquals(Map.of("shadowMatrix", 0, "texCoords", 4,
                "surface", 6, "texCoord", 8), bases);
    }
}
