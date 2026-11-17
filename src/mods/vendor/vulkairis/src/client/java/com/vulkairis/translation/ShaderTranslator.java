package com.vulkairis.translation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprocesses and adapts Iris / Minecraft GLSL shader sources into Vulkan-compatible GLSL
 * before handing them to the in-process SPIR-V compiler.
 */
public class ShaderTranslator {
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^\\s*#version\\s+(\\d+)(?:\\s+(core|compatibility))?", Pattern.MULTILINE);
    private static final Pattern EXTENSION_PATTERN = Pattern.compile(
            "^[ \\t]*#extension\\b[^\\r\\n]*", Pattern.MULTILINE);
    private static final Pattern EXTENSION_NAME_PATTERN = Pattern.compile("#extension\\s+(\\S+)");

    /**
     * One physical uniform declaration. The declarator list deliberately excludes '{', '}' and
     * ';' so it can never run past the end of a single statement or swallow an interface block.
     */
    private static final Pattern UNIFORM_DECL_PATTERN = Pattern.compile(
            "^([ \\t]*)uniform[ \\t]+([A-Za-z_]\\w*)[ \\t]+([^;{}]*);", Pattern.MULTILINE);

    /** Non-opaque built-in types that must live inside a uniform block under Vulkan. */
    private static final Pattern NON_OPAQUE_TYPE_PATTERN = Pattern.compile(
            "^(?:bool|int|uint|float|double|bvec[234]|ivec[234]|uvec[234]|vec[234]|dvec[234]|mat[234](?:x[234])?)$");

    /** A single declarator: an identifier with an optional (possibly unsized) array specifier. */
    private static final Pattern DECLARATOR_PATTERN = Pattern.compile(
            "[A-Za-z_]\\w*(?:\\s*\\[\\s*[^\\]]*\\s*\\])?");

    /**
     * Iris renames vertex attributes with an {@code iris_} prefix. VulkanMod's
     * {@code GLSLParser} matches shader attributes to {@code VertexFormat} elements strictly by
     * name, and Mojang's formats use the unprefixed names ({@code Position}, {@code Color}, ...),
     * so the prefixed identifiers never match and VulkanMod logs
     * "Element iris_Position not found in elements [...]".
     *
     * <p>The lookbehinds keep the match a standalone token: a leading identifier character
     * (so {@code myiris_Position} is left alone) and a leading dot (so {@code object.iris_Position}
     * is left alone) both reject the position. The trailing {@code \b} prevents a partial match on
     * identifiers such as {@code iris_Color2}. Unknown {@code iris_Foo} names do not appear in the
     * alternation and are therefore never guessed at.</p>
     */
    private static final Pattern IRIS_ATTRIBUTE_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])(?<!\\.)iris_(Position|UV0|UV1|UV2|UV|Color|Normal|Entity)\\b");

    /**
     * A standalone {@code bool} type token in a declaration (e.g. {@code bool heavyFog;},
     * {@code uniform bool flag;}). The lookarounds keep this from touching identifiers that merely
     * contain "bool" such as {@code booleanType}, and from rewriting uses of a variable named
     * {@code bool} (there are none in GLSL, but this stays conservative).
     */
    private static final Pattern BOOL_TYPE_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])bool(?![A-Za-z0-9_])");

    /**
     * Standalone {@code true}/{@code false} literals. {@link #normalizeTypesForVulkanMod} rewrites
     * these to {@code 1}/{@code 0} <em>only inside the injected uniform block</em>, defensively,
     * should a block member ever carry an initializer. Outside the block the literals are left
     * untouched so boolean expressions elsewhere keep their exact type.
     */
    private static final Pattern BOOL_LITERAL_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_])(true|false)(?![A-Za-z0-9_])");

    public static final String UNIFORM_BLOCK_NAME = "VulkairisUniformBlock";

    /**
     * Locates the header of the injected {@link #UNIFORM_BLOCK_NAME} interface block, so
     * {@link #normalizeTypesForVulkanMod} can scope its {@code bool} -&gt; {@code int} conversion
     * exclusively to that block's body.
     */
    private static final Pattern UNIFORM_BLOCK_HEADER_PATTERN = Pattern.compile(
            "\\buniform\\s+" + Pattern.quote(UNIFORM_BLOCK_NAME) + "\\s*\\{");

    /**
     * Stable, explicit binding for the injected block. Kept well clear of the low bindings that
     * Shaderc auto-assigns to opaque samplers so the two can never collide.
     *
     * <p>This is the <em>input</em> binding produced by {@link #preprocessGlsl(String, boolean)}.
     * VulkanMod's {@code Pipeline.createDescriptorSetLayout} indexes its binding array by the raw
     * binding number, so this value is not Vulkan-legal on its own: callers on the VulkanMod path
     * must run {@link #remapDescriptorBindings} to compact every descriptor into a dense
     * {@code 0..N-1} range before building the pipeline.</p>
     */
    public static final int UNIFORM_BLOCK_BINDING = 100;

    /** Every {@code layout(...)} qualifier list; used to locate descriptor binding qualifiers. */
    private static final Pattern LAYOUT_QUALIFIER_PATTERN = Pattern.compile("layout\\s*\\(([^)]*)\\)");

    /** The {@code binding = N} token inside a layout qualifier list. */
    private static final Pattern BINDING_QUALIFIER_PATTERN = Pattern.compile("\\bbinding\\s*=\\s*(\\d+)");

    /** An interface block introduced after a layout qualifier: {@code uniform Name {} / buffer Name {}}. */
    private static final Pattern BLOCK_AFTER_LAYOUT_PATTERN =
            Pattern.compile("\\s*(?:uniform|buffer)\\s+([A-Za-z_]\\w*)\\s*\\{");

    /** An opaque sampler/image descriptor after a layout qualifier; captures the declared name. */
    private static final Pattern OPAQUE_AFTER_LAYOUT_PATTERN = Pattern.compile(
            "\\s*(?:uniform\\s+)?(?:highp\\s+|mediump\\s+|lowp\\s+)?(?:sampler\\w+|image\\w+)\\s+([A-Za-z_]\\w*)");

    /**
     * A complete opaque ({@code sampler*}/{@code image*}) uniform declaration, optionally preceded by a
     * {@code layout(...)} qualifier and optionally carrying precision or memory qualifiers. Used both
     * to discover opaque descriptors that have <em>no</em> binding and (via
     * {@link #ensureDescriptorBindings}) to inject one.
     *
     * <p>Capture groups: {@code 1} = leading indentation, {@code 2} = whitespace between
     * {@code layout} and its parentheses, {@code 3} = layout qualifier contents ({@code null} when
     * there is no layout qualifier), {@code 4} = whitespace after the layout list, {@code 5} =
     * {@code uniform} plus trailing whitespace, {@code 6} = precision/memory qualifiers (possibly
     * empty), {@code 7} = the opaque type, {@code 8} = whitespace before the declared name,
     * {@code 9} = declared name, {@code 10} = optional array specifier, {@code 11} = trailing
     * whitespace and the terminating semicolon.</p>
     */
    private static final Pattern OPAQUE_UNIFORM_DECL_PATTERN = Pattern.compile(
            "(?m)^([ \\t]*)"
                    + "(?:layout([ \\t]*)\\(([^)]*)\\)(\\s+))?"
                    + "(uniform\\s+)"
                    + "((?:(?:highp|mediump|lowp|readonly|writeonly|coherent|volatile|restrict|precise)\\s+)*)"
                    + "(sampler\\w+|image\\w+)"
                    + "(\\s+)"
                    + "([A-Za-z_]\\w*)"
                    + "([ \\t]*\\[[^\\]]*\\])?"
                    + "([ \\t]*;)");

    /**
     * Phase 2: whether the source already carries Vulkairis translator output (our injected
     * uniform block). Translating such a unit again must not silently apply changes twice:
     * {@link #preprocessGlsl} detects this state and produces a stable, declared result.
     */
    public static boolean isTranslatedSource(String source) {
        return source != null && source.contains(UNIFORM_BLOCK_NAME);
    }

    /**
     * Prepares shader GLSL source for Vulkan compilation:
     * - Ensures a valid #version directive (upgrades legacy versions to 450 core)
     * - Hoists all #extension directives directly after #version
     * - Injects GL_ARB_separate_shader_objects and GL_ARB_shading_language_420pack
     * - Adapts legacy attribute/varying keywords to in/out (preserving qualifiers)
     * - Wraps non-opaque uniforms into a uniform block for Vulkan compliance, dropping the
     *   default initializers Iris attaches to many uniforms (illegal inside a block)
     */
    public static String preprocessGlsl(String rawSource, boolean isVertex) {
        return preprocessGlsl(rawSource, isVertex, null);
    }

    /**
     * Prepares shader GLSL source for Vulkan compilation (see
     * {@link #preprocessGlsl(String, boolean)}).
     *
     * @param rawSource   the raw Iris GLSL
     * @param isVertex    whether this is a vertex stage
     * @param includerPath shaders-relative path of the including file (e.g.
     *        {@code gbuffers_terrain.vsh}) when known; {@code null} when the source is detached
     *        from its file. Absolute ({@code /...}) includes resolve either way; relative ones
     *        need this path and otherwise become named {@code #error} lines.
     * @return the translated source
     */
    public static String preprocessGlsl(String rawSource, boolean isVertex, String includerPath) {
        if (rawSource == null || rawSource.isBlank()) {
            return rawSource;
        }

        // Phase A: expand surviving pack #includes before anything else, so the translation
        // cache key below covers the expanded text and compiler errors map via #line markers.
        // Sources without #include pass through byte-identical (and never touch Iris at all).
        String expanded = rawSource.contains("#include")
                ? IncludeResolver.expandEntry(
                        com.vulkairis.iris.IrisBridge.packFiles(), includerPath, rawSource,
                        com.vulkairis.iris.SourceProvenance.currentGeneration()).text()
                : rawSource;

        String body = expanded.replace("\r\n", "\n").replace('\r', '\n');

        // Phase 12: identical inputs share translations. The key covers the normalized source,
        // stage and translator version; failures are never cached.
        String translationKey = TranslationCache.key(
                com.vulkairis.diagnostics.DiagnosticManager.hashSource(body), isVertex);
        String cachedTranslation = TranslationCache.lookup(translationKey);
        if (cachedTranslation != null) {
            return cachedTranslation;
        }

        // Legacy builtins first (mirrors ProgramTranslator.rewriteStage): later passes
        // only see core-profile identifiers. Without this, Iris pack sources carrying
        // GLSL-120 builtins (gl_TextureMatrix, gl_FragData, texture2D, ftransform, ...)
        // reach shaderc under '#version 450 core' undeclared and every program fails.
        LegacyGlsl.LegacyResult legacy = LegacyGlsl.translateLegacyBuiltins(body, isVertex);
        body = legacy.source();

        // Collect #extension lines up front; they must precede any non-preprocessor token and
        // re-emitting them after #version keeps already-expanded (often multi-directive) sources valid.
        Map<String, String> extensions = new LinkedHashMap<>();
        Matcher extensionMatcher = EXTENSION_PATTERN.matcher(body);
        StringBuilder withoutExtensions = new StringBuilder();
        int last = 0;
        while (extensionMatcher.find()) {
            withoutExtensions.append(body, last, extensionMatcher.start());
            String line = extensionMatcher.group().trim();
            Matcher nameMatcher = EXTENSION_NAME_PATTERN.matcher(line);
            if (nameMatcher.find()) {
                extensions.putIfAbsent(nameMatcher.group(1), line);
            }
            last = extensionMatcher.end();
            // Phase 2: consume the extracted line's break too, so removed directives leave no
            // blank lines behind and re-translation reproduces the header exactly.
            if (last < body.length() && body.charAt(last) == '\n') {
                last++;
            }
        }
        withoutExtensions.append(body, last, body.length());
        body = withoutExtensions.toString();

        // Normalize #version to 450 core (keeps the body's compatibility built-ins untouched).
        Matcher versionMatcher = VERSION_PATTERN.matcher(body);
        if (versionMatcher.find()) {
            StringBuilder withVersion = new StringBuilder();
            versionMatcher.appendReplacement(withVersion, Matcher.quoteReplacement("#version 450 core"));
            versionMatcher.appendTail(withVersion);
            body = withVersion.toString();
        } else {
            body = "#version 450 core\n" + body;
        }

        // Rewrite legacy keywords through the token-based pass (Phase 3): only standalone
        // identifier tokens in code regions are converted, so comments, strings and
        // preprocessor directives (macro bodies, version/extension lines) are never touched.
        body = com.vulkairis.translation.passes.LegacyKeywordPass.rewrite(body, isVertex);

        // Translate Iris' prefixed attribute names into the vanilla names VulkanMod's VertexFormat
        // uses, so its GLSLParser can match attributes to format elements by name. This applies to
        // both stages: the vertex stage declares the inputs, the fragment stage usually only
        // disjointly consumes matching varyings, and normalising both keeps names consistent.
        body = normalizeIrisAttributeNames(body);

        // Wrap non-opaque uniforms, but never inject a second block into an already-transformed module.
        StringBuilder blockMembers = new StringBuilder();
        boolean alreadyTransformed = body.contains(UNIFORM_BLOCK_NAME);
        if (!alreadyTransformed) {
            body = extractNonOpaqueUniforms(body, blockMembers);
            // Legacy fixed-function uniforms join the same union block (mirrors
            // ProgramTranslator.requiredMatrixMembers). Without them the rewritten
            // vulkairis_* references stay undeclared and shaderc rejects the module.
            for (String required : ProgramTranslator.requiredMatrixMembers(legacy)) {
                String memberName = memberNameOf(required);
                if (blockMembers.indexOf(memberName) < 0) {
                    blockMembers.append("    ").append(required).append('\n');
                }
            }
            if (legacy.needsFog() && blockMembers.indexOf("vulkairis_Fog") < 0) {
                blockMembers.append("    VulkairisFogParams vulkairis_Fog;\n");
            }
        }

        String header = buildHeader(extensions, blockMembers, alreadyTransformed, body);

        // Legacy stage I/O the header must declare: indexed fragment outputs
        // (gl_FragData[n] / gl_FragColor became vulkairisFragDataN) and the vertex
        // attributes the rewritten body references. Fragment outs need explicit
        // locations even with auto_map_locations; vertex ins are plain declarations.
        if (!alreadyTransformed) {
            if (!isVertex && !legacy.fragDataIndices().isEmpty()) {
                header += LegacyGlsl.fragOutputDeclarations(legacy.fragDataIndices());
            }
            if (isVertex) {
                header += legacyVertexAttribDeclarations(body, legacy);
            }
            if (legacy.needsFog() && !body.contains("VulkairisFogParams")
                    && header.indexOf("struct VulkairisFogParams") < 0) {
                header = "struct VulkairisFogParams {\n"
                        + "    vec4 color;\n"
                        + "    float start;\n"
                        + "    float end;\n"
                        + "    float scale;\n"
                        + "    float density;\n"
                        + "};\n" + header;
            }
        }

        int versionStart = body.indexOf("#version 450 core");
        if (versionStart < 0) {
            // Should not happen, but keep the result valid rather than corrupting the source.
            return "#version 450 core\n" + header + body;
        }
        int lineEnd = body.indexOf('\n', versionStart);

        StringBuilder result = new StringBuilder(body.length() + header.length() + 32);
        if (lineEnd < 0) {
            result.append(body).append('\n');
        } else {
            result.append(body, 0, lineEnd + 1);
        }
        result.append(header);
        if (lineEnd >= 0) {
            result.append(body, lineEnd + 1, body.length());
        }
        String translated = result.toString();
        TranslationCache.store(translationKey, translated);
        com.vulkairis.diagnostics.DiagnosticDumps.dumpTranslatedSource(
                (isVertex ? "vert-" : "frag-")
                        + com.vulkairis.diagnostics.DiagnosticManager.hashSource(rawSource),
                translated);
        return translated;
    }

    /**
     * Rewrites Iris' {@code iris_}-prefixed vertex attribute identifiers to the vanilla attribute
     * names VulkanMod's {@code VertexFormat} exposes, so the two can be matched by name.
     *
     * <p>Mapping (whole identifiers only):</p>
     * <ul>
     *   <li>{@code iris_Position} -&gt; {@code Position}</li>
     *   <li>{@code iris_UV0}/{@code iris_UV1}/{@code iris_UV2} -&gt; {@code UV0}/{@code UV1}/{@code UV2}</li>
     *   <li>{@code iris_Color} -&gt; {@code Color}</li>
     *   <li>{@code iris_Normal} -&gt; {@code Normal}</li>
     *   <li>{@code iris_Entity} -&gt; {@code UV2}</li>
     * </ul>
     *
     * <p>{@code iris_Entity} is Iris' per-vertex entity/lightmap data; Iris packs that payload into
     * the vanilla {@code UV2} attribute slot, so it maps to {@code UV2} here as well. Any other
     * {@code iris_} name is left untouched rather than guessed at, so an unrecognised attribute is
     * never silently corrupted (VulkanMod will report it and the caller can extend the mapping).</p>
     *
     * <p>Matches are standalone tokens: {@code myiris_Position} and {@code object.iris_Position} are
     * both left unchanged. The transform is pure, deterministic and idempotent.</p>
     *
     * @param source GLSL source (any stage); may be {@code null}
     * @return the source with known Iris attribute identifiers normalised, or the input if null
     */
    public static String normalizeIrisAttributeNames(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = IRIS_ATTRIBUTE_PATTERN.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length());
        do {
            String vanilla = vanillaAttributeName(matcher.group(1));
            // The replacer emits plain identifiers, but quote defensively for determinism.
            matcher.appendReplacement(out, Matcher.quoteReplacement(vanilla));
        } while (matcher.find());
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Converts standalone GLSL {@code bool} type tokens to {@code int} <em>inside the injected
     * {@link #UNIFORM_BLOCK_NAME} uniform block and on {@code in}/{@code out} bool
     * varyings</em>, for the VulkanMod-bound path.
     *
     * <p>VulkanMod's {@code PipelineConfig.convertType} only admits {@code matrix4x4}, {@code float}
     * and {@code int} family types; a uniform-block member typed {@code bool} reaches its
     * {@code default} branch and throws {@code RuntimeException("not admitted type: bool")}. Since
     * Vulkairis injects a {@code std140} block that VulkanMod's GLSLParser walks field-by-field,
     * every boolean member must be pre-converted before handing the source over. Bool
     * <em>varyings</em> ({@code in bool}/{@code out bool}, including split leaves such as
     * {@code surface_m0}) are converted the same way so the vertex writer and fragment
     * reader agree on {@code int}.</p>
     *
     * <p>The conversion is deliberately scoped to that block body plus interface
     * {@code in}/{@code out} declarations. A blanket rewrite of every
     * {@code bool} token also rewrote function return types, producing an {@code int} function whose
     * body still returned a {@code bool}-typed expression (for example a vector comparison such as
     * {@code return a == b;}), which Shaderc rejects with
     * {@code 'return' : type does not match, or is not convertible to, the function's return type}.
     * Boolean <em>members</em> and <em>varyings</em> only ever live in those two places, so leaving every other
     * {@code bool} (function signatures, locals, parameters, return types) untouched keeps the rest
     * of the module valid GLSL.</p>
     *
     * <p>Boolean uniforms and varyings are therefore represented as {@code int} (0 = false,
     * non-zero = true). This is semantics-preserving only where the value is consumed as a condition,
     * because GLSL's {@code if}/{@code ?:} conditions accept a scalar {@code int} with the usual
     * non-zero-is-true rule. A {@code true}/{@code false} literal found inside the block is defensively rewritten to
     * {@code 1}/{@code 0} as well, as is a {@code true}/{@code false} literal directly assigned
     * to a converted varying ({@code flag = true;} becomes {@code flag = 1;}) and a logical-not
     * read ({@code !flag} becomes {@code (flag == 0)}); outside those positions the literals are
     * never touched. Complex bool expressions assigned to a converted varying (for example a
     * ternary mixing {@code true}/{@code false} arms, or {@code &&}/{@code ||} over the varying
     * itself) stay compiler-reported: write them as {@code (expr ? 1 : 0)} instead.</p>
     *
     * <p>Only the exact token {@code bool} (and {@code bvec2/3/4} to {@code ivec2/3/4} on
     * varyings) is converted; identifiers such as {@code booleanType} are
     * left alone. The transform is null/empty-safe and idempotent. Kept separate from
     * {@link #preprocessGlsl(String, boolean)} so the Shaderc/test path keeps {@code bool} members
     * and its existing guarantees.</p>
     *
     * @param source GLSL source (typically the injected-block module); may be {@code null}
     * @return the source with the injected block's {@code bool} members and bool
     *         {@code in}/{@code out} varyings converted to {@code int}, or
     *         the input unchanged when there is nothing to convert
     */
    public static String normalizeTypesForVulkanMod(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        String converted = source;
        Matcher header = UNIFORM_BLOCK_HEADER_PATTERN.matcher(converted);
        if (header.find()) {
            int openBrace = header.end() - 1;
            int closeBrace = matchingBrace(converted, openBrace);
            if (closeBrace >= 0) {
                String body = converted.substring(openBrace + 1, closeBrace);
                String rewritten = BOOL_TYPE_PATTERN.matcher(body).replaceAll("int");
                rewritten = BOOL_LITERAL_PATTERN.matcher(rewritten).replaceAll(matchResult ->
                        "true".equals(matchResult.group(1)) ? "1" : "0");
                converted = converted.substring(0, openBrace + 1)
                        + rewritten + converted.substring(closeBrace);
            }
        }
        return normalizeBoolVaryings(converted);
    }

    /**
     * Converts {@code in}/{@code out} bool varyings to {@code flat int}
     * ({@code bvec2/3/4} to {@code flat ivec2/3/4}) where safe, leaving function
     * signatures, locals, parameters and return types untouched.
     *
     * <p>Only top-level interface declarations match (the same line-oriented
     * {@link #INTERFACE_DECL_PATTERN} the location pass uses, so already-qualified
     * {@code layout(...)} lines never match twice and the pass is idempotent).
     * Multi-declarator statements convert every declarator at once. Vulkan requires
     * integer varyings to be {@code flat} (Shaderc rejects a smooth {@code int}
     * varying), so the rewrite adds {@code flat} when absent and replaces a
     * {@code smooth}/{@code noperspective} qualifier, which would be illegal on
     * an integer. After the type swap, direct literal writes to converted scalar
     * varyings become {@code = 1}/{@code = 0}, {@code !name} reads become
     * {@code (name == 0)}, sole {@code if}/{@code while} conditions and ternary
     * or {@code &&}/{@code ||} operands gain an explicit {@code != 0} (GLSL
     * conditions need a real boolean, unlike C), {@code bool x = name} copies
     * become {@code (name != 0)}, and {@code ==}/{@code !=} comparisons against
     * {@code true}/{@code false} are folded to integer comparisons. All rewrites
     * are idempotent and single-{@code =} assignment forms never match
     * {@code ==} comparisons. Arithmetic over a converted varying and passing
     * one to a {@code bool} function parameter stay compiler-reported: keep the
     * value in {@code (name != 0)} form there.</p>
     *
     * @param source GLSL source; may be {@code null}
     * @return the source with bool varyings converted, or the input unchanged
     */
    public static String normalizeBoolVaryings(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = INTERFACE_DECL_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder(source.length() + 32);
        int last = 0;
        boolean changed = false;
        List<String> convertedBools = new ArrayList<>();
        List<String> convertedBvecs = new ArrayList<>();
        while (matcher.find()) {
            String type = matcher.group(6);
            String replacement;
            if ("bool".equals(type)) {
                replacement = "int";
            } else if ("bvec2".equals(type)) {
                replacement = "ivec2";
            } else if ("bvec3".equals(type)) {
                replacement = "ivec3";
            } else if ("bvec4".equals(type)) {
                replacement = "ivec4";
            } else {
                continue;
            }
            for (String declarator : splitDeclarators(matcher.group(8))) {
                String name = declaratorName(declarator);
                if (!name.isEmpty()) {
                    if ("bool".equals(type)) {
                        convertedBools.add(name);
                    } else {
                        convertedBvecs.add(name);
                    }
                }
            }
            String preQualifiers = matcher.group(2);
            String postQualifiers = matcher.group(5);
            if (Pattern.compile("\\bflat\\b").matcher(
                    preQualifiers + postQualifiers).find()) {
                out.append(source, last, matcher.start(6));
                out.append(replacement);
                last = matcher.end(6);
            } else {
                preQualifiers = "flat " + preQualifiers
                        .replaceAll("\\bsmooth[ \\t]+", "")
                        .replaceAll("\\bnoperspective[ \\t]+", "");
                postQualifiers = postQualifiers
                        .replaceAll("\\bsmooth[ \\t]+", "")
                        .replaceAll("\\bnoperspective[ \\t]+", "");
                out.append(source, last, matcher.start());
                out.append(matcher.group(1)).append(preQualifiers).append(matcher.group(3))
                        .append(matcher.group(4)).append(postQualifiers).append(replacement)
                        .append(matcher.group(7)).append(matcher.group(8))
                        .append(matcher.group(9)).append(';').append(matcher.group(10));
                last = matcher.end();
            }
            changed = true;
        }
        String converted = source;
        if (changed) {
            out.append(source, last, source.length());
            converted = out.toString();
        }
        for (String name : convertedBools) {
            String quoted = Pattern.quote(name);
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*==\\s*true(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement("(" + name + " != 0)"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*==\\s*false(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement("(" + name + " == 0)"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*!=\\s*true(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement("(" + name + " == 0)"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*!=\\s*false(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement("(" + name + " != 0)"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*=\\s*true(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement(name + " = 1"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*=\\s*false(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement(name + " = 0"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])!(\\s*)" + quoted + "(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement("(" + name + " == 0)"));
            converted = Pattern.compile(
                    "\\bbool\\s+([A-Za-z_]\\w*)\\s*=\\s*" + quoted + "\\s*;")
                    .matcher(converted).replaceAll("bool $1 = (" + name + " != 0);");
            converted = Pattern.compile(
                    "\\b(if|while)\\s*\\(\\s*" + quoted + "\\s*\\)")
                    .matcher(converted).replaceAll("$1 (" + name + " != 0)");
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*\\?")
                    .matcher(converted).replaceAll(
                            Matcher.quoteReplacement("(" + name + " != 0) ?"));
            converted = Pattern.compile(
                    "(?<![A-Za-z0-9_])" + quoted + "\\s*(&&|\\|\\|)")
                    .matcher(converted).replaceAll("(" + name + " != 0) $1");
            converted = Pattern.compile(
                    "(&&|\\|\\|)\\s*" + quoted + "(?![A-Za-z0-9_])")
                    .matcher(converted).replaceAll("$1 (" + name + " != 0)");
        }
        return converted;
    }

    /** Returns the index of the {@code '}'} matching the {@code '{'} at {@code openBrace}, or -1. */
    private static int matchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Interpolation / storage / precision qualifiers that may legally appear around a top-level
     * {@code in}/{@code out} declaration. Recognising them lets the layout-injection pass see the
     * actual direction keyword in forms such as {@code flat out vec3 n;} and
     * {@code centroid in vec2 uv;} instead of mistaking a qualifier for the type.
     */
    private static final String INTERFACE_QUALIFIER =
            "(?:flat|smooth|noperspective|centroid|sample|invariant|precise|patch|highp|mediump|lowp)";

    /**
     * A single top-level {@code in}/{@code out} declaration, matched line-orientedly, including
     * comma-separated multi-declarator statements such as
     * {@code flat out vec3 upVec, sunVec, eastVec;}.
     *
     * <p>Capture groups: {@code 1} = leading indentation, {@code 2} = qualifiers before the
     * direction (with their trailing whitespace; possibly empty), {@code 3} = direction
     * ({@code in}/{@code out}), {@code 4} = whitespace after the direction, {@code 5} = qualifiers
     * between the direction and the type (possibly empty), {@code 6} = type, {@code 7} = whitespace
     * after the type, {@code 8} = declarator list (each declarator an identifier plus an optional
     * array specifier, comma-separated), {@code 9} = whitespace before the semicolon,
     * {@code 10} = trailing text after the semicolon (for example a comment).</p>
     *
     * <p>Deliberate limits (a line-oriented regex pass, by design): it does not strip comments or
     * track brace nesting. It relies on the GLSL rule that {@code in}/{@code out} are only legal at
     * global scope as interface declarations, so a declaration is never function-local. A statement
     * carrying an initializer does not match and is therefore left untouched rather than corrupted.
     * Lines beginning with {@code layout} (already qualified) also do not match, which makes the pass
     * idempotent.</p>
     */
    private static final Pattern INTERFACE_DECL_PATTERN = Pattern.compile(
            "(?m)^([ \\t]*)"
                    + "((?:" + INTERFACE_QUALIFIER + "[ \\t]+)*)"
                    + "(in|out)"
                    + "([ \\t]+)"
                    + "((?:" + INTERFACE_QUALIFIER + "[ \\t]+)*)"
                    + "([A-Za-z_]\\w*)"
                    + "([ \\t]+)"
                    + "([A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?"
                        + "(?:[ \\t]*,[ \\t]*[A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?)*)"
                    + "([ \\t]*);"
                    + "([^\\r\\n]*)");

    /**
     * Injects an explicit {@code layout(location = N)} qualifier before every un-qualified,
     * top-level user {@code in}/{@code out} declaration in {@code source}.
     *
     * <p>VulkanMod compiles the raw GLSL string handed to
     * {@code Pipeline.Builder.setShaderSrc} directly with Shaderc, which rejects any user interface
     * variable that has no explicit location ({@code error: 'location' : SPIR-V requires location
     * for user input/output}). Iris instead relies on {@code glBindAttribLocation}, so its sources
     * ship bare declarations. This method supplies the missing locations for the VulkanMod path.</p>
     *
     * <ul>
     *   <li><b>Vertex inputs</b> ({@code in} when {@code isVertex}) take their location from
     *       {@code vertexInputLocations} by declared name. A name that is absent from the map is
     *       left untouched &mdash; no location is invented &mdash; so the caller owns coverage and
     *       can detect and reject an uncovered input.</li>
     *   <li><b>Varyings</b> ({@code out} when {@code isVertex}, {@code in} when fragment) take their
     *       location from {@code varyingLocations} by declared name, so the vertex output and
     *       matching fragment input always agree.</li>
     *   <li><b>Fragment outputs</b> ({@code out} when not {@code isVertex}) are numbered
     *       {@code 0,1,2,...} in declaration order, independently of the maps.</li>
     * </ul>
     *
     * <p>Declarations that already carry a {@code layout(...)} qualifier are never touched, which
     * makes the transform idempotent. Qualifiers between the direction keyword and the type
     * ({@code flat}, {@code smooth}, {@code centroid}, {@code highp}, ...) are preserved, and the
     * declared name of an arrayed varying is the identifier before {@code [}. A comma-separated
     * multi-declarator statement such as {@code flat out vec3 upVec, sunVec, eastVec;} is split into
     * one located declaration per name, preserving the direction, type, qualifiers and each
     * declarator's own array specifier. If any name in such a statement has no known location the
     * whole statement is left untouched (no location is invented and no partial split is emitted),
     * matching the single-declarator policy. Splitting is only done across commas at bracket-and-
     * parenthesis depth zero. See {@link #INTERFACE_DECL_PATTERN} for the documented limits of the
     * line-oriented pass.</p>
     *
     * @param source                 GLSL source (any stage); may be {@code null}/blank
     * @param isVertex               whether {@code source} is the vertex stage
     * @param vertexInputLocations   vertex {@code in} name -&gt; location; may be {@code null}
     * @param varyingLocations       varying name -&gt; location shared across both stages; may be {@code null}
     * @return the source with locations injected where a location was known, or the input unchanged
     */
    public static String injectLayoutLocations(String source, boolean isVertex,
                                               Map<String, Integer> vertexInputLocations,
                                               Map<String, Integer> varyingLocations) {
        if (source == null || source.isEmpty()) {
            return source;
        }

        Matcher matcher = INTERFACE_DECL_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder(source.length() + 64);
        int last = 0;
        int fragmentOutputLocation = 0;
        while (matcher.find()) {
            String indent = matcher.group(1);
            String direction = matcher.group(3);
            String type = matcher.group(6);
            List<String> declarators = splitDeclarators(matcher.group(8));
            List<String> names = new ArrayList<>(declarators.size());
            for (String declarator : declarators) {
                names.add(declaratorName(declarator));
            }

            if (declarators.size() > 1) {
                // A multi-declarator statement cannot keep one shared prefix: rewrite it into one
                // located declaration per name, preserving direction, type, qualifiers and arrays.
                List<Integer> locations = new ArrayList<>(declarators.size());
                boolean allKnown = true;
                for (int i = 0; i < declarators.size(); i++) {
                    String name = names.get(i);
                    Integer location = resolveInterfaceLocation(direction, name, isVertex,
                            vertexInputLocations, varyingLocations, fragmentOutputLocation);
                    if (location == null) {
                        allKnown = false;
                        break;
                    }
                    locations.add(location);
                    if (isFragmentOutput(direction, isVertex)) {
                        fragmentOutputLocation += computeLocationSlots(type, declarators.get(i));
                    }
                }
                if (!allKnown) {
                    // At least one declarator has no known location: leave the whole statement
                    // untouched rather than inventing a location or emitting a partial split.
                    continue;
                }

                out.append(source, last, matcher.start());
                appendSplitDeclarations(out, indent, matcher, declarators, locations);
                last = matcher.end();
                continue;
            }

            String name = names.get(0);
            Integer location = resolveInterfaceLocation(direction, name, isVertex,
                    vertexInputLocations, varyingLocations, fragmentOutputLocation);
            if (location == null) {
                // Leave the declaration untouched; the caller decides coverage.
                continue;
            }
            if (isFragmentOutput(direction, isVertex)) {
                fragmentOutputLocation += computeLocationSlots(type, matcher.group(8));
            }

            out.append(source, last, matcher.start());
            out.append(indent)
                    .append("layout(location = ").append(location).append(") ")
                    .append(source, matcher.start() + indent.length(), matcher.end());
            last = matcher.end();
        }

        if (last == 0) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /** Resolves the location for one interface declarator, or {@code null} when it is unknown. */
    private static Integer resolveInterfaceLocation(String direction, String name, boolean isVertex,
                                                    Map<String, Integer> vertexInputLocations,
                                                    Map<String, Integer> varyingLocations,
                                                    int fragmentOutputLocation) {
        if ("in".equals(direction)) {
            return isVertex
                    ? lookupLocation(vertexInputLocations, name)
                    : lookupLocation(varyingLocations, name);
        }
        if (isVertex) {
            return lookupLocation(varyingLocations, name);
        }
        // Fragment colour outputs have no cross-stage identity; number them in order.
        return fragmentOutputLocation;
    }

    /** Whether the declaration is a fragment-stage colour output ({@code out}, non-vertex). */
    private static boolean isFragmentOutput(String direction, boolean isVertex) {
        return !"in".equals(direction) && !isVertex;
    }

    /**
     * Emits one {@code layout(location = N)} declaration per declarator of a multi-declarator
     * interface statement, preserving indentation, direction, qualifiers, type and each declarator's
     * array specifier. The statement's trailing text is kept on the last emitted declaration only.
     */
    private static void appendSplitDeclarations(StringBuilder out, String indent, Matcher matcher,
                                                List<String> declarators, List<Integer> locations) {
        String preQualifiers = matcher.group(2);
        String direction = matcher.group(3);
        String afterDirection = matcher.group(4);
        String postQualifiers = matcher.group(5);
        String type = matcher.group(6);
        String afterType = matcher.group(7);
        String beforeSemicolon = matcher.group(9);
        String trailing = matcher.group(10);

        for (int i = 0; i < declarators.size(); i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(indent)
                    .append("layout(location = ").append(locations.get(i)).append(") ")
                    .append(preQualifiers).append(direction).append(afterDirection)
                    .append(postQualifiers).append(type).append(afterType)
                    .append(declarators.get(i).trim())
                    .append(beforeSemicolon).append(';');
            if (i == declarators.size() - 1) {
                out.append(trailing);
            }
        }
    }

    /**
     * Splits a declarator list on top-level commas, ignoring commas nested inside {@code [...]} or
     * parentheses. The returned parts are trimmed by their consumers.
     */
    private static List<String> splitDeclarators(String value) {
        List<String> parts = new ArrayList<>();
        int bracketDepth = 0;
        int parenDepth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[') {
                bracketDepth++;
            } else if (c == ']') {
                bracketDepth = Math.max(0, bracketDepth - 1);
            } else if (c == '(') {
                parenDepth++;
            } else if (c == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (c == ',' && bracketDepth == 0 && parenDepth == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    /** The declared identifier of a single declarator, stripping any array specifier. */
    private static String declaratorName(String declarator) {
        String trimmed = declarator.trim();
        int bracket = trimmed.indexOf('[');
        return bracket < 0 ? trimmed : trimmed.substring(0, bracket).trim();
    }

    /**
     * Collects the names of top-level interface declarations in {@code source} with the given
     * direction ({@code "in"} or {@code "out"}), in declaration order and de-duplicated. Every
     * declarator of a multi-declarator statement is included, so
     * {@code flat out vec3 upVec, sunVec, eastVec;} contributes {@code upVec}, {@code sunVec} and
     * {@code eastVec}. Used to derive the required vertex inputs and the shared varying interface
     * before {@link #injectLayoutLocations}.
     *
     * @param source    GLSL source (any stage); may be {@code null}
     * @param direction {@code "in"} or {@code "out"}; any other value yields an empty list
     * @return declared names in source order; empty for {@code null}/blank input
     */
    public static List<String> collectInterfaceNames(String source, String direction) {
        List<String> names = new ArrayList<>();
        if (source == null || source.isEmpty() || direction == null) {
            return names;
        }
        Matcher matcher = INTERFACE_DECL_PATTERN.matcher(source);
        while (matcher.find()) {
            if (!direction.equals(matcher.group(3))) {
                continue;
            }
            for (String declarator : splitDeclarators(matcher.group(8))) {
                String name = declaratorName(declarator);
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /**
     * Computes the number of SPIR-V layout location slots consumed by a declaration of the given
     * GLSL type and declarator string.
     *
     * <p>In SPIR-V / GLSL 4.50, vectors/scalars consume 1 location slot, matrices consume 1 slot per
     * column, and arrayed declarations multiply the slot count by the array element count.</p>
     */
    public static int computeLocationSlots(String type, String declarator) {
        int baseSlots = switch (type) {
            case "mat2", "dmat2" -> 2;
            case "mat3", "dmat3" -> 3;
            case "mat4", "dmat4" -> 4;
            default -> 1;
        };
        int arrayMultiplier = 1;
        int openBracket = declarator.indexOf('[');
        int closeBracket = declarator.indexOf(']');
        if (openBracket >= 0 && closeBracket > openBracket) {
            String sizeStr = declarator.substring(openBracket + 1, closeBracket).trim();
            try {
                arrayMultiplier = Math.max(1, Integer.parseInt(sizeStr));
            } catch (NumberFormatException ignored) {
            }
        }
        return baseSlots * arrayMultiplier;
    }

    /**
     * Collects top-level interface declarations in {@code source} for the given direction ({@code "in"}
     * or {@code "out"}), mapping each declarator name to the number of location slots it consumes.
     */
    public static Map<String, Integer> collectInterfaceSlots(String source, String direction) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        if (source == null || source.isEmpty() || direction == null) {
            return slots;
        }
        Matcher matcher = INTERFACE_DECL_PATTERN.matcher(source);
        while (matcher.find()) {
            if (!direction.equals(matcher.group(3))) {
                continue;
            }
            String type = matcher.group(6);
            for (String declarator : splitDeclarators(matcher.group(8))) {
                String name = declaratorName(declarator);
                if (!name.isEmpty() && !slots.containsKey(name)) {
                    slots.put(name, computeLocationSlots(type, declarator));
                }
            }
        }
        return slots;
    }

    /** Names of the vertex stage's top-level {@code in} declarations, in declaration order. */
    public static List<String> collectVertexInputNames(String source) {
        return collectInterfaceNames(source, "in");
    }

    private static Integer lookupLocation(Map<String, Integer> locations, String name) {
        return locations == null ? null : locations.get(name);
    }

    /**
     * The result of {@link #remapDescriptorBindings(String)}: the rewritten source together with the
     * deterministic dense assignment that was applied.
     *
     * @param source       the rewritten GLSL source
     * @param orderedNames descriptor names in assignment order (index == dense binding)
     * @param bindings     immutable {@code name -> dense binding} map (values are exactly {@code 0..N-1})
     */
    public record BindingRemap(String source, List<String> orderedNames, Map<String, Integer> bindings) {
    }

    /**
     * Collects the descriptor names in {@code source}, in a deterministic order suitable for dense
     * rebinding: the injected {@link #UNIFORM_BLOCK_NAME} first (when present), then every other
     * descriptor in source declaration order.
     *
     * <p>Three shapes are recognised: interface blocks that carry a {@code layout(binding = N)}
     * qualifier ({@code uniform Name { ... }}, {@code buffer Name { ... }}), opaque sampler/image
     * declarations that already carry one, and <em>bare</em> opaque declarations such as
     * {@code uniform sampler2D depth;} that carry no qualifier at all (Iris ships those, and Shaderc
     * rejects them until a binding is injected). Array suffixes are stripped, so {@code tex[4]}
     * yields {@code tex}. A name that occurs more than once is reported once.</p>
     *
     * @param source GLSL source (any stage); may be {@code null}
     * @return an ordered, de-duplicated list of descriptor names; empty for {@code null}/blank input
     */
    public static List<String> collectDescriptorNames(String source) {
        List<String> names = new ArrayList<>();
        if (source == null || source.isEmpty()) {
            return names;
        }

        // Descriptors are reported in source order. Two independent shapes are recognised: interface
        // blocks / opaque declarations that already carry a binding qualifier, and opaque sampler/
        // image uniforms that are still bare (Iris ships these without a layout, and they must be
        // discovered so the VulkanMod path can inject a binding for them). A name already seen is not
        // re-added, so an opaque declaration that also matched the layout pass is reported once.
        Set<String> seen = new HashSet<>();
        TreeMap<Integer, String> byPosition = new TreeMap<>();

        Matcher layout = LAYOUT_QUALIFIER_PATTERN.matcher(source);
        while (layout.find()) {
            String name = descriptorNameAfter(source, layout.end());
            if (name == null) {
                continue;
            }
            boolean hasBinding = BINDING_QUALIFIER_PATTERN.matcher(layout.group(1)).find();
            boolean isBlock = BLOCK_AFTER_LAYOUT_PATTERN.matcher(source.substring(layout.end())).lookingAt();
            if ((hasBinding || isBlock) && seen.add(name)) {
                byPosition.put(layout.start(), name);
            }
        }

        Matcher opaque = OPAQUE_UNIFORM_DECL_PATTERN.matcher(source);
        while (opaque.find()) {
            String name = opaque.group(9);
            if (seen.add(name)) {
                byPosition.put(opaque.start(), name);
            }
        }

        names.addAll(byPosition.values());

        // The injected block is emitted in the header and therefore precedes everything else, but
        // force it to the front explicitly so the ordering contract does not depend on layout.
        int blockIndex = names.indexOf(UNIFORM_BLOCK_NAME);
        if (blockIndex > 0) {
            names.remove(blockIndex);
            names.add(0, UNIFORM_BLOCK_NAME);
        }
        return names;
    }

    /**
     * Number of descriptors in {@code source}, counting bound interface blocks, bound opaque
     * declarations, and bare opaque declarations alike (see {@link #collectDescriptorNames(String)}).
     */
    public static int countDescriptorBindings(String source) {
        return collectDescriptorNames(source).size();
    }

    /**
     * Collects the descriptor names in {@code source} together with the raw {@code binding = N} value
     * declared for each, in source declaration order. Where a name is declared more than once the
     * first binding wins. Unlike {@link #collectDescriptorNames(String)} the injected block is not
     * hoisted to the front, because callers use this only to look up the pre-remap binding of a
     * descriptor (for example a named interface-block instance whose reflected variable name differs
     * from the GLSL block type name).
     *
     * @param source GLSL source (any stage); may be {@code null}
     * @return an ordered {@code name -> raw binding} map; empty for {@code null}/blank input
     */
    public static Map<String, Integer> collectDescriptorBindings(String source) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return bindings;
        }
        Matcher layout = LAYOUT_QUALIFIER_PATTERN.matcher(source);
        while (layout.find()) {
            Matcher bindingMatcher = BINDING_QUALIFIER_PATTERN.matcher(layout.group(1));
            if (!bindingMatcher.find()) {
                continue;
            }
            String name = descriptorNameAfter(source, layout.end());
            if (name == null) {
                continue;
            }
            bindings.putIfAbsent(name, Integer.parseInt(bindingMatcher.group(1)));
        }
        return bindings;
    }

    /**
     * Builds one unified, deterministic descriptor assignment from the descriptors actually declared
     * in the given stage sources, in order. Order is: the injected {@link #UNIFORM_BLOCK_NAME}
     * first (if any stage declares it), then every other descriptor in first-seen declaration order
     * (vertex stage before fragment stage). Values are exactly {@code 0..N-1}.
     *
     * <p>This is the single shared dense-binding contract (§4.2): the VulkanMod driver path
     * ({@code VulkanModProgramBridge}) and every test derive their cross-stage assignment from
     * this helper, so both stages always agree on bindings. VulkanMod's
     * {@code Pipeline.createDescriptorSetLayout} indexes its binding array by the raw binding
     * number, so only a dense {@code 0..N-1} assignment is Vulkan-legal here.
     *
     * <p>Never returns {@code null}: empty input simply yields an empty map.</p>
     *
     * @param stageSourcesInOrder stage sources in link order (vertex first, then fragment);
     *        {@code null} entries are skipped, a {@code null} list yields an empty map
     * @return an ordered {@code name -> dense binding} map with values exactly {@code 0..N-1}
     */
    public static Map<String, Integer> buildUnifiedBindingMap(List<String> stageSourcesInOrder) {
        Map<String, Integer> bindings = new LinkedHashMap<>();
        if (stageSourcesInOrder == null) {
            return bindings;
        }
        // The injected block is one shared descriptor across both stages; pin it to binding 0 first
        // regardless of which stage (or both) declares it, so the unified order matches the
        // single-stage remap contract.
        for (String source : stageSourcesInOrder) {
            if (source != null && collectDescriptorNames(source).contains(UNIFORM_BLOCK_NAME)) {
                bindings.put(UNIFORM_BLOCK_NAME, 0);
                break;
            }
        }
        for (String source : stageSourcesInOrder) {
            if (source == null) {
                continue;
            }
            for (String name : collectDescriptorNames(source)) {
                if (!bindings.containsKey(name)) {
                    bindings.put(name, bindings.size());
                }
            }
        }
        return bindings;
    }

    /**
     * Two-stage convenience form of {@link #buildUnifiedBindingMap(List)}: vertex source first,
     * then fragment source. {@code null} stages are treated as empty.
     *
     * @param vertSrc vertex-stage GLSL; may be {@code null}
     * @param fragSrc fragment-stage GLSL; may be {@code null}
     * @return an ordered {@code name -> dense binding} map with values exactly {@code 0..N-1}
     */
    public static Map<String, Integer> buildUnifiedBindingMap(String vertSrc, String fragSrc) {
        return buildUnifiedBindingMap(List.of(vertSrc != null ? vertSrc : "", fragSrc != null ? fragSrc : ""));
    }

    /**
     * Rewrites every descriptor {@code layout(binding = N)} qualifier in {@code source} to a dense,
     * deterministic, 0-based index: the injected {@link #UNIFORM_BLOCK_NAME} first, then the other
     * descriptors in declaration order. This is the single-stage convenience form; callers that must
     * keep bindings consistent across two stages should build one shared map (for example from
     * {@link #collectDescriptorNames} over both stages) and use
     * {@link #remapDescriptorBindings(String, Map)}.
     *
     * @param source GLSL source; may be {@code null}
     * @return the rewritten source plus the ordered names and {@code name -> binding} map; for
     *         {@code null}/blank input the source is returned unchanged with empty metadata
     */
    public static BindingRemap remapDescriptorBindings(String source) {
        List<String> names = collectDescriptorNames(source);
        Map<String, Integer> bindings = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            bindings.put(names.get(i), i);
        }
        String rewritten = remapDescriptorBindings(source, bindings);
        return new BindingRemap(rewritten, List.copyOf(names), Map.copyOf(bindings));
    }

    /**
     * Ensures every opaque ({@code sampler*}/{@code image*}) uniform declaration in {@code source}
     * carries an explicit {@code layout(binding = N)} taken from {@code bindings} by base name
     * (so {@code tex[4]} looks up {@code tex}).
     *
     * <p>SPIR-V/Shaderc requires an explicit binding on sampler/texture/image uniforms, but Iris
     * sources declare them bare and rely on auto-binding. This method bridges that gap on the
     * VulkanMod path, whose {@code Pipeline.Builder} compiles the raw source without
     * {@code shaderc_compile_options_set_auto_bind_uniforms}:</p>
     *
     * <ul>
     *   <li>No {@code layout} qualifier &mdash; {@code layout(binding = N) } is prepended.</li>
     *   <li>{@code layout(...)} without a binding &mdash; {@code binding = N} is appended inside the
     *       existing parentheses, preserving every other qualifier ({@code set}, ...).</li>
     *   <li>{@code layout(...)} with a binding &mdash; only the binding value is replaced, preserving
     *       the surrounding whitespace and qualifiers.</li>
     * </ul>
     *
     * <p>Non-opaque uniforms are never touched (they live in the injected uniform block), array
     * declarators and precision/memory qualifiers are preserved verbatim, and the transform is
     * idempotent. Declarations whose name is absent from {@code bindings} are left untouched.</p>
     *
     * @param source   GLSL source (any stage); may be {@code null}
     * @param bindings explicit {@code descriptor base name -> binding}; may be {@code null}/empty
     * @return the source with opaque bindings ensured (unchanged when there is nothing to do)
     */
    public static String ensureDescriptorBindings(String source, Map<String, Integer> bindings) {
        if (source == null || source.isEmpty() || bindings == null || bindings.isEmpty()) {
            return source;
        }

        Matcher matcher = OPAQUE_UNIFORM_DECL_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder(source.length() + 32);
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            String name = matcher.group(9);
            Integer dense = bindings.get(name);
            if (dense == null) {
                continue;
            }
            out.append(source, last, matcher.start());

            String layoutContents = matcher.group(3);
            if (layoutContents != null) {
                // An existing layout list: rewrite or add binding inside it, keeping its whitespace.
                out.append(matcher.group(1))
                        .append("layout").append(matcher.group(2))
                        .append('(').append(rewriteBindingInLayout(layoutContents, dense)).append(')')
                        .append(matcher.group(4));
            } else {
                // A bare declaration: supply a fresh layout qualifier in front of it.
                out.append(matcher.group(1))
                        .append("layout(binding = ").append(dense).append(") ");
            }
            out.append(matcher.group(5))
                    .append(matcher.group(6))
                    .append(matcher.group(7))
                    .append(matcher.group(8))
                    .append(matcher.group(9));
            if (matcher.group(10) != null) {
                out.append(matcher.group(10));
            }
            out.append(matcher.group(11));

            last = matcher.end();
            changed = true;
        }

        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /** Replaces the {@code binding = N} value inside a layout list, or appends one when absent. */
    private static String rewriteBindingInLayout(String contents, int dense) {
        Matcher binding = BINDING_QUALIFIER_PATTERN.matcher(contents);
        if (binding.find()) {
            return contents.substring(0, binding.start()) + "binding = " + dense
                    + contents.substring(binding.end());
        }
        String trimmed = contents.stripTrailing();
        return trimmed.isEmpty() ? "binding = " + dense : trimmed + ", binding = " + dense;
    }

    /**
     * Rewrites descriptor {@code layout(binding = N)} qualifiers in {@code source} using an explicit
     * {@code name -> binding} map. Opaque sampler/image uniforms without a binding are first given one
     * by {@link #ensureDescriptorBindings} (Iris ships them bare, and Shaderc rejects a bare sampler),
     * then every bound descriptor is rewritten to the mapped value. Only descriptors whose name
     * appears in the map are touched, so a caller that shares one map across both stages gets
     * identical bindings in each. Non-binding qualifiers (for example {@code location}) and all
     * declaration text other than the binding token are preserved.
     *
     * @param source   GLSL source (any stage); may be {@code null}
     * @param bindings explicit {@code descriptor name -> binding}; may be {@code null}/empty
     * @return the rewritten source (unchanged when there is nothing to remap)
     */
    public static String remapDescriptorBindings(String source, Map<String, Integer> bindings) {
        if (source == null || source.isEmpty() || bindings == null || bindings.isEmpty()) {
            return source;
        }

        // Inject a binding into bare opaque declarations first, so the rewrite loop below (and the
        // compiled source) sees a complete set of bound descriptors.
        source = ensureDescriptorBindings(source, bindings);

        Matcher layout = LAYOUT_QUALIFIER_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        while (layout.find()) {
            String name = descriptorNameAfter(source, layout.end());
            if (name == null) {
                continue;
            }
            Integer dense = bindings.get(name);
            if (dense == null) {
                continue;
            }

            Matcher bindingMatcher = BINDING_QUALIFIER_PATTERN.matcher(layout.group(1));
            if (bindingMatcher.find()) {
                // Rewrite only the binding token, using absolute offsets so the surrounding qualifier
                // list (std140, set, spacing, ...) and the rest of the declaration stay untouched.
                int bindingStart = layout.start(1) + bindingMatcher.start();
                int bindingEnd = layout.start(1) + bindingMatcher.end();
                out.append(source, last, bindingStart);
                out.append("binding = ").append(dense);
                last = bindingEnd;
            } else {
                out.append(source, last, layout.start(1));
                out.append(rewriteBindingInLayout(layout.group(1), dense));
                last = layout.end(1);
            }
        }
        if (last == 0) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    /**
     * Returns the descriptor name introduced immediately after a {@code layout(...)} qualifier, or
     * {@code null} if the following declaration is not a bound descriptor. Handles interface blocks
     * ({@code uniform}/{@code buffer} + identifier + {@code {}) and opaque sampler/image declarations.
     */
    private static String descriptorNameAfter(String source, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= source.length()) {
            return null;
        }
        String tail = source.substring(fromIndex);

        Matcher block = BLOCK_AFTER_LAYOUT_PATTERN.matcher(tail);
        if (block.lookingAt()) {
            return block.group(1);
        }
        Matcher opaque = OPAQUE_AFTER_LAYOUT_PATTERN.matcher(tail);
        if (opaque.lookingAt()) {
            return opaque.group(1);
        }
        return null;
    }

    /** Maps a captured {@code iris_} suffix to its vanilla attribute name. */
    private static String vanillaAttributeName(String irisSuffix) {
        return switch (irisSuffix) {
            case "Position" -> "Position";
            case "UV0", "UV" -> "UV0";
            case "UV1" -> "UV1";
            case "UV2", "Entity" -> "UV2";
            case "Color" -> "Color";
            case "Normal" -> "Normal";
            default -> "iris_" + irisSuffix;
        };
    }

    /**
     * Moves every non-opaque uniform into {@code blockMembers} as a plain {@code type name;}
     * member, replacing the original declaration with a comment. Initializers are dropped
     * (illegal inside a block), arrays are preserved and multi-declarator statements are split.
     * Opaque (sampler/image) uniforms and anything the parser does not recognise are left alone.
     */
    private static String extractNonOpaqueUniforms(String source, StringBuilder blockMembers) {
        Matcher matcher = UNIFORM_DECL_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String indent = matcher.group(1);
            String type = matcher.group(2);
            String declarators = matcher.group(3).trim();

            if (!NON_OPAQUE_TYPE_PATTERN.matcher(type).matches()) {
                // Leave opaque/unknown declarations untouched. Not calling appendReplacement keeps
                // this match verbatim in the output.
                continue;
            }

            List<String> keptNames = new ArrayList<>();
            StringBuilder members = new StringBuilder();
            for (String rawDeclarator : splitTopLevel(declarators, ',')) {
                String declarator = rawDeclarator.trim();
                int initializer = topLevelIndexOf(declarator, '=');
                if (initializer >= 0) {
                    declarator = declarator.substring(0, initializer).trim();
                }
                if (declarator.isEmpty() || !DECLARATOR_PATTERN.matcher(declarator).matches()) {
                    continue;
                }
                members.append("    ").append(type).append(' ').append(declarator).append(";\n");
                keptNames.add(declarator);
            }

            if (keptNames.isEmpty()) {
                continue;
            }

            blockMembers.append(members);
            String comment = indent + "// uniform " + type + " "
                    + String.join(", ", keptNames).replaceAll("\\s+", " ") + ";";
            matcher.appendReplacement(out, Matcher.quoteReplacement(comment));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Declared identifier of a {@code type declarator;} member line, array stripped. */
    private static String memberNameOf(String memberLine) {
        String trimmed = memberLine.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        int space = trimmed.lastIndexOf(' ');
        String declarator = space >= 0 ? trimmed.substring(space + 1).trim() : trimmed;
        int bracket = declarator.indexOf('[');
        return bracket < 0 ? declarator : declarator.substring(0, bracket).trim();
    }

    /** Vertex-stage {@code in} declarations for every legacy attribute the body references. */
    private static String legacyVertexAttribDeclarations(
            String body, LegacyGlsl.LegacyResult legacy) {
        StringBuilder declarations = new StringBuilder();
        if (legacy.needsVertexAttrib() && !hasVertexInput(body, "vulkairis_Vertex")) {
            declarations.append("in vec4 vulkairis_Vertex;\n");
        }
        if (legacy.needsNormalAttrib() && !hasVertexInput(body, "vulkairis_Normal")) {
            declarations.append("in vec3 vulkairis_Normal;\n");
        }
        if (legacy.needsColorAttrib() && !hasVertexInput(body, "vulkairis_Color")) {
            declarations.append("in vec4 vulkairis_Color;\n");
        }
        if (legacy.needsTexCoord0() && !hasVertexInput(body, "vulkairis_MultiTexCoord0")) {
            declarations.append("in vec4 vulkairis_MultiTexCoord0;\n");
        }
        if (legacy.needsTexCoord1() && !hasVertexInput(body, "vulkairis_MultiTexCoord1")) {
            declarations.append("in vec4 vulkairis_MultiTexCoord1;\n");
        }
        return declarations.toString();
    }

    private static final java.util.regex.Pattern VERTEX_INPUT_DECL = java.util.regex.Pattern.compile(
            "(?m)^[ \\t]*in[ \\t]+[A-Za-z_]\\w*[ \\t]+([A-Za-z_]\\w*)");

    private static boolean hasVertexInput(String body, String name) {
        if (body == null || name == null) {
            return false;
        }
        java.util.regex.Matcher matcher = VERTEX_INPUT_DECL.matcher(body);
        while (matcher.find()) {
            if (name.equals(matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    private static String buildHeader(Map<String, String> extensions, StringBuilder blockMembers,
                                      boolean alreadyTransformed, String body) {
        Map<String, String> emitted = new LinkedHashMap<>(extensions);
        emitted.putIfAbsent("GL_ARB_separate_shader_objects",
                "#extension GL_ARB_separate_shader_objects : enable");
        emitted.putIfAbsent("GL_ARB_shading_language_420pack",
                "#extension GL_ARB_shading_language_420pack : enable");

        StringBuilder header = new StringBuilder();
        for (String extension : emitted.values()) {
            header.append(extension).append('\n');
        }
        // Phase 2: emit each precision line only when the body does not already carry it.
        // This keeps first-pass output free of duplicates and makes re-translation stable
        // whether or not the source received a uniform block.
        if (body == null || !body.contains("precision highp float;")) {
            header.append("precision highp float;\n");
        }
        if (body == null || !body.contains("precision highp int;")) {
            header.append("precision highp int;\n");
        }

        if (!alreadyTransformed && blockMembers.length() > 0) {
            header.append("layout(std140, binding = ").append(UNIFORM_BLOCK_BINDING)
                    .append(") uniform ").append(UNIFORM_BLOCK_NAME).append(" {\n")
                    .append(blockMembers)
                    .append("};\n");
        }
        return header.toString();
    }

    /** Splits on {@code delimiter} while ignoring occurrences nested inside {@code [...]}. */
    private static List<String> splitTopLevel(String value, char delimiter) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == delimiter && depth == 0) {
                parts.add(value.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(value.substring(start));
        return parts;
    }

    /** Finds the first {@code target} at bracket depth zero, or -1. */
    private static int topLevelIndexOf(String value, char target) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == target && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
