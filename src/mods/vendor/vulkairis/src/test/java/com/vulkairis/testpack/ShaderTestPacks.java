package com.vulkairis.testpack;

import java.util.List;

/**
 * The known-good fixtures required by the implementation plan's Phase 0 test harness: a solid-color
 * final pass, a texture copy, a depth read, a shadow sample, a multi-pass ping-pong, plus basic
 * gbuffers, shadow and composite/final chains.
 *
 * <p>These are synthetic Iris-style sources that run through the same translator/compiler the real
 * bridge uses, so a regression anywhere in translation or reflection is localized to a named pack.</p>
 */
public final class ShaderTestPacks {

    private ShaderTestPacks() {
    }

    private static final String FULLSCREEN_VERTEX = """
            #version 150 core
            in vec3 iris_Position;
            in vec2 iris_UV;
            uniform mat4 projection;
            out vec2 vUv;
            void main() {
                vUv = iris_UV;
                gl_Position = projection * vec4(iris_Position, 1.0);
            }
            """;

    private static final String GBUFFERS_VERTEX = """
            #version 150 core
            in vec3 iris_Position;
            in vec4 iris_Color;
            in vec2 iris_UV;
            in vec4 iris_LightCoord;
            uniform mat4 projection;
            uniform mat4 iris_ModelViewMat;
            out vec2 vUv;
            out vec4 vColor;
            out vec4 vLight;
            void main() {
                vUv = iris_UV;
                vColor = iris_Color;
                vLight = iris_LightCoord;
                gl_Position = projection * iris_ModelViewMat * vec4(iris_Position, 1.0);
            }
            """;

    private static final String SHADOW_VERTEX = """
            #version 150 core
            in vec3 iris_Position;
            uniform mat4 projection;
            uniform mat4 iris_ModelViewMat;
            void main() {
                gl_Position = projection * iris_ModelViewMat * vec4(iris_Position, 1.0);
            }
            """;

    private static ShaderStageFixture fullscreenVertex(String name) {
        return ShaderStageFixture.vertex(name, FULLSCREEN_VERTEX, List.of("projection"));
    }

    /** A final pass that outputs a constant color: isolates final execution from visual complexity. */
    public static ShaderTestPack solidColorFinal() {
        return new ShaderTestPack(
                "solid_color_final",
                "A final pass writing a solid color, independent of scene content.",
                List.of(
                        fullscreenVertex("solid_color.vsh"),
                        ShaderStageFixture.fragment(
                                "solid_color.fsh",
                                """
                                #version 150 core
                                out vec4 fragColor;
                                void main() {
                                    fragColor = vec4(1.0, 0.0, 0.0, 1.0);
                                }
                                """,
                                List.of(), List.of())));
    }

    /** Reads the scene color and writes it unchanged. */
    public static ShaderTestPack textureCopy() {
        return new ShaderTestPack(
                "texture_copy",
                "Reads colortex0 and writes it unchanged.",
                List.of(
                        fullscreenVertex("copy.vsh"),
                        ShaderStageFixture.fragment(
                                "copy.fsh",
                                """
                                #version 150 core
                                uniform sampler2D colortex0;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    fragColor = texture(colortex0, vUv);
                                }
                                """,
                                List.of("colortex0"), List.of())));
    }

    /** Reads the depth texture: proves depth is bound as a sampled image. */
    public static ShaderTestPack depthRead() {
        return new ShaderTestPack(
                "depth_read",
                "Reads depthtex0 and outputs it, proving depth is available as a sampled image.",
                List.of(
                        fullscreenVertex("depth.vsh"),
                        ShaderStageFixture.fragment(
                                "depth.fsh",
                                """
                                #version 150 core
                                uniform sampler2D depthtex0;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    float d = texture(depthtex0, vUv).r;
                                    fragColor = vec4(vec3(d), 1.0);
                                }
                                """,
                                List.of("depthtex0"), List.of())));
    }

    /** Samples the shadow map and shadow color: proves shadow resources are exposed. */
    public static ShaderTestPack shadowSample() {
        return new ShaderTestPack(
                "shadow_sample",
                "Samples shadowtex0 and shadowcolor0 in a composite pass.",
                List.of(
                        fullscreenVertex("shadow_sample.vsh"),
                        ShaderStageFixture.fragment(
                                "shadow_sample.fsh",
                                """
                                #version 150 core
                                uniform sampler2D shadowtex0;
                                uniform sampler2D shadowcolor0;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    float s = texture(shadowtex0, vUv).r;
                                    vec4 c = texture(shadowcolor0, vUv);
                                    fragColor = vec4(vec3(s), 1.0) + c;
                                }
                                """,
                                List.of("shadowtex0", "shadowcolor0"), List.of())));
    }

    /** Basic geometry pass with two color outputs (MRT) and the terrain samplers. */
    public static ShaderTestPack basicGbuffers() {
        return new ShaderTestPack(
                "basic_gbuffers",
                "Terrain gbuffers with MRT color outputs and gtexture/lightmap samplers.",
                List.of(
                        ShaderStageFixture.vertex(
                                "gbuffers_terrain.vsh", GBUFFERS_VERTEX,
                                List.of("projection", "iris_ModelViewMat")),
                        ShaderStageFixture.fragment(
                                "gbuffers_terrain.fsh",
                                """
                                #version 150 core
                                uniform sampler2D gtexture;
                                uniform sampler2D lightmap;
                                in vec2 vUv;
                                in vec4 vColor;
                                in vec4 vLight;
                                out vec4 fragColor0;
                                out vec4 fragColor1;
                                void main() {
                                    fragColor0 = texture(gtexture, vUv) * vColor;
                                    fragColor1 = texture(lightmap, vLight.xy);
                                }
                                """,
                                List.of("gtexture", "lightmap"), List.of())));
    }

    /** A depth-only shadow pass. */
    public static ShaderTestPack basicShadow() {
        return new ShaderTestPack(
                "basic_shadow",
                "A depth-only shadow pass.",
                List.of(
                        ShaderStageFixture.vertex(
                                "shadow.vsh", SHADOW_VERTEX,
                                List.of("projection", "iris_ModelViewMat")),
                        ShaderStageFixture.fragment(
                                "shadow.fsh",
                                """
                                #version 150 core
                                void main() {
                                    gl_FragDepth = gl_FragCoord.z;
                                }
                                """,
                                List.of(), List.of())));
    }

    /** A composite then final chain, each consuming the previous pass's output. */
    public static ShaderTestPack compositeFinalChain() {
        return new ShaderTestPack(
                "composite_final_chain",
                "A composite pass feeding a final pass.",
                List.of(
                        fullscreenVertex("composite.vsh"),
                        ShaderStageFixture.fragment(
                                "composite.fsh",
                                """
                                #version 150 core
                                uniform sampler2D colortex0;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    fragColor = texture(colortex0, vUv) * 0.5;
                                }
                                """,
                                List.of("colortex0"), List.of()),
                        ShaderStageFixture.fragment(
                                "final.fsh",
                                """
                                #version 150 core
                                uniform sampler2D colortex1;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    fragColor = texture(colortex1, vUv);
                                }
                                """,
                                List.of("colortex1"), List.of())));
    }

    /** Two composite passes that alternate between two targets (ping-pong). */
    public static ShaderTestPack pingPongMultiPass() {
        return new ShaderTestPack(
                "ping_pong_multi_pass",
                "Two composite passes reading the previous pass's target.",
                List.of(
                        ShaderStageFixture.vertex(
                                "composite0.vsh", FULLSCREEN_VERTEX, List.of("projection")),
                        ShaderStageFixture.fragment(
                                "composite0.fsh",
                                """
                                #version 150 core
                                uniform sampler2D colortex0;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    fragColor = texture(colortex0, vUv);
                                }
                                """,
                                List.of("colortex0"), List.of()),
                        ShaderStageFixture.fragment(
                                "composite1.fsh",
                                """
                                #version 150 core
                                uniform sampler2D colortex1;
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    fragColor = texture(colortex1, vUv) * 0.5;
                                }
                                """,
                                List.of("colortex1"), List.of())));
    }

    /** A translucent-style pass: alpha output plus scalar/vector uniforms. */
    public static ShaderTestPack alphaBlend() {
        return new ShaderTestPack(
                "alpha_blend",
                "Translucent-style fragment with alpha output and scalar/vector uniforms.",
                List.of(
                        ShaderStageFixture.vertex(
                                "blend.vsh", GBUFFERS_VERTEX,
                                List.of("projection", "iris_ModelViewMat")),
                        ShaderStageFixture.fragment(
                                "blend.fsh",
                                """
                                #version 150 core
                                uniform sampler2D gtexture;
                                uniform vec4 tintColor;
                                uniform float alphaScale;
                                in vec2 vUv;
                                in vec4 vColor;
                                in vec4 vLight;
                                out vec4 fragColor;
                                void main() {
                                    vec4 tex = texture(gtexture, vUv);
                                    fragColor = vec4(tex.rgb * tintColor.rgb * vColor.rgb, tintColor.a * alphaScale);
                                }
                                """,
                                List.of("gtexture"), List.of("tintColor", "alphaScale"))));
    }

    /** Reads depth via gl_FragCoord and writes gl_FragDepth: proves depth I/O compiles. */
    public static ShaderTestPack depthTest() {
        return new ShaderTestPack(
                "depth_test",
                "Depth read/write pass using gl_FragCoord and gl_FragDepth.",
                List.of(
                        fullscreenVertex("depth_test.vsh"),
                        ShaderStageFixture.fragment(
                                "depth_test.fsh",
                                """
                                #version 150 core
                                in vec2 vUv;
                                out vec4 fragColor;
                                void main() {
                                    float d = gl_FragCoord.z;
                                    gl_FragDepth = d;
                                    fragColor = vec4(vec3(d), 1.0);
                                }
                                """,
                                List.of(), List.of())));
    }

    /**
     * A vertex/fragment pair whose stage interface disagrees (vec3 output vs vec2 input).
     * Each stage compiles alone; the pair must be rejected by link validation, never bound.
     * Deliberately excluded from {@link #all()}: the harness asserts per-stage success.
     */
    public static ShaderTestPack interfaceMismatch() {
        return new ShaderTestPack(
                "interface_mismatch",
                "Vertex outputs vec3 vColor while the fragment expects vec2 vColor.",
                List.of(
                        ShaderStageFixture.vertex(
                                "mismatch.vsh",
                                """
                                #version 150 core
                                in vec3 iris_Position;
                                out vec3 vColor;
                                void main() {
                                    vColor = vec3(1.0, 0.0, 0.0);
                                    gl_Position = vec4(iris_Position, 1.0);
                                }
                                """,
                                List.of()),
                        ShaderStageFixture.fragment(
                                "mismatch.fsh",
                                """
                                #version 150 core
                                in vec2 vColor;
                                out vec4 fragColor;
                                void main() {
                                    fragColor = vec4(vColor, 0.0, 1.0);
                                }
                                """,
                                List.of(), List.of())));
    }

    /** @return every Phase 0 fixture pack (all must reach REFLECTED) */
    public static List<ShaderTestPack> all() {
        return List.of(
                solidColorFinal(),
                textureCopy(),
                depthRead(),
                shadowSample(),
                basicGbuffers(),
                basicShadow(),
                compositeFinalChain(),
                pingPongMultiPass(),
                alphaBlend(),
                depthTest());
    }
}
