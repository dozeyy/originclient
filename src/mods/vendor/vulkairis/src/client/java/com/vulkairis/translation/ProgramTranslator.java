package com.vulkairis.translation;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.translation.passes.LegacyKeywordPass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase B: program-together translator (one program, not one stage at a time).
 *
 * <p>{@link ShaderTranslator#preprocessGlsl} translates each stage in isolation and numbers
 * bindings per file, so two stages can claim the same binding or location and the driver is
 * left to arbitrate. This translator instead runs a <em>union pass</em> over every stage of a
 * program first — one shared {@code VulkairisUniformBlock} (the {@code OfGlobals} equivalent)
 * plus one union varying set — and only then rewrites each stage against that fixed union.</p>
 *
 * <p>Binding policy (the Vitrail rule, reimplemented): the output carries
 * <strong>no {@code layout(binding =)} and no {@code layout(location =)} except on fragment
 * {@code out} declarations</strong>. Descriptor bindings and interface locations are assigned
 * later from SPIR-V reflection ({@link SpirvReflector} plus the descriptor-set builder), never
 * guessed per file here. Fragment outputs are the exception because Shaderc still requires an
 * explicit location for them even with {@code auto_map_locations}; they are numbered densely
 * ({@code gl_FragData[n]} keeps its index, every other output takes the next free slot).</p>
 *
 * <p>Kept from the per-stage path: the {@code bool}-to-{@code int} normalization covers
 * the injected block <em>and</em> {@code in}/{@code out} bool varyings (function
 * signatures intentionally untouched) via
 * {@link ShaderTranslator#normalizeTypesForVulkanMod}, and the {@code iris_}
 * attribute handling runs on every stage and therefore covers the union varying
 * set as well.</p>
 *
 * <p>Varying split (plan §4 remainder): matrix/array/struct varyings are scalarized
 * end-to-end (for example {@code mat4 m} to {@code m_c0..c3}) with rank==location
 * slot discipline via {@link LegacyGlsl#splitVarying},
 * {@link LegacyGlsl#splitStructVarying} and {@link LegacyGlsl#assignDenseLocations}.
 * Only body-safe patterns are rewritten (whole-matrix writes, literal array
 * indices, per-member struct accesses); anything else keeps the single
 * declaration with slot accounting so packs never miscompile.</p>
 *
 * <p>Generation gating: translation results are cached under
 * (expanded text, program, defines, translator version) <em>plus</em> the
 * {@code SourceProvenance} pipeline generation. A dimension or pack change bumps the
 * generation, so a cached translation from an older generation is never reused. Failures are
 * never cached.</p>
 */
public final class ProgramTranslator {

    private ProgramTranslator() {
    }

    /** Translator version baked into every program cache key; bump on any logic change. */
    public static final String TRANSLATOR_VERSION = "phaseB-3";

    /** Shared block name, identical to the per-stage path so reflection tooling keeps working. */
    public static final String UNIFORM_BLOCK_NAME = ShaderTranslator.UNIFORM_BLOCK_NAME;

    /** One input stage of a program. */
    public record StageUnit(String stage, String source, String includerPath) {
        public StageUnit {
            stage = com.vulkairis.iris.SourceProvenance.normalizeStage(stage);
        }
    }

    /** One translated stage. */
    public record TranslatedStage(String stage, String glsl, List<String> notes) {
    }

    /**
     * A translated program: every stage rewritten against the same union.
     *
     * @param program                 program name (for example {@code gbuffers_terrain})
     * @param stages                  stage name to translated stage, in input order
     * @param uniformBlockMembers     union block members ({@code type name;} lines, deduped)
     * @param fragmentOutputLocations fragment {@code out} name to assigned location
     * @param unionVaryings           varying name to {@code vertex-out/fragment-in/both}
     * @param generation              pipeline generation this translation belongs to
     * @param translationKey          cache key (without the generation suffix)
     * @param notes                   cross-stage observations (one-sided varyings, conflicts)
     */
    public record TranslatedProgram(
            String program,
            Map<String, TranslatedStage> stages,
            List<String> uniformBlockMembers,
            Map<String, Integer> fragmentOutputLocations,
            Map<String, String> unionVaryings,
            long generation,
            String translationKey,
            List<String> notes) {
    }

    // ------------------------------------------------------------------
    // Entry points.
    // ------------------------------------------------------------------

    /**
     * Translates every stage of {@code program} together under the current pipeline generation.
     *
     * @param program program name for diagnostics and cache keys
     * @param units   one unit per stage (vertex/fragment; anything else is noted and skipped)
     * @param packId  pack identity for {@code SourceProvenance} tracing
     * @return the translated program
     */
    public static TranslatedProgram translate(
            String program, List<StageUnit> units, String packId) {
        return translate(program, units, packId,
                com.vulkairis.iris.SourceProvenance.currentGeneration());
    }

    /**
     * Translates every stage of {@code program} together under an explicit generation.
     *
     * @param program    program name for diagnostics and cache keys
     * @param units      one unit per stage
     * @param packId     pack identity for {@code SourceProvenance} tracing
     * @param generation pipeline generation; part of the cache identity
     * @return the translated program
     */
    public static TranslatedProgram translate(
            String program, List<StageUnit> units, String packId, long generation) {
        String name = program != null ? program : "unknown-program";
        List<StageUnit> inputs = units != null ? List.copyOf(units) : List.of();

        String translationKey = programKey(name, inputs);
        TranslatedProgram cached = lookupCache(translationKey, generation);
        if (cached != null) {
            return cached;
        }

        // Per-stage rewrite up to (but excluding) the union block and layout policy.
        List<StageDraft> drafts = new ArrayList<>();
        for (StageUnit unit : inputs) {
            drafts.add(rewriteStage(name, unit, packId, generation));
        }

        // Union pass: one shared block over all stages.
        LinkedHashMap<String, String> blockMembers = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (StageDraft draft : drafts) {
            for (Map.Entry<String, String> member : draft.uniformMembers().entrySet()) {
                String existing = blockMembers.get(member.getKey());
                if (existing == null) {
                    blockMembers.put(member.getKey(), member.getValue());
                } else if (!existing.equals(member.getValue()) && !notes.contains(
                        "uniform type conflict for '" + member.getKey() + "': keeping "
                                + existing + ", dropping " + member.getValue())) {
                    notes.add("uniform type conflict for '" + member.getKey() + "': keeping "
                            + existing + ", dropping " + member.getValue());
                }
            }
        }
        // Legacy fixed-function uniforms required by any stage join the same union block.
        for (StageDraft draft : drafts) {
            for (String required : requiredMatrixMembers(draft.legacy())) {
                String memberName = memberNameOf(required);
                blockMembers.putIfAbsent(memberName, required);
            }
        }

        // Union varying set: vertex outs plus fragment ins, with one-sided names noted.
        Map<String, Integer> vertexOuts = new LinkedHashMap<>();
        Map<String, Integer> fragmentIns = new LinkedHashMap<>();
        for (StageDraft draft : drafts) {
            if ("VERTEX".equals(draft.stage())) {
                draft.interfaceOuts().forEach(vertexOuts::putIfAbsent);
            } else if ("FRAGMENT".equals(draft.stage())) {
                draft.interfaceIns().forEach(fragmentIns::putIfAbsent);
            }
        }
        Map<String, String> unionVaryings = new LinkedHashMap<>();
        for (String varying : LegacyGlsl.unionVaryingSlots(vertexOuts, fragmentIns).keySet()) {
            boolean out = vertexOuts.containsKey(varying);
            boolean in = fragmentIns.containsKey(varying);
            unionVaryings.put(varying, out && in ? "both" : out ? "vertex-out" : "fragment-in");
        }
        for (String oneSided : LegacyGlsl.oneSidedVaryings(vertexOuts, fragmentIns)) {
            notes.add("varying '" + oneSided + "' is declared on only one side of the program");
        }

        // Union-coordinated varying split: both stages must scalarize the same
        // bases identically, so safety is decided once here from both bodies.
        // Only bases declared on at least one side with a multi-leaf expansion
        // and body-safe patterns on both stages are approved; everything else
        // keeps the single declaration with slot accounting.
        Set<String> splitBases = decideVaryingSplit(drafts);

        // Fragment-output locations: gl_FragData indices keep their index, every other
        // fragment out takes the next free dense slot (fragment stage only).
        Map<String, Integer> fragOutputs = new LinkedHashMap<>();
        Set<Integer> taken = new LinkedHashSet<>();
        for (StageDraft draft : drafts) {
            if ("FRAGMENT".equals(draft.stage())) {
                for (int index : draft.legacy().fragDataIndices()) {
                    fragOutputs.putIfAbsent(LegacyGlsl.FRAG_DATA_BASE + index, index);
                    taken.add(index);
                }
            }
        }
        int nextOutput = 0;
        for (StageDraft draft : drafts) {
            if (!"FRAGMENT".equals(draft.stage())) {
                continue;
            }
            for (String outName : draft.plainFragmentOutputs()) {
                if (fragOutputs.containsKey(outName)) {
                    continue;
                }
                while (taken.contains(nextOutput)) {
                    nextOutput++;
                }
                fragOutputs.put(outName, nextOutput);
                taken.add(nextOutput);
                nextOutput++;
            }
        }

        // Finalize each stage against the fixed union.
        Map<String, TranslatedStage> stages = new LinkedHashMap<>();
        for (StageDraft draft : drafts) {
            String finalized = finalizeStage(draft, blockMembers, fragOutputs, splitBases);
            List<String> stageNotes = new ArrayList<>(draft.notes());
            com.vulkairis.iris.SourceProvenance.recordTranslation(
                    packId, name, draft.stage(), finalized);
            stages.put(draft.stage(), new TranslatedStage(
                    draft.stage(), finalized, List.copyOf(stageNotes)));
        }

        TranslatedProgram result = new TranslatedProgram(name, Map.copyOf(stages),
                List.copyOf(blockMembers.values()), Map.copyOf(fragOutputs),
                Map.copyOf(unionVaryings), generation, translationKey, List.copyOf(notes));
        storeCache(translationKey, generation, result);
        return result;
    }

    // ------------------------------------------------------------------
    // Per-stage rewrite (everything before the union is fixed).
    // ------------------------------------------------------------------

    private record StageDraft(
            String stage,
            String body,
            Map<String, String> uniformMembers,
            Map<String, Integer> interfaceOuts,
            Map<String, Integer> interfaceIns,
            List<String> plainFragmentOutputs,
            LegacyGlsl.LegacyResult legacy,
            List<String> notes) {
    }

    private static StageDraft rewriteStage(
            String program, StageUnit unit, String packId, long generation) {
        String stage = unit.stage();
        boolean isVertex = "VERTEX".equals(stage);
        List<String> notes = new ArrayList<>();
        String source = unit.source() != null ? unit.source() : "";

        if (!isVertex && !"FRAGMENT".equals(stage)) {
            notes.add("stage '" + unit.stage() + "' is not vertex/fragment; "
                    + "translated best-effort as fragment");
        }

        // Surviving pack #includes expand before anything else (Phase A worker owns the
        // resolver; this path only calls it). Include-free sources never touch Iris at all.
        String expanded = source;
        if (source.contains("#include")) {
            try {
                expanded = IncludeResolver.expandEntry(
                        com.vulkairis.iris.IrisBridge.packFiles(),
                        unit.includerPath(), source, generation).text();
            } catch (RuntimeException includeFailure) {
                notes.add("include expansion failed: " + includeFailure);
            }
        }

        // Legacy builtins first: later passes only see core-profile identifiers.
        LegacyGlsl.LegacyResult legacy = LegacyGlsl.translateLegacyBuiltins(expanded, isVertex);
        String body = legacy.source();

        // Legacy keywords, then Iris attribute names (covers the union varying set, since
        // every stage passes through here before the union is built).
        body = LegacyKeywordPass.rewrite(body, isVertex);
        body = ShaderTranslator.normalizeIrisAttributeNames(body);

        // One version, extensions hoisted behind it.
        body = normalizeVersionAndExtensions(body);

        // Non-opaque uniforms become union-block candidates; the declaration is stubbed out.
        Map<String, String> members = new LinkedHashMap<>();
        body = collectUniformMembers(body, members);

        // Layout policy starts here so the union inventory below sees bare declarations:
        // every binding goes (reflection assigns later) and every location goes (fragment
        // outputs are renumbered densely once the union is fixed). Both strips are
        // idempotent, so finalizeStage re-applies them defensively.
        body = stripBindingQualifiers(body);
        body = stripAllLocationQualifiers(body);

        // Interface inventory for the union varying set (slot-aware for split leaves).
        Map<String, Integer> outs = collectInterfaceSlots(body, "out");
        Map<String, Integer> ins = collectInterfaceSlots(body, "in");
        List<String> plainFragOuts = "FRAGMENT".equals(stage)
                ? collectInterfaceNames(body, "out") : List.of();

        // Provenance: trace the pre-union rewrite like any other translation output.
        com.vulkairis.iris.SourceProvenance.recordTranslation(packId, program, stage, body);

        return new StageDraft(stage, body, members, outs, ins, plainFragOuts, legacy, notes);
    }

    // ------------------------------------------------------------------
    // Finalize (union fixed): block injection + layout policy.
    // ------------------------------------------------------------------

    private static String finalizeStage(StageDraft draft,
                                        Map<String, String> blockMembers,
                                        Map<String, Integer> fragOutputs,
                                        Set<String> splitBases) {
        String body = draft.body();
        boolean isVertex = !"FRAGMENT".equals(draft.stage());

        // Varying split first (declarations + bodies), so the bool-varying pass
        // below sees the final scalar leaf declarations.
        if (splitBases != null && !splitBases.isEmpty()) {
            body = applyVaryingSplit(body, isVertex, splitBases);
        }

        // Inject the single union block (std140, no binding: reflection assigns it).
        if (!blockMembers.isEmpty() && !body.contains(UNIFORM_BLOCK_NAME)) {
            StringBuilder block = new StringBuilder("layout(std140) uniform ")
                    .append(UNIFORM_BLOCK_NAME).append(" {\n");
            // Deterministic member order: alphabetical by member name.
            TreeMap<String, String> ordered = new TreeMap<>(blockMembers);
            for (String member : ordered.values()) {
                block.append("    ").append(member).append('\n');
            }
            block.append("};\n");
            body = insertAfterHeader(body, block.toString());
        }
        if (draft.legacy().needsFog() && !body.contains("struct VulkairisFogParams")) {
            body = insertAfterHeader(body, "struct VulkairisFogParams {\n"
                    + "    vec4 color;\n"
                    + "    float start;\n"
                    + "    float end;\n"
                    + "    float scale;\n"
                    + "    float density;\n"
                    + "};\n");
        }

        // Binding policy: strip every layout(binding = N); strip every layout(location = N)
        // except on fragment-stage out declarations, which are numbered densely instead.
        body = stripBindingQualifiers(body);
        body = stripAllLocationQualifiers(body);
        if ("FRAGMENT".equals(draft.stage())) {
            body = numberFragmentOutputs(body, fragOutputs);
            body = ensureVertexAttribDeclarations(body, draft.legacy(), false);
            body = insertAfterHeader(body, LegacyGlsl.fragOutputDeclarations(
                    draft.legacy().fragDataIndices()));
        } else {
            body = ensureVertexAttribDeclarations(body, draft.legacy(), true);
        }

        // bool members become int for the VulkanMod-bound path (injected block
        // plus bool in/out varyings; function signatures stay untouched).
        body = ShaderTranslator.normalizeTypesForVulkanMod(body);
        return body;
    }

    // ------------------------------------------------------------------
    // Union-coordinated varying split.
    // ------------------------------------------------------------------

    /**
     * Decides once per program which varying bases to scalarize, so the vertex
     * and fragment stages rewrite identically. A base is approved only when it
     * expands to multiple leaves (via {@link LegacyGlsl#splitVarying} /
     * {@link LegacyGlsl#splitStructVarying}) <em>and</em> both stage bodies use
     * only the documented safe patterns.
     */
    static Set<String> decideVaryingSplit(List<StageDraft> drafts) {
        Set<String> approved = new LinkedHashSet<>();
        if (drafts == null || drafts.isEmpty()) {
            return approved;
        }
        String vertexBody = null;
        String fragmentBody = null;
        Map<String, LegacyGlsl.VaryingDecl> vertexDecls = new LinkedHashMap<>();
        Map<String, LegacyGlsl.VaryingDecl> fragmentDecls = new LinkedHashMap<>();
        for (StageDraft draft : drafts) {
            if (draft == null) {
                continue;
            }
            if ("VERTEX".equals(draft.stage())) {
                vertexBody = draft.body();
                for (LegacyGlsl.VaryingDecl decl :
                        LegacyGlsl.collectVaryingDecls(draft.body(), "out")) {
                    vertexDecls.putIfAbsent(decl.name(), decl);
                }
            } else if ("FRAGMENT".equals(draft.stage())) {
                fragmentBody = draft.body();
                for (LegacyGlsl.VaryingDecl decl :
                        LegacyGlsl.collectVaryingDecls(draft.body(), "in")) {
                    fragmentDecls.putIfAbsent(decl.name(), decl);
                }
            }
        }
        Set<String> bases = new LinkedHashSet<>();
        bases.addAll(vertexDecls.keySet());
        bases.addAll(fragmentDecls.keySet());
        for (String base : bases) {
            LegacyGlsl.VaryingDecl vertexDecl = vertexDecls.get(base);
            LegacyGlsl.VaryingDecl fragmentDecl = fragmentDecls.get(base);
            LegacyGlsl.VaryingDecl representative =
                    vertexDecl != null ? vertexDecl : fragmentDecl;
            if (representative == null) {
                continue;
            }
            if (vertexDecl != null && fragmentDecl != null
                    && (!vertexDecl.type().equals(fragmentDecl.type())
                            || vertexDecl.arrayLength() != fragmentDecl.arrayLength())) {
                continue;
            }
            if (!LegacyGlsl.isBuiltinInterfaceType(representative.type())) {
                boolean vertexHasMembers = vertexDecl != null
                        && vertexDecl.structMembers() != null
                        && !vertexDecl.structMembers().isEmpty();
                boolean fragmentHasMembers = fragmentDecl != null
                        && fragmentDecl.structMembers() != null
                        && !fragmentDecl.structMembers().isEmpty();
                if (vertexDecl != null && fragmentDecl != null
                        && !(vertexHasMembers && fragmentHasMembers)) {
                    continue;
                }
                if ((vertexDecl == null) != (fragmentDecl == null)
                        && !(vertexHasMembers || fragmentHasMembers)) {
                    continue;
                }
            }
            List<LegacyGlsl.SplitLeaf> leaves = LegacyGlsl.expandVaryingLeaves(
                    representative.type(), representative.name(),
                    representative.arrayLength(), representative.structMembers());
            if (leaves.size() <= 1 && leaves.get(0).name().equals(base)) {
                continue;
            }
            if (!LegacyGlsl.isSafeToSplit(representative, vertexBody, fragmentBody)) {
                continue;
            }
            approved.add(base);
        }
        return approved;
    }

    /**
     * Applies the union-approved varying split to one stage body: stage uses
     * become leaf uses, multi-leaf declarations become scalar leaf declarations,
     * and split matrix varyings gain a reconstruction define on both stages
     * (vertex writes are decomposed first, so the define only serves leftover
     * reads there; fragment reads need no other rewrite).
     */
    static String applyVaryingSplit(String body, boolean isVertex, Set<String> splitBases) {
        if (body == null || body.isEmpty() || splitBases == null || splitBases.isEmpty()) {
            return body;
        }
        String direction = isVertex ? "out" : "in";
        List<LegacyGlsl.VaryingDecl> decls = LegacyGlsl.collectVaryingDecls(body, direction);
        Map<String, LegacyGlsl.VaryingDecl> byName = new LinkedHashMap<>();
        for (LegacyGlsl.VaryingDecl decl : decls) {
            byName.putIfAbsent(decl.name(), decl);
        }
        Set<String> applicable = new LinkedHashSet<>();
        for (String base : splitBases) {
            if (byName.containsKey(base)) {
                applicable.add(base);
            }
        }
        if (applicable.isEmpty()) {
            return body;
        }
        String rewritten = LegacyGlsl.rewriteSplitStageUses(body, isVertex, byName, applicable);
        rewritten = LegacyGlsl.rewriteSplitDeclarations(rewritten, direction, applicable);
        StringBuilder defines = new StringBuilder();
        for (String base : applicable) {
            LegacyGlsl.VaryingDecl decl = byName.get(base);
            if (decl != null && !LegacyGlsl.isBuiltinInterfaceType(decl.type())) {
                continue;
            }
            if (decl != null && decl.type() != null
                    && (decl.type().startsWith("mat") || decl.type().startsWith("dmat"))
                    && decl.arrayLength() <= 1) {
                String define = LegacyGlsl.matReconstructionDefine(base, decl.type());
                if (!define.isEmpty() && !rewritten.contains("#define " + base + " ")) {
                    defines.append(define);
                }
            }
        }
        if (defines.length() > 0) {
            rewritten = insertAfterHeader(rewritten, defines.toString());
        }
        return rewritten;
    }

    // ------------------------------------------------------------------
    // Version / extension header (per-program minimal form).
    // ------------------------------------------------------------------

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "^\\s*#version\\s+(\\d+)(?:\\s+(core|compatibility))?", Pattern.MULTILINE);
    private static final Pattern EXTENSION_LINE = Pattern.compile(
            "^[ \\t]*#extension\\b[^\\r\\n]*", Pattern.MULTILINE);
    private static final Pattern EXTENSION_NAME = Pattern.compile("#extension\\s+(\\S+)");

    static String normalizeVersionAndExtensions(String source) {
        String body = source.replace("\r\n", "\n").replace('\r', '\n');

        Map<String, String> extensions = new LinkedHashMap<>();
        Matcher ext = EXTENSION_LINE.matcher(body);
        StringBuilder stripped = new StringBuilder();
        int last = 0;
        while (ext.find()) {
            stripped.append(body, last, ext.start());
            Matcher name = EXTENSION_NAME.matcher(ext.group().trim());
            if (name.find()) {
                extensions.putIfAbsent(name.group(1), ext.group().trim());
            }
            last = ext.end();
            if (last < body.length() && body.charAt(last) == '\n') {
                last++;
            }
        }
        stripped.append(body, last, body.length());
        body = stripped.toString();

        Matcher version = VERSION_PATTERN.matcher(body);
        if (version.find()) {
            StringBuilder withVersion = new StringBuilder();
            version.appendReplacement(withVersion, Matcher.quoteReplacement("#version 450 core"));
            version.appendTail(withVersion);
            body = withVersion.toString();
        } else {
            body = "#version 450 core\n" + body;
        }

        StringBuilder header = new StringBuilder();
        for (String line : extensions.values()) {
            header.append(line).append('\n');
        }
        if (!body.contains("precision highp float;")) {
            header.append("precision highp float;\n");
        }
        if (!body.contains("precision highp int;")) {
            header.append("precision highp int;\n");
        }
        return insertAfterHeader(body, header.toString());
    }

    /** Inserts {@code header} on the line after the {@code #version} directive. */
    static String insertAfterHeader(String body, String header) {
        if (header == null || header.isEmpty()) {
            return body;
        }
        int versionStart = body.indexOf("#version 450 core");
        if (versionStart < 0) {
            return "#version 450 core\n" + header + body;
        }
        int lineEnd = body.indexOf('\n', versionStart);
        if (lineEnd < 0) {
            return body + "\n" + header;
        }
        return body.substring(0, lineEnd + 1) + header + body.substring(lineEnd + 1);
    }

    // ------------------------------------------------------------------
    // Uniform union.
    // ------------------------------------------------------------------

    private static final Pattern UNIFORM_DECL = Pattern.compile(
            "^([ \\t]*)uniform[ \\t]+([A-Za-z_]\\w*)[ \\t]+([^;{}]*);", Pattern.MULTILINE);
    private static final Pattern NON_OPAQUE_TYPE = Pattern.compile(
            "^(?:bool|int|uint|float|double|bvec[234]|ivec[234]|uvec[234]|vec[234]"
                    + "|dvec[234]|mat[234](?:x[234])?)$");
    private static final Pattern DECLARATOR = Pattern.compile(
            "[A-Za-z_]\\w*(?:\\s*\\[\\s*[^\\]]*\\s*\\])?");

    /**
     * Collects non-opaque uniform members ({@code memberName} to {@code type declarator;})
     * and stubs the original declarations out with comments (initializers dropped: illegal
     * in a block). Opaque sampler/image declarations stay in place, binding-free.
     */
    static String collectUniformMembers(String source, Map<String, String> members) {
        Matcher matcher = UNIFORM_DECL.matcher(source);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String type = matcher.group(2);
            String declarators = matcher.group(3).trim();
            if (!NON_OPAQUE_TYPE.matcher(type).matches()) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (String raw : splitTopLevel(declarators, ',')) {
                String declarator = raw.trim();
                int initializer = topLevelIndexOf(declarator, '=');
                if (initializer >= 0) {
                    declarator = declarator.substring(0, initializer).trim();
                }
                if (declarator.isEmpty() || !DECLARATOR.matcher(declarator).matches()) {
                    continue;
                }
                String memberName = declaratorName(declarator);
                members.putIfAbsent(memberName, type + " " + declarator + ";");
                kept.add(declarator);
            }
            if (kept.isEmpty()) {
                continue;
            }
            String comment = matcher.group(1) + "// uniform " + type + " "
                    + String.join(", ", kept).replaceAll("\\s+", " ") + ";";
            matcher.appendReplacement(out, Matcher.quoteReplacement(comment));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** Matrix uniforms required by legacy mappings, as union-block member lines. */
    static List<String> requiredMatrixMembers(LegacyGlsl.LegacyResult legacy) {
        List<String> required = new ArrayList<>();
        if (legacy.needsTextureMatrix()) {
            required.add("mat4 vulkairis_TextureMatrix["
                    + LegacyGlsl.TEXTURE_MATRIX_UNITS + "];");
        }
        if (legacy.needsModelViewProjection()) {
            required.add("mat4 vulkairis_ModelViewProjectionMatrix;");
        }
        if (legacy.needsModelView()) {
            required.add("mat4 vulkairis_ModelViewMatrix;");
        }
        if (legacy.needsProjection()) {
            required.add("mat4 vulkairis_ProjectionMatrix;");
        }
        if (legacy.needsNormalMatrix()) {
            required.add("mat3 vulkairis_NormalMatrix;");
        }
        if (legacy.needsFog()) {
            required.add("VulkairisFogParams vulkairis_Fog;");
        }
        return required;
    }

    private static String memberNameOf(String memberLine) {
        String trimmed = memberLine.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        int space = trimmed.lastIndexOf(' ');
        String declarator = space >= 0 ? trimmed.substring(space + 1).trim() : trimmed;
        return declaratorName(declarator);
    }

    /** Vertex attributes required by legacy mappings, as stage-input declarations. */
    private static String ensureVertexAttribDeclarations(
            String body, LegacyGlsl.LegacyResult legacy, boolean isVertexStage) {
        if (!isVertexStage) {
            return body;
        }
        StringBuilder declarations = new StringBuilder();
        if (legacy.needsVertexAttrib() && !hasInputDeclaration(body, "vulkairis_Vertex")) {
            declarations.append("in vec4 vulkairis_Vertex;\n");
        }
        if (legacy.needsNormalAttrib() && !hasInputDeclaration(body, "vulkairis_Normal")) {
            declarations.append("in vec3 vulkairis_Normal;\n");
        }
        if (legacy.needsColorAttrib() && !hasInputDeclaration(body, "vulkairis_Color")) {
            declarations.append("in vec4 vulkairis_Color;\n");
        }
        if (legacy.needsTexCoord0() && !hasInputDeclaration(body, "vulkairis_MultiTexCoord0")) {
            declarations.append("in vec4 vulkairis_MultiTexCoord0;\n");
        }
        if (legacy.needsTexCoord1() && !hasInputDeclaration(body, "vulkairis_MultiTexCoord1")) {
            declarations.append("in vec4 vulkairis_MultiTexCoord1;\n");
        }
        if (declarations.length() == 0) {
            return body;
        }
        return insertAfterHeader(body, declarations.toString());
    }

    private static final Pattern INPUT_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise"
                    + "|patch|highp|mediump|lowp)[ \\t]+)*in[ \\t]+[A-Za-z_]\\w*[ \\t]+"
                    + "[A-Za-z_]\\w*");

    /** Whether {@code body} already declares {@code name} as a stage input (not merely uses it). */
    static boolean hasInputDeclaration(String body, String name) {
        if (body == null || name == null) {
            return false;
        }
        Matcher matcher = INPUT_DECLARATION.matcher(body);
        while (matcher.find()) {
            String tail = matcher.group().trim();
            String declared = tail.substring(tail.lastIndexOf(' ') + 1).trim();
            int bracket = declared.indexOf('[');
            if (bracket >= 0) {
                declared = declared.substring(0, bracket).trim();
            }
            if (name.equals(declared)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Layout policy: strip bindings everywhere, locations except fragment outs.
    // ------------------------------------------------------------------

    private static final Pattern LAYOUT_LIST = Pattern.compile("layout\\s*\\(([^)]*)\\)");
    private static final Pattern BINDING_TOKEN = Pattern.compile("\\bbinding\\s*=\\s*\\d+");
    private static final Pattern LOCATION_TOKEN = Pattern.compile("\\blocation\\s*=\\s*\\d+");

    /**
     * Removes every {@code binding = N} qualifier. A qualifier list left empty (or with only
     * commas/whitespace) is removed wholesale; otherwise the list is re-emitted with the
     * surviving qualifiers ({@code std140}, {@code set}, ...) intact.
     */
    public static String stripBindingQualifiers(String source) {
        if (source == null || source.isEmpty() || !source.contains("binding")) {
            return source;
        }
        Matcher layout = LAYOUT_LIST.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        boolean changed = false;
        while (layout.find()) {
            if (!BINDING_TOKEN.matcher(layout.group(1)).find()) {
                continue;
            }
            String remaining = BINDING_TOKEN.matcher(layout.group(1)).replaceAll("");
            remaining = remaining.replaceAll(",\\s*,", ",").trim();
            remaining = remaining.replaceAll("^,\\s*", "").replaceAll("\\s*,\\s*$", "").trim();
            out.append(source, last, layout.start());
            if (!remaining.isEmpty()) {
                out.append("layout(").append(remaining).append(")");
            }
            last = layout.end();
            changed = true;
        }
        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        // A removed qualifier can leave a doubled space; collapse only that artifact.
        return out.toString().replaceAll("[ \\t]{2,}", " ");
    }

    /**
     * Removes every {@code location = N} qualifier (same tidy rules as
     * {@link #stripBindingQualifiers}). Fragment outputs regain dense locations via
     * {@link #numberFragmentOutputs}.
     */
    public static String stripAllLocationQualifiers(String source) {
        if (source == null || source.isEmpty() || !source.contains("location")) {
            return source;
        }
        Matcher layout = LAYOUT_LIST.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        int last = 0;
        boolean changed = false;
        while (layout.find()) {
            if (!LOCATION_TOKEN.matcher(layout.group(1)).find()) {
                continue;
            }
            String remaining = LOCATION_TOKEN.matcher(layout.group(1)).replaceAll("");
            remaining = remaining.replaceAll(",\\s*,", ",").trim();
            remaining = remaining.replaceAll("^,\\s*", "").replaceAll("\\s*,\\s*$", "").trim();
            out.append(source, last, layout.start());
            if (!remaining.isEmpty()) {
                out.append("layout(").append(remaining).append(")");
            }
            last = layout.end();
            changed = true;
        }
        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString().replaceAll("[ \\t]{2,}", " ");
    }

    private static final Pattern FRAG_OUT_DECL = Pattern.compile(
            "(?m)^([ \\t]*)out[ \\t]+([A-Za-z_]\\w*)[ \\t]+"
                    + "([A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?"
                    + "(?:[ \\t]*,[ \\t]*[A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?)*)"
                    + "([ \\t]*);([^\\r\\n]*)");

    /**
     * Numbers every bare fragment {@code out} declaration with its union location. Only
     * declarations without an existing {@code layout(...)} prefix are touched, which makes
     * the pass idempotent.
     */
    static String numberFragmentOutputs(String source, Map<String, Integer> locations) {
        if (source == null || source.isEmpty() || locations == null || locations.isEmpty()) {
            return source;
        }
        Matcher matcher = FRAG_OUT_DECL.matcher(source);
        StringBuilder out = new StringBuilder(source.length() + 64);
        int last = 0;
        boolean changed = false;
        while (matcher.find()) {
            // Skip declarations that already carry a layout qualifier on the same line start.
            int lineStart = matcher.start();
            String declarators = matcher.group(3);
            List<String> names = new ArrayList<>();
            for (String raw : declarators.split(",")) {
                names.add(declaratorName(raw.trim()));
            }
            if (names.size() != 1 || !locations.containsKey(names.get(0))) {
                continue;
            }
            out.append(source, last, lineStart);
            out.append(matcher.group(1))
                    .append("layout(location = ").append(locations.get(names.get(0))).append(") ")
                    .append("out ").append(matcher.group(2)).append(' ')
                    .append(declarators.trim())
                    .append(matcher.group(4)).append(';').append(matcher.group(5));
            last = matcher.end();
            changed = true;
        }
        if (!changed) {
            return source;
        }
        out.append(source, last, source.length());
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Interface inventory (union varying set).
    // ------------------------------------------------------------------

    private static final Pattern INTERFACE_LINE = Pattern.compile(
            "(?m)^([ \\t]*)((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise"
                    + "|patch|highp|mediump|lowp)[ \\t]+)*)(in|out)([ \\t]+)"
                    + "((?:(?:flat|smooth|noperspective|centroid|sample|invariant|precise"
                    + "|patch|highp|mediump|lowp)[ \\t]+)*)"
                    + "([A-Za-z_]\\w*)([ \\t]+)"
                    + "([A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?"
                    + "(?:[ \\t]*,[ \\t]*[A-Za-z_]\\w*(?:[ \\t]*\\[[^\\]]*\\])?)*)"
                    + "([ \\t]*);");

    static Map<String, Integer> collectInterfaceSlots(String source, String direction) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        if (source == null || source.isEmpty()) {
            return slots;
        }
        Matcher matcher = INTERFACE_LINE.matcher(source);
        while (matcher.find()) {
            if (!direction.equals(matcher.group(3))) {
                continue;
            }
            String type = matcher.group(6);
            for (String raw : matcher.group(8).split(",")) {
                String declarator = raw.trim();
                String name = declaratorName(declarator);
                if (name.isEmpty() || slots.containsKey(name)) {
                    continue;
                }
                // Split-aware slot accounting: matrices/arrays expand via
                // splitVarying, structs via splitStructVarying with the visible
                // definition (unknown structs keep the single declaration).
                // This keeps the union inventory consistent with the scalarized
                // declarations emitted by applyVaryingSplit.
                int arrayLength = arrayLengthOf(declarator);
                List<LegacyGlsl.StructMember> members =
                        LegacyGlsl.isBuiltinInterfaceType(type)
                                ? List.of()
                                : LegacyGlsl.parseStructMembers(source, type);
                int consumed = LegacyGlsl.expandVaryingLeaves(
                        type, name, arrayLength, members).size();
                slots.put(name, Math.max(1, consumed));
            }
        }
        return slots;
    }

    static List<String> collectInterfaceNames(String source, String direction) {
        return new ArrayList<>(collectInterfaceSlots(source, direction).keySet());
    }

    private static int arrayLengthOf(String declarator) {
        int open = declarator.indexOf('[');
        int close = declarator.indexOf(']');
        if (open < 0 || close <= open) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(declarator.substring(open + 1, close).trim()));
        } catch (NumberFormatException unsized) {
            return 1;
        }
    }

    private static String declaratorName(String declarator) {
        String trimmed = declarator.trim();
        int bracket = trimmed.indexOf('[');
        return bracket < 0 ? trimmed : trimmed.substring(0, bracket).trim();
    }

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

    // ------------------------------------------------------------------
    // Generation-gated program cache.
    // ------------------------------------------------------------------

    private record CacheEntry(long generation, TranslatedProgram program) {
    }

    private static final int MAX_CACHE_ENTRIES = 64;
    private static final Map<String, CacheEntry> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_CACHE_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    static String programKey(String program, List<StageUnit> units) {
        StringBuilder key = new StringBuilder(program).append('|').append(TRANSLATOR_VERSION);
        for (StageUnit unit : units) {
            key.append('|').append(unit.stage()).append(':')
                    .append(DiagnosticManager.hashSource(unit.source()));
        }
        return key.toString();
    }

    private static TranslatedProgram lookupCache(String key, long generation) {
        CacheEntry entry = CACHE.get(key);
        if (entry != null && entry.generation() == generation) {
            return entry.program();
        }
        return null;
    }

    private static void storeCache(String key, long generation, TranslatedProgram program) {
        CACHE.put(key, new CacheEntry(generation, program));
    }

    /** Clears the program cache; tests only. */
    public static void clearCacheForTests() {
        CACHE.clear();
    }

    /** @return live program-cache entries; tests only */
    public static int cacheSizeForTests() {
        return CACHE.size();
    }
}
