package com.vulkairis.translation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase A: Iris-pack {@code #include} expansion, run on pre-translation text.
 *
 * <p>Some pack programs reach Vulkairis with their {@code #include} directives still in place
 * (notably the sodium-terrain programs and any {@code #include <...>} angle-bracket form, which
 * Iris's own include graph does not follow). Shaderc rejects those outright
 * ({@code '#include' : #error unexpected include directive for header name: ...}), so they are
 * expanded here before any translation runs.</p>
 *
 * <p>Resolution semantics (reimplemented from observed pack behavior, not copied from any pack
 * loader):</p>
 * <ul>
 *   <li>A spec starting with {@code /} resolves under the pack {@code shaders/} root; any other
 *       spec resolves relative to the including file. Leading {@code /+} runs are stripped, so
 *       {@code //program/x.glsl} behaves like {@code /program/x.glsl}.</li>
 *   <li>Both {@code #include "..."} and {@code #include <...>} forms are followed.</li>
 *   <li>Segments are normalized ({@code .} dropped, {@code ..} pops) and the result is confined
 *       to the root: a spec that would escape is a structured miss, never silent clamping.</li>
 *   <li>Exact hit first, then a case-insensitive fallback over the pack's known paths (packs are
 *       authored on case-insensitive filesystems and break inside archives otherwise).</li>
 *   <li>Bounds: {@link #MAX_DEPTH} nesting, {@link #MAX_FILES} total files, per-file byte/line
 *       caps, and total output line/character caps (diamond includes multiply).</li>
 *   <li>Successful expansions are memoized per {@code (generation, entry)}; failures never are.</li>
 * </ul>
 *
 * <p>Misses never throw into the render path: the offending directive is replaced by a
 * {@code #error} line naming the file and the reason, so the compiler (and diagnostics) attribute
 * the failure to the pack file instead of failing opaquely. Spliced files are wrapped in
 * {@code #line} markers so compiler errors map back to pack files.</p>
 *
 * <p>Deliberate Phase A limits, documented so a later phase can lift them honestly:</p>
 * <ul>
 *   <li>No preprocessor conditional evaluation: every {@code #include} in the text is followed,
 *       regardless of {@code #if} nesting. Intercepted sources are already Iris-macro-resolved,
 *       so this only matters for unexpanded direct-pack reads.</li>
 *   <li>Only the entry file's {@code #version} is kept; {@code #version} lines inside included
 *       files are dropped (a unit has exactly one version, and the translator guarantees one).</li>
 *   <li>String literals are quote-aware for comment detection (a {@code //} inside a header
 *       name does not start a comment).</li>
 *   <li>Backslash line continuations are not joined before matching (consistent with Iris's own
 *       line-based include scan).</li>
 * </ul>
 */
public final class IncludeResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/IncludeResolver");

    /** Deep enough for anything real; past this the pack is malformed or hostile. */
    public static final int MAX_DEPTH = 32;

    /**
     * Total files per expansion. A depth limit alone does not bound the work: includes form a
     * graph, not a tree, and diamond includes multiply at every level.
     */
    public static final int MAX_FILES = 20000;

    /** Total output lines per expansion (runaway guard; a healthy unit is thousands). */
    public static final int MAX_TOTAL_LINES = 400_000;

    /** Total output characters per expansion (downstream translator bound). */
    public static final int MAX_TOTAL_CHARACTERS = 4_000_000;

    /** Per-file byte cap for an included file (approximated from its lines). */
    public static final long MAX_FILE_BYTES = 8L * 1024L * 1024L;

    /** Per-file line cap for an included file. */
    public static final int MAX_FILE_LINES = 100_000;

    /** Successful expansions remembered; failures are recomputed so a fixed pack recovers. */
    private static final int MEMO_ENTRIES = 64;

    private static final Pattern INCLUDE_DIRECTIVE = Pattern.compile(
            "^\\s*#\\s*include\\s*[<\"]([^\"<>]+)[>\"].*$");

    private IncludeResolver() {
    }

    /**
     * Iris-owned pack file access. Production implementations read through Iris's already-mounted
     * pack (never a second zip mount); tests use in-memory or fixture-zip implementations.
     */
    public interface PackFiles {
        /** @return whether pack reads are possible at all right now */
        boolean available();

        /** @return short human-readable identity for logs, e.g. {@code iris-pack} or a reason */
        String describe();

        /**
         * @param shadersRelativePath normalized path relative to {@code shaders/}, no leading
         *        slash (e.g. {@code program/gbuffers_terrain.glsl})
         * @return the file's lines, or {@code null} when absent (never throws for a miss)
         */
        List<String> readLines(String shadersRelativePath);

        /** @return known shaders-relative paths, for the case-insensitive fallback; may be empty */
        Set<String> knownPaths();
    }

    /** A pack source that cannot be read right now; every include becomes a structured miss. */
    public static PackFiles unavailable(String reason) {
        String detail = reason != null ? reason : "unknown reason";
        return new PackFiles() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public String describe() {
                return "unavailable(" + detail + ")";
            }

            @Override
            public List<String> readLines(String shadersRelativePath) {
                return null;
            }

            @Override
            public Set<String> knownPaths() {
                return Set.of();
            }
        };
    }

    /**
     * In-memory pack for tests. Keys accept any reasonable form
     * ({@code program/x.glsl}, {@code /program/x.glsl}, {@code shaders/program/x.glsl}) and are
     * canonicalized; values are split into lines.
     */
    public static PackFiles memoryPack(Map<String, String> filesByPath) {
        Map<String, List<String>> byRel = new LinkedHashMap<>();
        if (filesByPath != null) {
            for (Map.Entry<String, String> entry : filesByPath.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String rel = canonicalKey(entry.getKey());
                if (rel.isEmpty()) {
                    continue;
                }
                byRel.putIfAbsent(rel, splitLines(entry.getValue()));
            }
        }
        Map<String, List<String>> frozen = new LinkedHashMap<>(byRel);
        return new PackFiles() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String describe() {
                return "memory-pack(" + frozen.size() + " files)";
            }

            @Override
            public List<String> readLines(String shadersRelativePath) {
                List<String> lines = frozen.get(shadersRelativePath);
                return lines != null ? List.copyOf(lines) : null;
            }

            @Override
            public Set<String> knownPaths() {
                return Set.copyOf(frozen.keySet());
            }
        };
    }

    /** One include resolution: either the file's lines or a structured miss (never thrown). */
    public sealed interface FollowResult permits FollowResult.Found, FollowResult.Miss {
        /** @param resolvedPath canonical shaders-relative path that was read */
        record Found(String resolvedPath, List<String> lines, boolean caseInsensitive)
                implements FollowResult {
        }

        /** @param reason human-readable {@code filename + reason} text, also emitted as-is */
        record Miss(String spec, String reason) implements FollowResult {
        }
    }

    /**
     * Resolves one include spec against the pack.
     *
     * @param files pack file access (Iris-owned in production)
     * @param from shaders-relative path of the including file (e.g.
     *        {@code gbuffers_terrain.vsh}); may be {@code null} when unknown, in which case only
     *        absolute specs resolve
     * @param spec the raw header name from the {@code #include} directive
     * @return the resolved file or a structured miss
     */
    public static FollowResult follow(PackFiles files, String from, String spec) {
        if (files == null || !files.available()) {
            String why = files != null ? files.describe() : "null pack files";
            return new FollowResult.Miss(spec,
                    "pack files unavailable for \"" + spec + "\" (" + why + ")");
        }
        if (spec == null || spec.isBlank()) {
            return new FollowResult.Miss(spec, "empty include path");
        }
        String trimmed = spec.trim();
        String rel;
        if (trimmed.startsWith("/")) {
            rel = normalizeSegments(stripLeadingSlashes(trimmed));
            if (rel == null) {
                return new FollowResult.Miss(spec, "include escapes pack: \"" + trimmed + "\"");
            }
        } else {
            if (from == null || from.isBlank()) {
                return new FollowResult.Miss(spec,
                        "relative include with unknown includer: \"" + trimmed + "\"");
            }
            String base = clampSegments(from.replace('\\', '/'));
            String parent = parentDir(base);
            rel = normalizeJoin(parent, trimmed);
            if (rel == null) {
                return new FollowResult.Miss(spec, "include escapes pack: \"" + trimmed + "\"");
            }
        }

        List<String> exact = readSafely(files, rel);
        if (exact != null) {
            return new FollowResult.Found(rel, exact, false);
        }

        String fallbackKey = findIgnoringCase(files.knownPaths(), rel);
        if (fallbackKey != null) {
            List<String> lines = readSafely(files, fallbackKey);
            if (lines != null) {
                return new FollowResult.Found(canonicalKey(fallbackKey), lines, true);
            }
        }
        return new FollowResult.Miss(spec,
                "include not found: \"" + trimmed + "\" (resolved \"shaders/" + rel + "\")");
    }

    /**
     * The expanded unit: the output text plus what happened during expansion.
     */
    public record ExpansionResult(
            String text,
            String entryKey,
            int filesExpanded,
            int maxDepth,
            int caseInsensitiveHits,
            int missing,
            int cycles,
            boolean clean,
            List<String> errors) {
    }

    private record MemoEntry(PackFiles files, ExpansionResult result) {
    }

    private static final Map<String, MemoEntry> MEMO = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMO_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, MemoEntry> eldest) {
                    return size() > MEMO_ENTRIES;
                }
            });

    /**
     * Expands every {@code #include} in {@code source}, recursively, with {@code #line} markers.
     *
     * @param files pack file access
     * @param entryPath shaders-relative path of the entry file when known (e.g.
     *        {@code gbuffers_terrain.vsh}); {@code null} when the source is detached from its
     *        file (absolute includes still resolve; relative ones miss loudly)
     * @param source the entry source text
     * @param generation pipeline generation; part of the memo key so a pack/dimension change
     *        never reuses an older expansion
     * @return the expanded unit; never {@code null}, never throws for content problems
     */
    public static ExpansionResult expandEntry(
            PackFiles files, String entryPath, String source, long generation) {
        if (source == null) {
            return new ExpansionResult(null, "<null>", 0, 0, 0, 0, 0, true, List.of());
        }
        if (!source.contains("#include")) {
            // Fast path: nothing to do, byte-identical output. This keeps every include-free
            // source (all existing fixtures and tests) exactly as before.
            return new ExpansionResult(source, entryLabel(entryPath), 0, 0, 0, 0, 0, true,
                    List.of());
        }

        String entryKey = entryPath != null && !entryPath.isBlank()
                ? "path:" + canonicalKey(entryPath)
                : "hash:" + hashForMemo(source);
        String filesId = System.identityHashCode(files) + ":"
                + (files != null ? files.describe() : "null");
        String memoKey = generation + "|" + filesId + "|" + entryKey;
        MemoEntry memoized = MEMO.get(memoKey);
        if (memoized != null && memoized.files() == files) {
            return memoized.result();
        }

        State state = new State();
        String entryRel = entryPath != null && !entryPath.isBlank()
                ? clampSegments(canonicalKey(entryPath)) : null;
        if (entryRel != null && !entryRel.isEmpty()) {
            state.onPath.add(entryRel);
        }
        expandLines(files, entryRel, entryLabel(entryPath), splitLines(source), 0, state);

        ExpansionResult result = new ExpansionResult(
                String.join("\n", state.output),
                entryLabel(entryPath),
                state.filesExpanded,
                state.maxDepth,
                state.caseInsensitiveHits,
                state.missing,
                state.cycles,
                state.errors.isEmpty(),
                List.copyOf(state.errors));
        if (result.clean()) {
            MEMO.put(memoKey, new MemoEntry(files, result));
        }
        if (state.caseInsensitiveHits > 0) {
            LOGGER.debug("[Vulkairis/IncludeResolver] {} case-insensitive include hit(s) in {}",
                    state.caseInsensitiveHits, result.entryKey());
        }
        return result;
    }

    private static String entryLabel(String entryPath) {
        if (entryPath != null && !entryPath.isBlank()) {
            return "shaders/" + canonicalKey(entryPath);
        }
        return "pack-entry";
    }

    private static String hashForMemo(String source) {
        try {
            String hash = com.vulkairis.diagnostics.DiagnosticManager.hashSource(source);
            return hash != null ? hash : Integer.toHexString(source.hashCode());
        } catch (Throwable ignored) {
            return Integer.toHexString(source.hashCode());
        }
    }

    /** Clears the expansion memo; tests only. */
    public static void clearMemoForTests() {
        MEMO.clear();
    }

    /** @return live memo entries; tests only */
    public static int memoSizeForTests() {
        return MEMO.size();
    }

    private static final class State {
        final List<String> output = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final Set<String> onPath = new LinkedHashSet<>();
        long characters;
        int filesExpanded;
        int maxDepth;
        int caseInsensitiveHits;
        int missing;
        int cycles;
        int versionSkipped;
        boolean inBlockComment;
        boolean exhausted;
    }

    private static void expandLines(PackFiles files, String currentRel, String display,
                                    List<String> lines, int depth, State state) {
        state.maxDepth = Math.max(state.maxDepth, depth);
        if (depth > MAX_DEPTH) {
            emitError(state, display,
                    "include nesting too deep at \"" + display + "\" (past " + MAX_DEPTH + ")");
            return;
        }
        if (++state.filesExpanded > MAX_FILES) {
            emitExhausted(state, display);
            return;
        }

        for (int i = 0; i < lines.size(); i++) {
            if (state.exhausted) {
                return;
            }
            String line = lines.get(i);
            Scan scan = scanLine(line, state.inBlockComment);
            state.inBlockComment = scan.inBlockAfter();

            Matcher include = INCLUDE_DIRECTIVE.matcher(scan.codeText().trim());
            if (!include.matches()) {
                if (depth == 0) {
                    emit(state, line);
                } else if (isVersionLine(scan.codeText())) {
                    // A unit has exactly one version and it is the entry's; later ones come from
                    // includes and would be an error where they land. The translator guarantees a
                    // version when the entry has none, so dropping these loses nothing.
                    state.versionSkipped++;
                } else {
                    emit(state, line);
                }
                continue;
            }

            String spec = include.group(1).trim();
            FollowResult follow = follow(files, currentRel, spec);
            if (follow instanceof FollowResult.Miss miss) {
                state.missing++;
                state.errors.add(miss.reason());
                emit(state, "#error vulkairis: " + miss.reason());
                continue;
            }
            FollowResult.Found found = (FollowResult.Found) follow;
            if (found.caseInsensitive()) {
                state.caseInsensitiveHits++;
            }
            if (!checkFileCaps(state, found)) {
                continue;
            }
            if (!state.onPath.add(found.resolvedPath())) {
                state.cycles++;
                String reason = "include cycle at \"shaders/" + found.resolvedPath() + "\"";
                state.errors.add(reason);
                emit(state, "#error vulkairis: " + reason);
                continue;
            }
            emit(state, "#line 1 \"" + sanitizeLabel("shaders/" + found.resolvedPath()) + "\"");
            expandLines(files, found.resolvedPath(), "shaders/" + found.resolvedPath(),
                    found.lines(), depth + 1, state);
            state.onPath.remove(found.resolvedPath());
            if (!state.exhausted) {
                emit(state, "#line " + (i + 2) + " \"" + sanitizeLabel(display) + "\"");
            }
        }
    }

    private static boolean checkFileCaps(State state, FollowResult.Found found) {
        List<String> lines = found.lines();
        if (lines.size() > MAX_FILE_LINES) {
            String reason = "include too large: \"shaders/" + found.resolvedPath() + "\" ("
                    + lines.size() + " lines, past " + MAX_FILE_LINES + ")";
            state.errors.add(reason);
            emit(state, "#error vulkairis: " + reason);
            return false;
        }
        long bytes = 0;
        for (String line : lines) {
            bytes += line.length() + 1;
            if (bytes > MAX_FILE_BYTES) {
                String reason = "include too large: \"shaders/" + found.resolvedPath()
                        + "\" (past " + MAX_FILE_BYTES + " bytes)";
                state.errors.add(reason);
                emit(state, "#error vulkairis: " + reason);
                return false;
            }
        }
        return true;
    }

    private static void emit(State state, String line) {
        if (state.exhausted) {
            return;
        }
        if (state.output.size() >= MAX_TOTAL_LINES
                || state.characters + line.length() + 1 > MAX_TOTAL_CHARACTERS) {
            emitExhausted(state, null);
            return;
        }
        state.output.add(line);
        state.characters += line.length() + 1;
    }

    private static void emitError(State state, String display, String reason) {
        state.errors.add(reason);
        // Errors bypass the budget: the message itself must never become the runaway.
        state.output.add("#error vulkairis: " + reason);
        state.characters += reason.length() + 32;
    }

    private static void emitExhausted(State state, String display) {
        if (!state.exhausted) {
            state.exhausted = true;
            String reason = "include budget exhausted"
                    + (display != null ? " at \"" + display + "\"" : "")
                    + " (past " + MAX_FILES + " files / " + MAX_TOTAL_LINES + " lines)";
            state.errors.add(reason);
            state.output.add("#error vulkairis: " + reason);
        }
    }

    private record Scan(String codeText, boolean inBlockAfter) {
    }

    /**
     * Splits a line into its code part (outside comments) and the resulting block-comment state.
     * Mirrors the block-comment walk with one refinement: {@code //} and {@code /*} open a
     * comment only outside a double-quoted literal, so a header name like
     * {@code "//program/x.glsl"} is not cut at its slashes. String literals are otherwise kept
     * verbatim in the code part (the include matcher needs the quoted header name).
     */
    static Scan scanLine(String line, boolean inBlock) {
        StringBuilder code = new StringBuilder(line.length());
        boolean inString = false;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (inBlock) {
                int end = line.indexOf("*/", i);
                if (end < 0) {
                    i = line.length();
                } else {
                    inBlock = false;
                    i = end + 2;
                }
            } else if (inString) {
                if (c == '\\' && i + 1 < line.length()) {
                    code.append(c).append(line.charAt(i + 1));
                    i += 2;
                } else {
                    if (c == '"') {
                        inString = false;
                    }
                    code.append(c);
                    i++;
                }
            } else if (c == '"') {
                inString = true;
                code.append(c);
                i++;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                inBlock = true;
                i += 2;
            } else {
                code.append(c);
                i++;
            }
        }
        return new Scan(code.toString(), inBlock);
    }

    private static boolean isVersionLine(String codeText) {
        String trimmed = codeText.trim();
        if (!trimmed.startsWith("#")) {
            return false;
        }
        String rest = trimmed.substring(1).trim();
        return rest.equals("version") || rest.startsWith("version ")
                || rest.startsWith("version\t");
    }

    private static String sanitizeLabel(String label) {
        return label.replace('"', '\'');
    }

    private static List<String> readSafely(PackFiles files, String rel) {
        try {
            List<String> lines = files.readLines(rel);
            return lines != null ? List.copyOf(lines) : null;
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/IncludeResolver] Pack read failed for '{}': {}",
                    rel, String.valueOf(t));
            return null;
        }
    }

    private static String findIgnoringCase(Set<String> knownPaths, String rel) {
        if (knownPaths == null || knownPaths.isEmpty()) {
            return null;
        }
        for (String known : knownPaths) {
            if (known != null && canonicalKey(known).equalsIgnoreCase(rel)) {
                return known;
            }
        }
        return null;
    }

    /**
     * Canonical form for comparison and lookup: forward slashes, no leading slashes, no leading
     * {@code shaders/} segment. Public so Iris-owned readers canonicalize exactly like specs.
     */
    public static String canonicalKey(String path) {
        String s = path.replace('\\', '/');
        s = stripLeadingSlashes(s);
        if (s.length() > 8 && s.substring(0, 8).equalsIgnoreCase("shaders/")) {
            s = s.substring(8);
        } else if (s.equalsIgnoreCase("shaders")) {
            s = "";
        }
        return s;
    }

    private static String stripLeadingSlashes(String spec) {
        int i = 0;
        while (i < spec.length() && (spec.charAt(i) == '/' || spec.charAt(i) == '\\')) {
            i++;
        }
        return spec.substring(i);
    }

    private static String parentDir(String canonicalRel) {
        int slash = canonicalRel.lastIndexOf('/');
        return slash < 0 ? "" : canonicalRel.substring(0, slash);
    }

    /**
     * Normalizes a slash-separated path, collapsing {@code .} and resolving {@code ..}.
     *
     * @return the normalized path, or {@code null} when {@code ..} would escape the root
     */
    static String normalizeSegments(String path) {
        List<String> kept = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (kept.isEmpty()) {
                    return null;
                }
                kept.remove(kept.size() - 1);
                continue;
            }
            kept.add(segment);
        }
        return String.join("/", kept);
    }

    /** Like {@link #normalizeSegments} but clamps {@code ..} at the root instead of failing. */
    private static String clampSegments(String path) {
        List<String> kept = new ArrayList<>();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!kept.isEmpty()) {
                    kept.remove(kept.size() - 1);
                }
                continue;
            }
            kept.add(segment);
        }
        return String.join("/", kept);
    }

    private static String normalizeJoin(String baseDir, String spec) {
        String cleaned = spec.replace('\\', '/');
        String combined = baseDir.isEmpty() ? cleaned : baseDir + "/" + cleaned;
        return normalizeSegments(combined);
    }

    private static List<String> splitLines(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }
}
