package com.vulkairis.translation;

import com.vulkairis.translation.lexer.GlslLexer;
import com.vulkairis.translation.lexer.GlslToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase B: legacy-GLSL builtin coverage for the program-together translator.
 *
 * <p>OptiFine-format packs target the compatibility profile: {@code texture2D*},
 * {@code shadow2D*}, {@code gl_FragData}/{@code gl_FragColor}, {@code gl_TextureMatrix},
 * {@code ftransform()} and the other fixed-function inputs. Shaderc targeting Vulkan rejects
 * every one of them (see the fix5 dry-run errors), so they are rewritten to core-profile
 * equivalents <em>before</em> any binding/location policy runs.</p>
 *
 * <p>Semantics (reimplemented for this codebase, token-based so comments, string literals and
 * preprocessor directives are never touched):</p>
 * <ul>
 *   <li>Levelled/planar lookups map to their core callee with identical arguments:
 *       {@code texture2D}&#8594;{@code texture}, {@code texture2DLod}&#8594;{@code textureLod},
 *       {@code texture2DProj}&#8594;{@code textureProj}, {@code texture2DProjLod}&#8594;
 *       {@code textureProjLod}, {@code texture2DBias}&#8594;{@code texture},
 *       {@code textureCube}/{@code texture3D} families likewise, {@code texture2DRect}&#8594;
 *       {@code texture}.</li>
 *   <li>Depth-compare lookups are <em>wrapped, not renamed</em>: {@code shadow2D}&#8594;
 *       {@code texture}, {@code shadow2DProj}&#8594;{@code textureProj},
 *       {@code shadow2DLod}&#8594;{@code textureLod}, {@code shadow2DProjLod}&#8594;
 *       {@code textureProjLod}. The sampler stays a
 *       {@code sampler*Shadow} and the argument list is preserved, so the comparison still
 *       happens in the sampler.</li>
 *   <li>Depth/volume handling: 3D (volume) lookups modernize the same way; {@code gl_FragDepth}
 *       and {@code gl_FragCoord} are already core and pass through untouched. This is the
 *       verified forward-Z contract (plan &#167;4.3 &#167;3.6): the VulkanMod driver path is
 *       forward-Z throughout (depth range {@code [0, 1]}, clear {@code 1.0}, compare
 *       {@code LESS_OR_EQUAL}; see {@code TerrainTargets#SHADOW_CLEAR_DEPTH},
 *       {@code TransformLedger#VULKAN_CLEAR_DEPTH} and the {@code RenderLifecycleTable}
 *       {@code "forward-Z (clear 1.0)"} entries), and the {@code [-1, 1]}&#8594;{@code [0, 1]}
 *       range correction is applied exactly once on matrix upload
 *       ({@code UniformBridge#updateMatrices} via {@code RenderMatrices#CLIP_SPACE_CORRECTION}).
 *       Translation therefore performs <em>no</em> reversed-Z rewrite: no depth inversion,
 *       no {@code gl_DepthRange} synthesis, and shadow clear semantics are never altered here.
 *       A reversed-Z pipeline (clear {@code 0.0}, {@code GREATER}) does not exist on this
 *       path, so there is nothing for the shader source to match.</li>
 *   <li>{@code gl_FragColor} becomes indexed output {@code 0}; each literal
 *       {@code gl_FragData[n]} becomes {@code vulkairisFragDataN} and its index is recorded.
 *       Declarations are <em>not</em> emitted here; {@link ProgramTranslator} declares the
 *       indexed outputs with fragment-output locations.</li>
 *   <li>Fixed-function state maps to explicit {@code vulkairis_}-prefixed declarations:
 *       {@code gl_TextureMatrix}&#8594;{@code vulkairis_TextureMatrix} (mat4 array uniform),
 *       {@code gl_ModelViewProjectionMatrix}/{@code gl_ModelViewMatrix}/
 *       {@code gl_ProjectionMatrix}/{@code gl_NormalMatrix} to same-named uniforms,
 *       {@code gl_Vertex}/{@code gl_Normal}/{@code gl_Color}/{@code gl_MultiTexCoord0..1} to
 *       same-named vertex attributes, and {@code ftransform()} to
 *       {@code (vulkairis_ModelViewProjectionMatrix * vulkairis_Vertex)}.</li>
 *   <li>Varying-split helpers scalarize matrix/array varyings and account location slots
 *       (one slot per column/element), so the union-varying pass can verify that a split
 *       interface never collides with itself.</li>
 *   <li>Pack {@code precision <qualifier> <type>;} statements are stripped (the downstream
 *       header emits the canonical {@code highp} lines); precision qualifiers on
 *       declarations are preserved.</li>
 *   <li>{@code const} declarations with non-constant initializers are demoted to plain
 *       declarations (translation only); constant {@code const} declarations, function
 *       parameters and preprocessor directives are left alone.</li>
 * </ul>
 *
 * <p>All rewrites are pure, deterministic and idempotent: mapped names never match the
 * mapping table again. Anything outside the table is deliberately left for the compiler
 * to report rather than guessed at. That set includes {@code gl_Fog} beyond the injected
 * {@code VulkairisFogParams} struct (values are Phase E work), {@code gl_LightSource},
 * {@code gl_DepthRange}, and the inverse matrices ({@code gl_ModelViewMatrixInverse},
 * {@code gl_ProjectionMatrixInverse} and {@code gl_ModelViewProjectionMatrixInverse}). The inverses are common in BSL-family packs, so
 * BSL-derived fixtures are expected to fail translation with a compiler error naming the
 * undeclared identifier until a future phase supplies them from the already-tracked CPU
 * inverses ({@code RenderMatrices#getInverseView}/{@code getInverseProjection}) as
 * uniform-block members with {@code UniformBridge} transport; inventing values here would
 * silently corrupt depth and lighting instead.</p>
 */
public final class LegacyGlsl {

    private LegacyGlsl() {
    }

    /** Prefix for every identifier this pass introduces. */
    public static final String INTRODUCED_PREFIX = "vulkairis";

    /** Indexed fragment-output base name: {@code gl_FragData[n]} becomes this plus {@code n}. */
    public static final String FRAG_DATA_BASE = "vulkairisFragData";

    /** How many texture units the compatibility {@code gl_TextureMatrix} array exposes. */
    public static final int TEXTURE_MATRIX_UNITS = 8;

    /**
     * Aggregate result of {@link #translateLegacyBuiltins(String, boolean)}.
     *
     * @param source             the rewritten source
     * @param fragDataIndices    sorted literal {@code gl_FragData[n]} indices seen (plus
     *                           {@code 0} when {@code gl_FragColor} was rewritten)
     * @param needsTextureMatrix whether {@code vulkairis_TextureMatrix} is referenced
     * @param needsModelViewProjection whether {@code vulkairis_ModelViewProjectionMatrix} is referenced
     * @param needsModelView     whether {@code vulkairis_ModelViewMatrix} is referenced
     * @param needsProjection    whether {@code vulkairis_ProjectionMatrix} is referenced
     * @param needsNormalMatrix  whether {@code vulkairis_NormalMatrix} is referenced
     * @param needsVertexAttrib  whether {@code vulkairis_Vertex} is referenced
     * @param needsNormalAttrib  whether {@code vulkairis_Normal} is referenced
     * @param needsColorAttrib   whether {@code vulkairis_Color} is referenced
     * @param needsTexCoord0     whether {@code vulkairis_MultiTexCoord0} is referenced
     * @param needsTexCoord1     whether {@code vulkairis_MultiTexCoord1} is referenced
     */
    public record LegacyResult(
            String source,
            Set<Integer> fragDataIndices,
            boolean needsTextureMatrix,
            boolean needsModelViewProjection,
            boolean needsModelView,
            boolean needsProjection,
            boolean needsNormalMatrix,
            boolean needsVertexAttrib,
            boolean needsNormalAttrib,
            boolean needsColorAttrib,
            boolean needsTexCoord0,
            boolean needsTexCoord1,
            boolean needsFog) {
    }

    /**
     * Runs every legacy mapping over one stage source.
     *
     * <p>Order: precision statements are stripped first (pack {@code precision mediump ...;}
     * lines go; the canonical {@code highp} lines are re-added downstream by
     * {@code ShaderTranslator}/{@code ProgramTranslator}), then texture/shadow/frag-data/
     * fixed-function mappings run, then non-constant {@code const} declarations are demoted
     * to plain declarations. All three passes are token-based, so comments, string literals
     * and preprocessor directives are never touched, and all are idempotent.</p>
     *
     * @param source   GLSL source; may be {@code null}
     * @param isVertex stage selector (currently informational; mappings are stage-agnostic)
     * @return the rewritten source plus the set of introduced requirements
     */
    public static LegacyResult translateLegacyBuiltins(String source, boolean isVertex) {
        if (source == null) {
            return new LegacyResult(null, Set.of(),
                    false, false, false, false, false, false, false, false, false, false, false);
        }
        List<GlslToken> tokens;
        try {
            tokens = GlslLexer.tokenize(source);
        } catch (RuntimeException lexFailure) {
            return new LegacyResult(source, Set.of(),
                    false, false, false, false, false, false, false, false, false, false, false);
        }

        List<GlslToken> stripped = stripPrecisionTokens(tokens);
        Set<Integer> fragData = new TreeSet<>();
        boolean[] flags = new boolean[11];
        List<GlslToken> rewritten = rewriteTokens(stripped, fragData, flags);
        List<GlslToken> demoted = demoteConstTokens(rewritten);

        return new LegacyResult(GlslLexer.emit(demoted), Set.copyOf(fragData),
                flags[0], flags[1], flags[2], flags[3], flags[4],
                flags[5], flags[6], flags[7], flags[8], flags[9], flags[10]);
    }

    /**
     * Rewrites only the texture/shadow/volume lookup callees.
     *
     * @param source GLSL source; may be {@code null}
     * @return the rewritten source, or the input when {@code null}
     */
    public static String rewriteTextureLookups(String source) {
        return translateLegacyBuiltins(source, false).source();
    }

    /**
     * Collects the literal {@code gl_FragData[n]} indices used by {@code source}, in order.
     *
     * @param source GLSL source; may be {@code null}
     * @return sorted indices; empty for {@code null} input or when none are used
     */
    public static Set<Integer> collectFragDataIndices(String source) {
        if (source == null) {
            return Set.of();
        }
        List<GlslToken> tokens;
        try {
            tokens = GlslLexer.tokenize(source);
        } catch (RuntimeException lexFailure) {
            return Set.of();
        }
        Set<Integer> indices = new TreeSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            GlslToken token = tokens.get(i);
            if (!token.isCodeIdentifier("gl_FragData")) {
                continue;
            }
            Integer index = bracketedLiteral(tokens, i);
            if (index != null) {
                indices.add(index);
            }
        }
        return Set.copyOf(indices);
    }

    /**
     * Declares one indexed fragment output per index, for the fragment stage only.
     *
     * @param indices literal indices (from {@link #collectFragDataIndices} or a
     *                {@link LegacyResult}); may be {@code null}/empty
     * @return {@code layout(location = n) out vec4 vulkairisFragDataN;} lines, or {@code ""}
     */
    public static String fragOutputDeclarations(Set<Integer> indices) {
        if (indices == null || indices.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int index : new TreeSet<>(indices)) {
            out.append("layout(location = ").append(index).append(") out vec4 ")
                    .append(FRAG_DATA_BASE).append(index).append(";\n");
        }
        return out.toString();
    }

    /**
     * Whether {@code name} is a depth builtin that translation passes through untouched
     * under the forward-Z contract ({@code gl_FragDepth}, {@code gl_FragCoord}).
     *
     * <p>Both are already core-profile builtins, so no rewrite is needed; the
     * {@code [-1, 1]}&#8594;{@code [0, 1]} range correction lives on matrix upload
     * ({@code UniformBridge}), never in the shader source. Anything depth-adjacent outside
     * this pair (for example {@code gl_DepthRange} or the inverse matrices) is
     * deliberately <em>not</em> matched and stays compiler-reported.</p>
     *
     * @param name identifier to classify; may be {@code null}
     * @return {@code true} only for the two pass-through depth builtins
     */
    public static boolean isDepthPassthroughBuiltin(String name) {
        return "gl_FragDepth".equals(name) || "gl_FragCoord".equals(name);
    }

    /**
     * Removes every {@code precision <qualifier> <type>;} statement (for example
     * {@code precision mediump float;}).
     *
     * <p>Pack precision defaults are ES legacy: under {@code #version 450 core} the
     * downstream header emits the two canonical {@code precision highp float;} /
     * {@code precision highp int;} lines instead, so incoming statements are redundant
     * at best and conflicting at worst. Precision <em>qualifiers</em> on declarations
     * (for example {@code mediump vec4 x;} or {@code uniform highp sampler2D t;}) are
     * preserved: desktop GLSL tolerates them and the binding/location passes already
     * recognise them.</p>
     *
     * <p>Token-based, so comments, string literals and preprocessor directives (including
     * macro bodies mentioning {@code precision}) are never touched. Null-safe and
     * idempotent: a source without precision statements is returned unchanged.</p>
     *
     * @param source GLSL source; may be {@code null}
     * @return the source without precision statements, or the input when {@code null}
     */
    public static String stripPrecisionStatements(String source) {
        if (source == null) {
            return null;
        }
        List<GlslToken> tokens;
        try {
            tokens = GlslLexer.tokenize(source);
        } catch (RuntimeException lexFailure) {
            return source;
        }
        return GlslLexer.emit(stripPrecisionTokens(tokens));
    }

    /**
     * Demotes {@code const} declarations with non-constant initializers to plain
     * declarations (translation only).
     *
     * <p>GLSL requires a {@code const} initializer to be a compile-time constant. Packs
     * sometimes write {@code const float exposure = frameTime * 2.0;} (or any other
     * runtime value), which Shaderc rejects. When the initializer references anything
     * beyond numeric literals, {@code true}/{@code false}, GLSL type constructors
     * ({@code vec3}, {@code mat4}, ...), {@code ALL_CAPS} preprocessor macros and
     * previously-seen constant {@code const} names, the leading {@code const} is
     * removed and the declaration becomes a regular (possibly global) variable with
     * the same type, name and initializer. A {@code const} without any initializer
     * ({@code const float x;}) is likewise demoted, since it is always illegal.</p>
     *
     * <p>Kept declarations (all initializers constant, or rather still {@code const})
     * contribute their names to the constant pool, so {@code const int M = N + 1;}
     * after {@code const int N = 4;} stays {@code const}. {@code const} on function
     * parameters and prototypes ({@code void f(const int x);} — no {@code ;}-terminated
     * declaration) is never touched, since read-only parameters are always legal.</p>
     *
     * <p>Token-based, so comments, string literals and preprocessor directives are never
     * touched. Null-safe and idempotent. Global non-constant initializers that need a
     * runtime value (for example {@code float x = texture(...).r;} at global scope)
     * stay compiler-reported: demotion only removes the illegal {@code const}, it does
     * not hoist initialization into {@code main()} (documented follow-up).</p>
     *
     * @param source GLSL source; may be {@code null}
     * @return the source with non-constant {@code const} declarations demoted,
     *         or the input when {@code null}
     */
    public static String demoteNonConstantConsts(String source) {
        if (source == null) {
            return null;
        }
        List<GlslToken> tokens;
        try {
            tokens = GlslLexer.tokenize(source);
        } catch (RuntimeException lexFailure) {
            return source;
        }
        return GlslLexer.emit(demoteConstTokens(tokens));
    }

    // ------------------------------------------------------------------
    // Token rewrite engine.
    // ------------------------------------------------------------------

    /** Flag slots into {@link #translateLegacyBuiltins} bookkeeping, in declaration order. */
    private static final int FLAG_TEXTURE_MATRIX = 0;
    private static final int FLAG_MVP = 1;
    private static final int FLAG_MODELVIEW = 2;
    private static final int FLAG_PROJECTION = 3;
    private static final int FLAG_NORMAL_MATRIX = 4;
    private static final int FLAG_VERTEX = 5;
    private static final int FLAG_NORMAL = 6;
    private static final int FLAG_COLOR = 7;
    private static final int FLAG_TEXCOORD0 = 8;
    private static final int FLAG_TEXCOORD1 = 9;
    private static final int FLAG_FOG = 10;

    private static List<GlslToken> rewriteTokens(
            List<GlslToken> tokens, Set<Integer> fragData, boolean[] flags) {
        List<GlslToken> out = new ArrayList<>(tokens.size());
        Set<Integer> skipIndices = new java.util.HashSet<>();
        int i = 0;
        while (i < tokens.size()) {
            if (skipIndices.contains(i)) {
                i++;
                continue;
            }
            GlslToken token = tokens.get(i);

            if (token.kind() == GlslToken.Kind.IDENTIFIER && token.isCode()) {
                // gl_VertexID -> gl_VertexIndex (standard Vulkan SPIR-V builtin)
                if (token.text().equals("gl_VertexID")) {
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            "gl_VertexIndex", token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }
                // Iris / OptiFine / Sodium aliases
                if (token.text().equals("projectionMatrix")) {
                    flags[FLAG_PROJECTION] = true;
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            "vulkairis_ProjectionMatrix", token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }
                if (token.text().equals("modelViewMatrix")) {
                    flags[FLAG_MODELVIEW] = true;
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            "vulkairis_ModelViewMatrix", token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }
                if (token.text().equals("vaPosition")) {
                    flags[FLAG_VERTEX] = true;
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            "vulkairis_Vertex.xyz", token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }
                if (token.text().equals("vaNormal")) {
                    flags[FLAG_NORMAL] = true;
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            "vulkairis_Normal", token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }

                // Indexed fragment outputs: gl_FragData[<literal>] -> vulkairisFragData<literal>.
                if (token.text().equals("gl_FragData")) {
                    Integer index = bracketedLiteral(tokens, i);
                    if (index != null) {
                        int close = bracketClose(tokens, i);
                        out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                                FRAG_DATA_BASE + index, token.line(), token.column(), token.offset()));
                        fragData.add(index);
                        i = close + 1;
                        continue;
                    }
                    out.add(token);
                    i++;
                    continue;
                }
                if (token.text().equals("gl_FragColor")) {
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            FRAG_DATA_BASE + "0", token.line(), token.column(), token.offset()));
                    fragData.add(0);
                    i++;
                    continue;
                }
                // ftransform() takes no arguments; the empty call pair is consumed with it.
                if (token.text().equals("ftransform")) {
                    int close = emptyCallClose(tokens, i);
                    if (close >= 0) {
                        flags[FLAG_MVP] = true;
                        flags[FLAG_VERTEX] = true;
                        out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                                "(vulkairis_ModelViewProjectionMatrix * vulkairis_Vertex)",
                                token.line(), token.column(), token.offset()));
                        i = close + 1;
                        continue;
                    }
                    out.add(token);
                    i++;
                    continue;
                }
                if (isShadowLookup(token.text())) {
                    String mapped = mappedCallee(token.text());
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            mapped, token.line(), token.column(), token.offset()));
                    // Check for trailing swizzle after matching ')'
                    int open = skipTrivia(tokens, i + 1);
                    if (isSymbol(tokens, open, "(")) {
                        int close = matchingParenClose(tokens, open);
                        if (close >= 0) {
                            int dot = skipTrivia(tokens, close + 1);
                            if (isSymbol(tokens, dot, ".")) {
                                int swiz = skipTrivia(tokens, dot + 1);
                                if (swiz < tokens.size() && tokens.get(swiz).kind() == GlslToken.Kind.IDENTIFIER) {
                                    String sw = tokens.get(swiz).text();
                                    if (sw.equals("x") || sw.equals("y") || sw.equals("z") || sw.equals("w")
                                            || sw.equals("r") || sw.equals("g") || sw.equals("b") || sw.equals("a")
                                            || sw.equals("st")) {
                                        for (int k = close + 1; k <= swiz; k++) {
                                            skipIndices.add(k);
                                        }
                                    }
                                }
                            }
                        }
                    }
                    i++;
                    continue;
                }
                String mapped = mappedCallee(token.text());
                if (mapped != null) {
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            mapped, token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }
                int flag = fixedFunctionFlag(token.text());
                if (flag >= 0) {
                    flags[flag] = true;
                    out.add(new GlslToken(GlslToken.Kind.IDENTIFIER,
                            "vulkairis_" + token.text().substring(3),
                            token.line(), token.column(), token.offset()));
                    i++;
                    continue;
                }
            }
            out.add(token);
            i++;
        }
        return out;
    }

    private static boolean isShadowLookup(String name) {
        return "shadow2D".equals(name) || "shadow2DLod".equals(name)
                || "shadow2DProj".equals(name) || "shadow2DProjLod".equals(name);
    }

    private static int matchingParenClose(List<GlslToken> tokens, int openParenIndex) {
        int depth = 0;
        for (int j = openParenIndex; j < tokens.size(); j++) {
            GlslToken t = tokens.get(j);
            if (t.isCode() && isSymbol(tokens, j, "(")) {
                depth++;
            } else if (t.isCode() && isSymbol(tokens, j, ")")) {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    /**
     * Maps one legacy lookup callee to its core equivalent, or {@code null} to leave it alone.
     * Introduced names never match this table, which keeps the pass idempotent.
     */
    private static String mappedCallee(String name) {
        return switch (name) {
            case "texture2D" -> "texture";
            case "texture2DProj" -> "textureProj";
            case "texture2DProjLod" -> "textureProjLod";
            case "texture2DLod" -> "textureLod";
            case "texture2DBias" -> "texture";
            case "texture2DRect" -> "texture";
            case "textureCube" -> "texture";
            case "textureCubeLod" -> "textureLod";
            case "textureCubeBias" -> "texture";
            case "texture3D" -> "texture";
            case "texture3DLod" -> "textureLod";
            case "texture3DBias" -> "texture";
            case "texture3DProj" -> "textureProj";
            case "shadow2D" -> "texture";
            case "shadow2DLod" -> "textureLod";
            case "shadow2DProj" -> "textureProj";
            case "shadow2DProjLod" -> "textureProjLod";
            default -> null;
        };
    }

    /** Maps a fixed-function {@code gl_} identifier to its requirement-flag slot, or -1. */
    private static int fixedFunctionFlag(String name) {
        return switch (name) {
            case "gl_TextureMatrix" -> FLAG_TEXTURE_MATRIX;
            case "gl_ModelViewProjectionMatrix" -> FLAG_MVP;
            case "gl_ModelViewMatrix" -> FLAG_MODELVIEW;
            case "gl_ProjectionMatrix" -> FLAG_PROJECTION;
            case "gl_NormalMatrix" -> FLAG_NORMAL_MATRIX;
            case "gl_Vertex" -> FLAG_VERTEX;
            case "gl_Normal" -> FLAG_NORMAL;
            case "gl_Color" -> FLAG_COLOR;
            case "gl_MultiTexCoord0" -> FLAG_TEXCOORD0;
            case "gl_MultiTexCoord1" -> FLAG_TEXCOORD1;
            case "gl_Fog" -> FLAG_FOG;
            default -> -1;
        };
    }

    /**
     * Removes {@code precision <qualifier> <type>;} statements from a token list.
     * See {@link #stripPrecisionStatements(String)} for semantics; this is the
     * token-level worker used by {@link #translateLegacyBuiltins}.
     */
    private static List<GlslToken> stripPrecisionTokens(List<GlslToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return tokens != null ? tokens : List.of();
        }
        List<GlslToken> out = new ArrayList<>(tokens.size());
        int i = 0;
        while (i < tokens.size()) {
            GlslToken token = tokens.get(i);
            if (token.isCodeIdentifier("precision")) {
                int qual = skipTrivia(tokens, i + 1);
                int type = qual < tokens.size() ? skipTrivia(tokens, qual + 1) : tokens.size();
                int semi = type < tokens.size() ? skipTrivia(tokens, type + 1) : tokens.size();
                if (qual < tokens.size() && type < tokens.size() && semi < tokens.size()
                        && tokens.get(qual).kind() == GlslToken.Kind.IDENTIFIER
                        && tokens.get(type).kind() == GlslToken.Kind.IDENTIFIER
                        && isSymbol(tokens, semi, ";")
                        && isPrecisionQualifier(tokens.get(qual).text())) {
                    int next = semi + 1;
                    while (next < tokens.size()
                            && tokens.get(next).kind() == GlslToken.Kind.WHITESPACE) {
                        next++;
                    }
                    if (next < tokens.size()
                            && tokens.get(next).kind() == GlslToken.Kind.NEWLINE) {
                        next++;
                    }
                    i = next;
                    continue;
                }
            }
            out.add(token);
            i++;
        }
        return out;
    }

    private static boolean isPrecisionQualifier(String name) {
        return "highp".equals(name) || "mediump".equals(name) || "lowp".equals(name);
    }

    /**
     * Type constructors and literals allowed in a {@code const} initializer without
     * demoting it. Anything else (variables, uniforms, attributes, function calls,
     * {@code vulkairis_*}/{@code gl_*} state) marks the initializer non-constant.
     * {@code ALL_CAPS} names are additionally allowed as likely preprocessor macros;
     * previously-seen constant {@code const} names are tracked separately in
     * {@link #demoteConstTokens}.
     */
    private static final Set<String> CONST_INIT_ALLOWED_TYPES = Set.of(
            "float", "double", "int", "uint", "bool",
            "vec2", "vec3", "vec4",
            "dvec2", "dvec3", "dvec4",
            "ivec2", "ivec3", "ivec4",
            "uvec2", "uvec3", "uvec4",
            "bvec2", "bvec3", "bvec4",
            "mat2", "mat3", "mat4",
            "mat2x2", "mat2x3", "mat2x4",
            "mat3x2", "mat3x3", "mat3x4",
            "mat4x2", "mat4x3", "mat4x4",
            "dmat2", "dmat3", "dmat4",
            "dmat2x2", "dmat2x3", "dmat2x4",
            "dmat3x2", "dmat3x3", "dmat3x4",
            "dmat4x2", "dmat4x3", "dmat4x4",
            "true", "false");

    private static boolean isAllCapsMacro(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        return name.charAt(0) >= 'A' && name.charAt(0) <= 'Z';
    }

    /**
     * Demotes non-constant {@code const} declarations in a token list.
     * See {@link #demoteNonConstantConsts(String)} for semantics; this is the
     * token-level worker used by {@link #translateLegacyBuiltins}.
     */
    private static List<GlslToken> demoteConstTokens(List<GlslToken> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return tokens != null ? tokens : List.of();
        }
        List<GlslToken> out = new ArrayList<>(tokens.size());
        Set<String> constantConsts = new java.util.HashSet<>();
        int i = 0;
        while (i < tokens.size()) {
            GlslToken token = tokens.get(i);
            if (!token.isCodeIdentifier("const")) {
                out.add(token);
                i++;
                continue;
            }
            Integer semi = statementSemicolon(tokens, i);
            if (semi == null) {
                out.add(token);
                i++;
                continue;
            }
            if (shouldDemoteConst(tokens, i, semi, constantConsts)) {
                i = skipConstKeyword(tokens, i);
                continue;
            }
            harvestConstantConstNames(tokens, i, semi, constantConsts);
            out.add(token);
            i++;
        }
        return out;
    }

    /**
     * Index of the {@code ;} ending the {@code ;}-terminated declaration starting at
     * {@code constIndex}, or {@code null} when the {@code const} is not a
     * {@code ;}-terminated declaration (function parameters/prototypes end with
     * {@code ,}/{@code )}, which must stay {@code const}).
     */
    private static Integer statementSemicolon(List<GlslToken> tokens, int constIndex) {
        int parenDepth = 0;
        int bracketDepth = 0;
        for (int j = constIndex + 1; j < tokens.size(); j++) {
            GlslToken t = tokens.get(j);
            if (t.kind() != GlslToken.Kind.SYMBOL || !t.isCode()) {
                continue;
            }
            switch (t.text()) {
                case "(" -> parenDepth++;
                case "[" -> bracketDepth++;
                case ")" -> {
                    if (parenDepth == 0) {
                        return null;
                    }
                    parenDepth--;
                }
                case "]" -> {
                    if (bracketDepth == 0) {
                        return null;
                    }
                    bracketDepth--;
                }
                case ";" -> {
                    if (parenDepth == 0 && bracketDepth == 0) {
                        return j;
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }

    /**
     * Whether the {@code const} declaration from {@code constIndex} to {@code semi}
     * must be demoted: no initializer (always illegal for {@code const}), or any
     * declarator whose initializer references a non-constant identifier.
     */
    private static boolean shouldDemoteConst(
            List<GlslToken> tokens, int constIndex, int semi, Set<String> constantConsts) {
        int parenDepth = 0;
        int bracketDepth = 0;
        boolean hasEquals = false;
        for (int j = constIndex + 1; j < semi; j++) {
            GlslToken t = tokens.get(j);
            if (t.kind() == GlslToken.Kind.SYMBOL && t.isCode()) {
                switch (t.text()) {
                    case "(" -> parenDepth++;
                    case "[" -> bracketDepth++;
                    case ")" -> parenDepth = Math.max(0, parenDepth - 1);
                    case "]" -> bracketDepth = Math.max(0, bracketDepth - 1);
                    case "=" -> {
                        if (parenDepth == 0 && bracketDepth == 0 && !isComparison(tokens, j)) {
                            hasEquals = true;
                        }
                    }
                    default -> {
                    }
                }
            }
        }
        if (!hasEquals) {
            return true;
        }
        List<int[]> inits = declaratorInits(tokens, constIndex, semi);
        if (inits.isEmpty()) {
            return true;
        }
        for (int[] range : inits) {
            for (int j = range[0]; j < range[1]; j++) {
                GlslToken t = tokens.get(j);
                if (t.kind() == GlslToken.Kind.IDENTIFIER && t.isCode()) {
                    String name = t.text();
                    if (CONST_INIT_ALLOWED_TYPES.contains(name)
                            || constantConsts.contains(name)
                            || isAllCapsMacro(name)) {
                        continue;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isComparison(List<GlslToken> tokens, int equalsIndex) {
        GlslToken t = tokens.get(equalsIndex);
        if (!"=".equals(t.text())) {
            return false;
        }
        if (equalsIndex + 1 < tokens.size()) {
            GlslToken next = tokens.get(equalsIndex + 1);
            if (next.kind() == GlslToken.Kind.SYMBOL && "=".equals(next.text())) {
                return true;
            }
        }
        if (equalsIndex - 1 >= 0) {
            GlslToken prev = tokens.get(equalsIndex - 1);
            if (prev.kind() == GlslToken.Kind.SYMBOL
                    && ("=".equals(prev.text()) || "!".equals(prev.text())
                            || "<".equals(prev.text()) || ">".equals(prev.text()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Init ranges (start inclusive, end exclusive) for every {@code = <init>} declarator
     * in the {@code const} statement, split on top-level commas.
     */
    private static List<int[]> declaratorInits(List<GlslToken> tokens, int constIndex, int semi) {
        List<int[]> inits = new ArrayList<>();
        int parenDepth = 0;
        int bracketDepth = 0;
        int equalsAt = -1;
        int segmentStart = constIndex + 1;
        for (int j = constIndex + 1; j <= semi; j++) {
            boolean boundary = j == semi;
            String sym = null;
            if (!boundary) {
                GlslToken t = tokens.get(j);
                if (t.kind() == GlslToken.Kind.SYMBOL && t.isCode()) {
                    sym = t.text();
                }
            }
            if (sym != null) {
                switch (sym) {
                    case "(" -> parenDepth++;
                    case "[" -> bracketDepth++;
                    case ")" -> parenDepth = Math.max(0, parenDepth - 1);
                    case "]" -> bracketDepth = Math.max(0, bracketDepth - 1);
                    default -> {
                    }
                }
            }
            boolean comma = ",".equals(sym) && parenDepth == 0 && bracketDepth == 0;
            if (boundary || comma) {
                if (equalsAt >= 0) {
                    inits.add(new int[]{equalsAt + 1, j});
                }
                equalsAt = -1;
                segmentStart = j + 1;
                continue;
            }
            if ("=".equals(sym) && parenDepth == 0 && bracketDepth == 0 && !isComparison(tokens, j)) {
                if (equalsAt < 0) {
                    equalsAt = j;
                }
            }
        }
        return inits;
    }

    private static void harvestConstantConstNames(
            List<GlslToken> tokens, int constIndex, int semi, Set<String> constantConsts) {
        int parenDepth = 0;
        int bracketDepth = 0;
        int segmentStart = constIndex + 1;
        for (int j = constIndex + 1; j <= semi; j++) {
            boolean boundary = j == semi;
            String sym = null;
            if (!boundary) {
                GlslToken t = tokens.get(j);
                if (t.kind() == GlslToken.Kind.SYMBOL && t.isCode()) {
                    sym = t.text();
                }
            }
            if (sym != null) {
                switch (sym) {
                    case "(" -> parenDepth++;
                    case "[" -> bracketDepth++;
                    case ")" -> parenDepth = Math.max(0, parenDepth - 1);
                    case "]" -> bracketDepth = Math.max(0, bracketDepth - 1);
                    default -> {
                    }
                }
            }
            if (boundary || (",".equals(sym) && parenDepth == 0 && bracketDepth == 0)) {
                String name = declaratorNameIn(tokens, segmentStart, j);
                if (name != null && !name.isEmpty()) {
                    constantConsts.add(name);
                }
                segmentStart = j + 1;
            }
        }
    }

    private static String declaratorNameIn(List<GlslToken> tokens, int from, int to) {
        for (int j = from; j < to; j++) {
            GlslToken t = tokens.get(j);
            if (t.kind() == GlslToken.Kind.IDENTIFIER && t.isCode()) {
                String candidate = t.text();
                if (CONST_INIT_ALLOWED_TYPES.contains(candidate) || "const".equals(candidate)) {
                    continue;
                }
                return candidate;
            }
            if (t.kind() == GlslToken.Kind.SYMBOL && t.isCode()
                    && ("=".equals(t.text()) || ",".equals(t.text()) || ";".equals(t.text()))) {
                break;
            }
        }
        return null;
    }

    /**
     * Index after the {@code const} keyword and its immediately following horizontal
     * whitespace (newlines and comments are preserved, so line structure survives).
     */
    private static int skipConstKeyword(List<GlslToken> tokens, int constIndex) {
        int j = constIndex + 1;
        while (j < tokens.size()
                && tokens.get(j).kind() == GlslToken.Kind.WHITESPACE) {
            j++;
        }
        return j;
    }

    /** Skips trivia (whitespace/comments/newlines) forward from {@code from}. */
    private static int skipTrivia(List<GlslToken> tokens, int from) {
        int i = from;
        while (i < tokens.size() && tokens.get(i).isTrivia()) {
            i++;
        }
        return i;
    }

    /** Whether the token is the exact single-character symbol {@code text}. */
    private static boolean isSymbol(List<GlslToken> tokens, int index, String text) {
        return index < tokens.size()
                && tokens.get(index).kind() == GlslToken.Kind.SYMBOL
                && tokens.get(index).text().equals(text);
    }

    /**
     * Reads {@code gl_FragData[<integer literal>]} at {@code identifierIndex}.
     *
     * @return the literal index, or {@code null} when the subscript is absent, non-literal
     *         or unbalanced (the identifier is then left untouched)
     */
    private static Integer bracketedLiteral(List<GlslToken> tokens, int identifierIndex) {
        int open = skipTrivia(tokens, identifierIndex + 1);
        if (!isSymbol(tokens, open, "[")) {
            return null;
        }
        int number = skipTrivia(tokens, open + 1);
        if (number >= tokens.size() || tokens.get(number).kind() != GlslToken.Kind.NUMBER) {
            return null;
        }
        int close = skipTrivia(tokens, number + 1);
        if (!isSymbol(tokens, close, "]")) {
            return null;
        }
        try {
            int value = Integer.parseInt(tokens.get(number).text().trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException notLiteral) {
            return null;
        }
    }

    /** Index of the {@code ]} closing {@code gl_FragData[...]} at {@code identifierIndex}, or -1. */
    private static int bracketClose(List<GlslToken> tokens, int identifierIndex) {
        int open = skipTrivia(tokens, identifierIndex + 1);
        int number = skipTrivia(tokens, open + 1);
        return skipTrivia(tokens, number + 1);
    }

    /**
     * Index of the {@code )} closing an empty {@code ftransform()} call at
     * {@code identifierIndex}, or -1 when arguments or unbalanced parentheses follow.
     */
    private static int emptyCallClose(List<GlslToken> tokens, int identifierIndex) {
        int open = skipTrivia(tokens, identifierIndex + 1);
        if (!isSymbol(tokens, open, "(")) {
            return -1;
        }
        int close = skipTrivia(tokens, open + 1);
        return isSymbol(tokens, close, ")") ? close : -1;
    }

    // ------------------------------------------------------------------
    // Varying-split helpers.
    // ------------------------------------------------------------------

    /**
     * One scalarized leaf of a split varying declaration.
     *
     * @param name  leaf identifier (for example {@code shadowMatrix_c2} or {@code texCoords_1})
     * @param type  leaf GLSL type (always a scalar or vector, never a matrix or array)
     * @param slots location slots this leaf consumes (always 1)
     */
    public record SplitLeaf(String name, String type, int slots) {
    }

    /**
     * Counts the SPIR-V location slots consumed by one varying declarator.
     *
     * <p>Vectors/scalars consume 1 slot, matrices consume 1 slot per column, and arrayed
     * declarations multiply by the element count. This is the rank==location accounting the
     * union-varying pass uses to keep split leaves collision-free.</p>
     *
     * @param type        GLSL type name (for example {@code vec3}, {@code mat4})
     * @param arrayLength array element count, or {@code 1} when not an array
     * @return slots consumed, at least 1
     */
    public static int locationSlotsFor(String type, int arrayLength) {
        int columns = switch (type) {
            case "mat2", "dmat2", "mat2x2", "dmat2x2" -> 2;
            case "mat3", "dmat3", "mat3x3", "dmat3x3" -> 3;
            case "mat4", "dmat4", "mat4x4", "dmat4x4" -> 4;
            case "mat2x3", "mat2x4", "dmat2x3", "dmat2x4" -> 2;
            case "mat3x2", "mat3x4", "dmat3x2", "dmat3x4" -> 3;
            case "mat4x2", "mat4x3", "dmat4x2", "dmat4x3" -> 4;
            default -> 1;
        };
        return Math.max(1, columns) * Math.max(1, arrayLength);
    }

    /**
     * Scalarizes one varying declarator into collision-free leaves.
     *
     * <ul>
     *   <li>Matrices become one {@code vec<rows>} leaf per column
     *       ({@code shadowMatrix} &#8594; {@code shadowMatrix_c0..c3}).</li>
     *   <li>Arrays of scalars/vectors become one leaf per element
     *       ({@code texCoords[3]} &#8594; {@code texCoords_0..2}).</li>
     *   <li>Anything else (scalars, vectors, structs without member metadata) stays one leaf.</li>
     * </ul>
     *
     * @param type        GLSL type name
     * @param baseName    declared identifier
     * @param arrayLength array element count, or {@code 1} when not an array
     * @return leaves in declaration order; never empty
     */
    public static List<SplitLeaf> splitVarying(String type, String baseName, int arrayLength) {
        String base = baseName != null ? baseName : "varying";
        String kind = type != null ? type : "vec4";
        int elements = Math.max(1, arrayLength);

        if (isMatrixType(kind)) {
            List<SplitLeaf> leaves = new ArrayList<>();
            int columns = locationSlotsFor(kind, 1);
            String columnType = (kind.startsWith("dmat") ? "dvec" : "vec") + matrixRows(kind);
            for (int c = 0; c < columns; c++) {
                for (int e = 0; e < elements; e++) {
                    String leaf = base + "_c" + c + (elements > 1 ? "_" + e : "");
                    leaves.add(new SplitLeaf(leaf, columnType, 1));
                }
            }
            return List.copyOf(leaves);
        }
        if (elements > 1) {
            List<SplitLeaf> leaves = new ArrayList<>(elements);
            for (int e = 0; e < elements; e++) {
                leaves.add(new SplitLeaf(base + "_" + e, kind, 1));
            }
            return List.copyOf(leaves);
        }
        return List.of(new SplitLeaf(base, kind, 1));
    }

    /**
     * Scalarizes a struct varying whose member types are known.
     *
     * @param baseName    declared identifier
     * @param memberTypes member GLSL types in order; may be {@code null}/empty
     * @return one leaf per member ({@code base_member<index>}), or the single base leaf
     */
    public static List<SplitLeaf> splitStructVarying(String baseName, List<String> memberTypes) {
        String base = baseName != null ? baseName : "varying";
        if (memberTypes == null || memberTypes.isEmpty()) {
            return List.of(new SplitLeaf(base, "vec4", 1));
        }
        List<SplitLeaf> leaves = new ArrayList<>(memberTypes.size());
        for (int i = 0; i < memberTypes.size(); i++) {
            String memberType = memberTypes.get(i) != null ? memberTypes.get(i) : "vec4";
            for (SplitLeaf nested : splitVarying(memberType, base + "_m" + i, 1)) {
                leaves.add(nested);
            }
        }
        return List.copyOf(leaves);
    }

    /**
     * Assigns dense locations over split leaves in declaration order, advancing by each
     * leaf's slot count (the rank==location workaround: a matrix leaf run never overlaps
     * the next varying).
     *
     * @param leaves leaves in declaration order; may be {@code null}/empty
     * @return leaf name to location, in encounter order; empty for {@code null}/empty input
     */
    public static Map<String, Integer> assignDenseLocations(List<SplitLeaf> leaves) {
        Map<String, Integer> locations = new LinkedHashMap<>();
        if (leaves == null || leaves.isEmpty()) {
            return locations;
        }
        int next = 0;
        for (SplitLeaf leaf : leaves) {
            if (leaf == null || locations.containsKey(leaf.name())) {
                continue;
            }
            locations.put(leaf.name(), next);
            next += Math.max(1, leaf.slots());
        }
        return locations;
    }

    // ------------------------------------------------------------------
    // Union-path split application (plan §4 remainder: §3.2 + §5).
    //
    // The helpers above scalarize declarations; the helpers below apply that
    // scalarization end-to-end on the union path. All three original helpers
    // are exercised here (not helpers-only any more):
    //   splitVarying -> expandVaryingLeaves (builtin types),
    //   splitStructVarying -> expandVaryingLeaves (struct types),
    //   assignDenseLocations -> assignSplitLeafLocations.
    //
    // Discipline: rank==location. Leaves are declared in order and numbered
    // densely (0..N-1 over leaves); each original varying's base location is
    // its first leaf's location, and slot accounting (next base starts after
    // the previous base's leaf run) matches locationSlotsFor for mat/array
    // and fixes the struct undercount (locationSlotsFor sees 1 for an
    // unknown struct name; the split sees N members).
    //
    // Body-rewrite scope (documented, not hidden): the fixtures cover
    //   - mat (non-array): vertex whole-matrix writes `m = EXPR;` and literal
    //     column writes `m[i] = v;` / `m[i][j] = x;`; fragment reads via a
    //     `matN(m_c0, ...)` reconstruction define (no fragment body rewrite
    //     needed, so `m`, `m[i]`, `m * v` all keep working);
    //   - vec/scalar arrays (non-matrix): literal-index `a[i]` accesses in
    //     both stages become `a_i`;
    //   - structs (non-array, known definition in the same stage source):
    //     per-member `s.member` accesses become `s_m<index>`; whole-struct
    //     assignments and dynamic indexing are NOT rewritten.
    // A varying whose body uses an unhandled pattern is left unsplit (single
    // declaration, slot accounting only), so real packs never miscompile for
    // the sake of scalarizing. Line-oriented regex like the location-injection
    // pass: function-local `in`/`out` never occur in legal GLSL, and comments
    // / string literals / directives are not stripped here either.
    // ------------------------------------------------------------------

    /**
     * One member of a {@code struct} varying definition.
     *
     * @param type        member GLSL type (for example {@code vec3})
     * @param name        member identifier (for example {@code albedo})
     * @param arrayLength array element count, or {@code 1} when not an array
     */
    public record StructMember(String type, String name, int arrayLength) {
    }

    /**
     * One interface varying declaration in source order.
     *
     * @param name          declared identifier
     * @param type          GLSL type name (builtin or struct name)
     * @param arrayLength   array element count, or {@code 1} when not an array
     * @param structMembers member list when {@code type} is a struct with a
     *                      definition visible in the same source; empty otherwise
     */
    public record VaryingDecl(String name, String type, int arrayLength,
                              List<StructMember> structMembers) {
    }

    private static final Pattern STRUCT_DEF_PATTERN = Pattern.compile(
            "\\bstruct\\s+([A-Za-z_]\\w*)\\s*\\{([^}]*)\\}\\s*;?", Pattern.DOTALL);
    private static final Pattern STRUCT_MEMBER_DECL = Pattern.compile(
            "^\\s*([A-Za-z_]\\w*)\\s+([^;]+);?\\s*$");
    private static final Pattern DECLARATOR_NAME_LEN = Pattern.compile(
            "^\\s*([A-Za-z_]\\w*)\\s*(?:\\[\\s*([^\\]]*)\\s*\\])?\\s*$");

    private static final Pattern INTERFACE_LINE_SPLIT = Pattern.compile(
            "(?m)^([ \\t]*)((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise"
                    + "|patch|highp|mediump|lowp)[ \\t]+)*)(in|out)([ \\t]+)"
                    + "((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise"
                    + "|patch|highp|mediump|lowp)[ \\t]+)*)"
                    + "([A-Za-z_]\\w*)([ \\t]+)"
                    + "([A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?"
                    + "(?:[ \\t]*,[ \\t]*[A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?)*)"
                    + "([ \\t]*);");

    /** Whether {@code type} is a builtin scalar/vector/matrix (never a struct name). */
    public static boolean isBuiltinInterfaceType(String type) {
        if (type == null) {
            return false;
        }
        return type.matches(
                "void|bool|int|uint|float|double|bvec[234]|ivec[234]|uvec[234]|vec[234]"
                        + "|dvec[234]|mat[234]|dmat[234]|mat[234]x[234]|dmat[234]x[234]");
    }

    /**
     * Parses the member list of {@code structName} from {@code source}.
     *
     * @return members in order; empty when no definition is visible (unknown
     *         struct: the caller must fall back to unsplit accounting)
     */
    public static List<StructMember> parseStructMembers(String source, String structName) {
        List<StructMember> members = new ArrayList<>();
        if (source == null || source.isEmpty() || structName == null || structName.isEmpty()) {
            return members;
        }
        Matcher def = STRUCT_DEF_PATTERN.matcher(source);
        while (def.find()) {
            if (!structName.equals(def.group(1))) {
                continue;
            }
            String body = def.group(2);
            for (String raw : body.split(";")) {
                String line = raw.trim();
                if (line.isEmpty()) {
                    continue;
                }
                Matcher member = STRUCT_MEMBER_DECL.matcher(line);
                if (!member.matches()) {
                    continue;
                }
                String memberType = member.group(1);
                for (String declarator : member.group(2).split(",")) {
                    Matcher parsed = DECLARATOR_NAME_LEN.matcher(declarator.trim());
                    if (!parsed.matches()) {
                        continue;
                    }
                    String memberName = parsed.group(1);
                    int arrayLength = 1;
                    String size = parsed.group(2);
                    if (size != null && !size.trim().isEmpty()) {
                        try {
                            arrayLength = Math.max(1, Integer.parseInt(size.trim()));
                        } catch (NumberFormatException unsized) {
                            arrayLength = 1;
                        }
                    }
                    if (!memberName.isEmpty()) {
                        members.add(new StructMember(memberType, memberName, arrayLength));
                    }
                }
            }
            break;
        }
        return members;
    }

    /**
     * Expands one varying declarator into split leaves via the original helpers.
     * Builtin types go through {@link #splitVarying}; struct types go through
     * {@link #splitStructVarying} with the definition's member types (mat members
     * nest into per-column leaves via the same path).
     *
     * @return leaves in declaration order; never empty. Unknown structs (no member
     *         metadata) yield the single base leaf so the declaration is left
     *         untouched rather than mistyped.
     */
    public static List<SplitLeaf> expandVaryingLeaves(
            String type, String baseName, int arrayLength, List<StructMember> structMembers) {
        String base = baseName != null ? baseName : "varying";
        String kind = type != null ? type : "vec4";
        int elements = Math.max(1, arrayLength);
        if (!isBuiltinInterfaceType(kind)) {
            if (structMembers == null || structMembers.isEmpty()) {
                return List.of(new SplitLeaf(base, kind, 1));
            }
            if (elements > 1) {
                return List.of(new SplitLeaf(base, kind, elements));
            }
            List<String> memberTypes = new ArrayList<>(structMembers.size());
            for (StructMember member : structMembers) {
                memberTypes.add(member != null && member.type() != null ? member.type() : "vec4");
            }
            return splitStructVarying(base, memberTypes);
        }
        return splitVarying(kind, base, elements);
    }

    /**
     * Collects interface varying declarations for one direction in source order.
     *
     * @param source    GLSL source; may be {@code null}
     * @param direction {@code "in"} or {@code "out"}
     * @return declarations in source order; empty for {@code null}/blank input
     */
    public static List<VaryingDecl> collectVaryingDecls(String source, String direction) {
        List<VaryingDecl> decls = new ArrayList<>();
        if (source == null || source.isEmpty() || direction == null) {
            return decls;
        }
        Matcher matcher = INTERFACE_LINE_SPLIT.matcher(source);
        while (matcher.find()) {
            if (!direction.equals(matcher.group(3))) {
                continue;
            }
            String type = matcher.group(6);
            for (String raw : matcher.group(8).split(",")) {
                String declarator = raw.trim();
                if (declarator.isEmpty()) {
                    continue;
                }
                Matcher parsed = DECLARATOR_NAME_LEN.matcher(declarator);
                if (!parsed.matches()) {
                    continue;
                }
                String name = parsed.group(1);
                int arrayLength = 1;
                String size = parsed.group(2);
                if (size != null && !size.trim().isEmpty()) {
                    try {
                        arrayLength = Math.max(1, Integer.parseInt(size.trim()));
                    } catch (NumberFormatException unsized) {
                        arrayLength = 1;
                    }
                }
                if (name.isEmpty()) {
                    continue;
                }
                boolean duplicate = false;
                for (VaryingDecl seen : decls) {
                    if (seen.name().equals(name)) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) {
                    continue;
                }
                List<StructMember> members = isBuiltinInterfaceType(type)
                        ? List.of() : parseStructMembers(source, type);
                decls.add(new VaryingDecl(name, type, arrayLength, members));
            }
        }
        return decls;
    }

    /**
     * Assigns dense leaf locations over declarations in order via
     * {@link #assignDenseLocations} (the rank==location discipline: each leaf
     * run advances by its slot count, so a matrix run never overlaps the next
     * varying).
     *
     * @return leaf name to location in encounter order
     */
    public static Map<String, Integer> assignSplitLeafLocations(List<VaryingDecl> decls) {
        List<SplitLeaf> leaves = new ArrayList<>();
        if (decls != null) {
            for (VaryingDecl decl : decls) {
                if (decl == null) {
                    continue;
                }
                leaves.addAll(expandVaryingLeaves(
                        decl.type(), decl.name(), decl.arrayLength(), decl.structMembers()));
            }
        }
        return assignDenseLocations(leaves);
    }

    /**
     * Base varying name to base location (first leaf's location) for the same
     * declaration order. Slot accounting matches {@link #locationSlotsFor} for
     * mat/array and corrects the struct undercount.
     *
     * @return base name to location in declaration order
     */
    public static Map<String, Integer> assignSplitBaseLocations(List<VaryingDecl> decls) {
        Map<String, Integer> baseLocations = new LinkedHashMap<>();
        Map<String, Integer> leafLocations = assignSplitLeafLocations(decls);
        if (decls != null) {
            for (VaryingDecl decl : decls) {
                if (decl == null || baseLocations.containsKey(decl.name())) {
                    continue;
                }
                List<SplitLeaf> leaves = expandVaryingLeaves(
                        decl.type(), decl.name(), decl.arrayLength(), decl.structMembers());
                if (!leaves.isEmpty() && leafLocations.containsKey(leaves.get(0).name())) {
                    baseLocations.put(decl.name(), leafLocations.get(leaves.get(0).name()));
                }
            }
        }
        return baseLocations;
    }

    /**
     * Whether a declaration is safe to scalarize given the stage bodies. Only the
     * fixture-covered patterns are allowlisted; anything else falls back to the
     * single declaration with slot accounting (never a miscompile):
     * <ul>
     *   <li>mat (non-array): vertex body must not use dynamic column indexing
     *       ({@code m[i]} with a non-literal) and must not read the matrix as a
     *       whole in the vertex stage in ways the column decomposition cannot
     *       express; fragment reads are always safe via the reconstruction
     *       define.</li>
     *   <li>vec/scalar arrays: every subscript of the base in both bodies must
     *       be a non-negative integer literal.</li>
     *   <li>structs: every use of the base in both bodies must be a
     *       {@code base.knownMember} access (no whole-struct assignment, no
     *       dynamic member access).</li>
     * </ul>
     */
    static boolean isSafeToSplit(VaryingDecl decl, String vertexBody, String fragmentBody) {
        if (decl == null) {
            return false;
        }
        List<SplitLeaf> leaves = expandVaryingLeaves(
                decl.type(), decl.name(), decl.arrayLength(), decl.structMembers());
        if (leaves.size() <= 1 && leaves.get(0).name().equals(decl.name())) {
            return false;
        }
        // Safety scans uses only: interface declaration lines themselves contain
        // the bare base name (`out Surface surface;`) and must not count as a
        // whole-variable use.
        String vertexUses = stripInterfaceDeclLines(vertexBody);
        String fragmentUses = stripInterfaceDeclLines(fragmentBody);
        String base = decl.name();
        if (!isBuiltinInterfaceType(decl.type())) {
            return structUsesAreSafe(base, decl.structMembers(), vertexUses, fragmentUses);
        }
        if (isMatrixType(decl.type())) {
            if (decl.arrayLength() > 1) {
                return false;
            }
            return matUsesAreSafe(base, vertexUses, fragmentUses);
        }
        if (decl.arrayLength() > 1) {
            return arrayUsesAreSafe(base, vertexUses, fragmentUses);
        }
        return false;
    }

    /**
     * Removes every top-level {@code in}/{@code out} declaration statement, so
     * use-safety scans see only executable code (declarations carry the bare
     * base name by definition).
     */
    static String stripInterfaceDeclLines(String source) {
        if (source == null || source.isEmpty()) {
            return source == null ? "" : source;
        }
        Matcher matcher = INTERFACE_LINE_SPLIT.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            out.append(source, last, matcher.start());
            last = matcher.end();
            changed = true;
        }
        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /** Whether one source line is a top-level {@code in}/{@code out} declaration. */
    private static boolean isInterfaceDeclLine(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        return INTERFACE_LINE_SPLIT.matcher(line).find();
    }

    private static boolean matUsesAreSafe(String base, String vertexBody, String fragmentBody) {
        Pattern dynamicIndex = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base)
                        + "\\s*\\[\\s*[^\\]0-9\\s][^\\]]*\\]");
        Pattern compoundAssign = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base)
                        + "\\s*(?:\\+\\+|--|\\+=|-=|\\*=|/=|%=|&=|\\|=|\\^=|<<=|>>=)");
        for (String body : new String[]{vertexBody, fragmentBody}) {
            if (body == null || body.isEmpty()) {
                continue;
            }
            if (dynamicIndex.matcher(body).find()) {
                return false;
            }
        }
        if (vertexBody != null && !vertexBody.isEmpty()
                && compoundAssign.matcher(vertexBody).find()) {
            return false;
        }
        return true;
    }

    private static boolean arrayUsesAreSafe(String base, String vertexBody, String fragmentBody) {
        Pattern subscript = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base) + "\\s*\\[([^\\]]*)\\]");
        for (String body : new String[]{vertexBody, fragmentBody}) {
            if (body == null || body.isEmpty()) {
                continue;
            }
            Matcher matcher = subscript.matcher(body);
            while (matcher.find()) {
                if (!matcher.group(1).trim().matches("\\d+")) {
                    return false;
                }
            }
            Pattern bare = Pattern.compile(
                    "(?<![A-Za-z0-9_.])" + Pattern.quote(base) + "(?![A-Za-z0-9_\\[])");
            if (bare.matcher(body).find()) {
                return false;
            }
        }
        return true;
    }

    private static boolean structUsesAreSafe(
            String base, List<StructMember> members, String vertexBody, String fragmentBody) {
        if (members == null || members.isEmpty()) {
            return false;
        }
        Set<String> memberNames = new java.util.HashSet<>();
        for (StructMember member : members) {
            if (member != null && member.name() != null) {
                memberNames.add(member.name());
            }
        }
        Pattern use = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base)
                        + "\\s*\\.\\s*([A-Za-z_]\\w*)");
        Pattern bare = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base) + "(?![A-Za-z0-9_])");
        for (String body : new String[]{vertexBody, fragmentBody}) {
            if (body == null || body.isEmpty()) {
                continue;
            }
            Matcher dotted = use.matcher(body);
            while (dotted.find()) {
                if (!memberNames.contains(dotted.group(1))) {
                    return false;
                }
            }
            String withoutDotted = use.matcher(body).replaceAll(" ");
            if (bare.matcher(withoutDotted).find()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Rewrites multi-leaf varying declarations into scalar leaf declarations,
     * preserving indentation, interpolation/precision qualifiers and direction.
     * Single-leaf declarations are left byte-identical. Only declarations whose
     * base passes {@link #isSafeToSplit} are rewritten; the rest keep slot
     * accounting without a rewrite.
     *
     * @param safeBases bases approved for scalarization in this stage
     */
    static String rewriteSplitDeclarations(
            String source, String direction, Set<String> safeBases) {
        if (source == null || source.isEmpty() || safeBases == null || safeBases.isEmpty()) {
            return source;
        }
        Matcher matcher = INTERFACE_LINE_SPLIT.matcher(source);
        StringBuilder out = new StringBuilder(source.length() + 128);
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            if (!direction.equals(matcher.group(3))) {
                continue;
            }
            String type = matcher.group(6);
            String[] declarators = matcher.group(8).split(",");
            boolean lineNeedsSplit = false;
            for (String raw : declarators) {
                Matcher parsed = DECLARATOR_NAME_LEN.matcher(raw.trim());
                if (parsed.matches() && safeBases.contains(parsed.group(1))) {
                    lineNeedsSplit = true;
                    break;
                }
            }
            if (!lineNeedsSplit) {
                continue;
            }
            out.append(source, last, matcher.start());
            String indent = matcher.group(1);
            String preQual = matcher.group(2);
            String dir = matcher.group(3);
            String afterDir = matcher.group(4);
            String postQual = matcher.group(5);
            String afterType = matcher.group(7);
            String beforeSemi = matcher.group(9);
            boolean first = true;
            for (String raw : declarators) {
                Matcher parsed = DECLARATOR_NAME_LEN.matcher(raw.trim());
                if (!parsed.matches()) {
                    continue;
                }
                String name = parsed.group(1);
                int arrayLength = 1;
                String size = parsed.group(2);
                if (size != null && !size.trim().isEmpty()) {
                    try {
                        arrayLength = Math.max(1, Integer.parseInt(size.trim()));
                    } catch (NumberFormatException unsized) {
                        arrayLength = 1;
                    }
                }
                List<StructMember> members = isBuiltinInterfaceType(type)
                        ? List.of() : parseStructMembers(source, type);
                List<SplitLeaf> leaves;
                if (safeBases.contains(name)) {
                    leaves = expandVaryingLeaves(type, name, arrayLength, members);
                } else {
                    leaves = null;
                }
                if (leaves == null) {
                    if (!first) {
                        out.append('\n');
                    }
                    out.append(indent).append(preQual).append(dir).append(afterDir)
                            .append(postQual).append(type).append(afterType)
                            .append(raw.trim()).append(beforeSemi).append(';');
                    first = false;
                    continue;
                }
                for (SplitLeaf leaf : leaves) {
                    String leafDeclarator = leaf.name();
                    String leafType = leaf.type();
                    if (!first) {
                        out.append('\n');
                    }
                    out.append(indent).append(preQual).append(dir).append(afterDir)
                            .append(postQual).append(leafType).append(afterType)
                            .append(leafDeclarator).append(beforeSemi).append(';');
                    first = false;
                }
            }
            last = matcher.end();
            changed = true;
        }
        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /**
     * Reconstruction define for a split matrix varying on the reading
     * (fragment) stage: {@code #define m mat4(m_c0, m_c1, ...)}. Reads keep
     * working without further body edits; the writing (vertex) stage never
     * receives this define (an assignment through it would be illegal).
     */
    static String matReconstructionDefine(String baseName, String matrixType) {
        if (baseName == null || matrixType == null || !isMatrixType(matrixType)) {
            return "";
        }
        int columns = locationSlotsFor(matrixType, 1);
        StringBuilder define = new StringBuilder("#define ")
                .append(baseName).append(' ').append(matrixType).append('(');
        for (int c = 0; c < columns; c++) {
            if (c > 0) {
                define.append(", ");
            }
            define.append(baseName).append("_c").append(c);
        }
        return define.append(")\n").toString();
    }

    /**
     * Rewrites stage bodies from base varyings to split leaves (vertex writes
     * decompose, both stages rewrite literal array subscripts and struct member
     * accesses). Only {@code safeBases} are touched.
     */
    static String rewriteSplitStageUses(
            String source, boolean isVertex, Map<String, VaryingDecl> declsByName,
            Set<String> safeBases) {
        if (source == null || source.isEmpty() || safeBases == null || safeBases.isEmpty()) {
            return source;
        }
        // Line-oriented like the location pass: interface declaration lines keep
        // their array specifiers (`out vec4 a[2];`) for the declaration rewrite
        // below, so use-rewrites (`a[0]` to `a_0`) apply to code lines only.
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder(source.length() + 128);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!isInterfaceDeclLine(line)) {
                line = rewriteSplitCodeLine(line, isVertex, declsByName, safeBases);
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String rewriteSplitCodeLine(
            String line, boolean isVertex, Map<String, VaryingDecl> declsByName,
            Set<String> safeBases) {
        String rewritten = line;
        for (String base : safeBases) {
            VaryingDecl decl = declsByName != null ? declsByName.get(base) : null;
            if (decl == null) {
                continue;
            }
            if (!isBuiltinInterfaceType(decl.type())) {
                rewritten = rewriteStructMemberUses(rewritten, base, decl.structMembers());
            } else if (isMatrixType(decl.type())) {
                if (isVertex) {
                    rewritten = rewriteMatWrites(rewritten, base, decl.type());
                }
            } else if (decl.arrayLength() > 1) {
                rewritten = rewriteArraySubscripts(rewritten, base);
            }
        }
        return rewritten;
    }

    private static String rewriteArraySubscripts(String source, String base) {
        Pattern subscript = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base) + "\\s*\\[\\s*(\\d+)\\s*\\]");
        Matcher matcher = subscript.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            out.append(source, last, matcher.start());
            out.append(base).append('_').append(matcher.group(1));
            last = matcher.end();
            changed = true;
        }
        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    private static String rewriteStructMemberUses(
            String source, String base, List<StructMember> members) {
        if (members == null || members.isEmpty()) {
            return source;
        }
        String body = source;
        for (int i = 0; i < members.size(); i++) {
            StructMember member = members.get(i);
            if (member == null || member.name() == null) {
                continue;
            }
            Pattern access = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + Pattern.quote(base)
                            + "\\s*\\.\\s*" + Pattern.quote(member.name())
                            + "(?![A-Za-z0-9_])");
            body = access.matcher(body).replaceAll(
                    Matcher.quoteReplacement(base + "_m" + i));
        }
        return body;
    }

    private static String rewriteMatWrites(String source, String base, String matrixType) {
        int columns = locationSlotsFor(matrixType, 1);
        String body = source;
        // Whole-matrix writes decompose per column: `m = EXPR;` becomes
        // `m_c0 = (EXPR)[0]; ...`. The parenthesized RHS keeps constructors,
        // variables and products valid; a self-reference inside EXPR is still
        // rewritten by the blanket column pass below.
        Pattern wholeAssign = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base) + "\\s*=\\s*([^;]+);");
        Matcher matcher = wholeAssign.matcher(body);
        StringBuilder out = new StringBuilder(body.length() + 128);
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            String rhs = matcher.group(1).trim();
            out.append(body, last, matcher.start());
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    out.append(' ');
                }
                out.append(base).append("_c").append(c)
                        .append(" = (").append(rhs).append(")[").append(c).append("];");
            }
            last = matcher.end();
            changed = true;
        }
        if (changed) {
            out.append(body, last, body.length());
            body = out.toString();
        }
        // Remaining `base[lit]` forms (column writes, element writes/reads such
        // as `base[0][1]`) all share the `base[lit]` prefix, which becomes the
        // leaf name and leaves any trailing `[row]` in place: `base[0][1]`
        // becomes `base_c0[1]`. `base_cN` leaves never match (after `base`
        // comes `_`, not `[`).
        Pattern columnAccess = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(base) + "\\s*\\[\\s*(\\d+)\\s*\\]");
        Matcher access = columnAccess.matcher(body);
        StringBuilder rewritten = new StringBuilder(body.length());
        int cursor = 0;
        boolean accessChanged = false;
        while (access.find()) {
            if (!accessChanged) {
                rewritten.append(body, 0, access.start());
            } else {
                rewritten.append(body, cursor, access.start());
            }
            rewritten.append(base).append("_c").append(access.group(1));
            cursor = access.end();
            accessChanged = true;
        }
        if (accessChanged) {
            rewritten.append(body, cursor, body.length());
            body = rewritten.toString();
        }
        return body;
    }

    private static boolean isMatrixType(String type) {
        return type.startsWith("mat") || type.startsWith("dmat");
    }

    /** Row count of a matrix type name ({@code mat4x3} has 3 rows; square forms have N). */
    private static int matrixRows(String type) {
        String core = type.startsWith("dmat") ? type.substring(4) : type.substring(3);
        int by = core.indexOf('x');
        try {
            if (by >= 0) {
                return Math.max(2, Math.min(4, Integer.parseInt(core.substring(by + 1))));
            }
            return Math.max(2, Math.min(4, Integer.parseInt(core)));
        } catch (NumberFormatException notMatrix) {
            return 4;
        }
    }

    /**
     * Union helper used by {@link ProgramTranslator}: merges per-stage varying slot maps,
     * keeping the widest declaration per name and reporting names seen on only one side.
     *
     * @param vertexOuts   vertex-stage {@code out} name to slots, in declaration order
     * @param fragmentIns  fragment-stage {@code in} name to slots, in declaration order
     * @return merged name to slots in first-seen order
     */
    public static Map<String, Integer> unionVaryingSlots(
            Map<String, Integer> vertexOuts, Map<String, Integer> fragmentIns) {
        Map<String, Integer> union = new LinkedHashMap<>();
        if (vertexOuts != null) {
            union.putAll(vertexOuts);
        }
        if (fragmentIns != null) {
            for (Map.Entry<String, Integer> entry : fragmentIns.entrySet()) {
                union.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
        return union;
    }

    /**
     * Names declared on exactly one side of a vertex/fragment varying interface.
     *
     * @return names in union order that are missing a writer (vertex {@code out}) or a
     *         reader (fragment {@code in}); empty when the interface agrees
     */
    public static Set<String> oneSidedVaryings(
            Map<String, Integer> vertexOuts, Map<String, Integer> fragmentIns) {
        Set<String> oneSided = new LinkedHashSet<>();
        Map<String, Integer> outs = vertexOuts != null ? vertexOuts : Map.of();
        Map<String, Integer> ins = fragmentIns != null ? fragmentIns : Map.of();
        for (String name : unionVaryingSlots(outs, ins).keySet()) {
            if (!outs.containsKey(name) || !ins.containsKey(name)) {
                oneSided.add(name);
            }
        }
        return Set.copyOf(oneSided);
    }
}
