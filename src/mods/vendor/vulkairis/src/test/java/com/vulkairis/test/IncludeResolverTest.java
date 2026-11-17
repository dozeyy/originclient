package com.vulkairis.test;

import com.vulkairis.translation.IncludeResolver;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Phase A: include-resolver tests (absolute/relative/case-insensitive/missing/cyclic/escape)
 * plus the {@code /program/*.glsl} fixture-zip integration and the pre-translation wiring.
 */
public class IncludeResolverTest {

    private static IncludeResolver.PackFiles pack(Object... kv) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            files.put((String) kv[i], (String) kv[i + 1]);
        }
        return IncludeResolver.memoryPack(files);
    }

    @Test
    public void testAbsoluteIncludeResolvesUnderShadersRoot() {
        IncludeResolver.PackFiles files = pack(
                "program/gbuffers_terrain.glsl", "vec4 shade() { return vec4(1.0); }\n");
        String entry = """
                #version 120
                #include "/program/gbuffers_terrain.glsl"
                void main() {}
                """;
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "gbuffers_terrain.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertTrue(result.text().contains("vec4 shade()"),
                "included body must be spliced in:\n" + result.text());
        Assertions.assertFalse(result.text().contains("#include"),
                "no include directive may survive:\n" + result.text());
        Assertions.assertEquals(2, result.filesExpanded());
    }

    @Test
    public void testRelativeIncludeResolvesNextToIncluder() {
        IncludeResolver.PackFiles files = pack(
                "lib/common.glsl", "float f() { return 1.0; }\n",
                "lib/extra.glsl", "#include \"common.glsl\"\nfloat g() { return f(); }\n");
        String entry = "#include \"extra.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "lib/main.glsl", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertTrue(result.text().contains("float f()"),
                "transitive relative include must resolve:\n" + result.text());
        Assertions.assertEquals(3, result.filesExpanded());
    }

    @Test
    public void testAngleBracketFormResolves() {
        IncludeResolver.PackFiles files = pack(
                "lib/a.glsl", "float a() { return 1.0; }\n");
        String entry = "#include <lib/a.glsl>\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertTrue(result.text().contains("float a()"));
    }

    @Test
    public void testLeadingSlashRunsAreStripped() {
        IncludeResolver.PackFiles files = pack(
                "program/x.glsl", "float x() { return 1.0; }\n");
        String entry = "#include \"//program/x.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertTrue(result.text().contains("float x()"));
    }

    @Test
    public void testCaseInsensitiveFallback() {
        IncludeResolver.PackFiles files = pack(
                "lib/CaseUtil.glsl", "float caseValue() { return 0.5; }\n");
        String entry = "#include \"/lib/caseutil.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertEquals(1, result.caseInsensitiveHits());
        Assertions.assertTrue(result.text().contains("float caseValue()"));
    }

    @Test
    public void testMissingIncludeEmitsNamedError() {
        IncludeResolver.PackFiles files = pack();
        String entry = """
                #version 120
                #include "/program/absent.glsl"
                void main() {}
                """;
        // Must not throw into the caller: the miss becomes a named #error line.
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertFalse(result.clean());
        Assertions.assertEquals(1, result.missing());
        Assertions.assertTrue(result.text().contains("#error"),
                "missing include must leave a compiler-visible error:\n" + result.text());
        Assertions.assertTrue(result.text().contains("absent.glsl"),
                "the error must name the pack file:\n" + result.text());
    }

    @Test
    public void testCyclicIncludeEmitsNamedError() {
        IncludeResolver.PackFiles files = pack(
                "lib/a.glsl", "#include \"b.glsl\"\nfloat a() { return 1.0; }\n",
                "lib/b.glsl", "#include \"a.glsl\"\nfloat b() { return 2.0; }\n");
        String entry = "#include \"lib/a.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertFalse(result.clean());
        Assertions.assertTrue(result.cycles() >= 1, "expected a cycle, got: " + result.errors());
        Assertions.assertTrue(result.text().contains("include cycle"),
                "cycle must be a named error:\n" + result.text());
        Assertions.assertTrue(result.text().contains("lib/a.glsl") || result.text().contains("lib/b.glsl"));
    }

    @Test
    public void testEscapeAttemptIsContained() {
        IncludeResolver.PackFiles files = pack(
                "lib/ok.glsl", "float ok() { return 1.0; }\n");
        String absolute = "#include \"/../secret.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult absoluteResult =
                IncludeResolver.expandEntry(files, "lib/entry.glsl", absolute, 0);
        Assertions.assertFalse(absoluteResult.clean());
        Assertions.assertTrue(absoluteResult.text().contains("escapes pack"),
                "absolute escape must be refused:\n" + absoluteResult.text());

        String relative = "#include \"../../secret.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult relativeResult =
                IncludeResolver.expandEntry(files, "lib/entry.glsl", relative, 0);
        Assertions.assertFalse(relativeResult.clean());
        Assertions.assertTrue(relativeResult.text().contains("escapes pack"),
                "relative escape must be refused:\n" + relativeResult.text());
    }

    @Test
    public void testDepthLimitTerminates() {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            files.put("lib/f" + i + ".glsl", "#include \"f" + (i + 1) + ".glsl\"\n");
        }
        files.put("lib/f40.glsl", "float deep() { return 1.0; }\n");
        IncludeResolver.ExpansionResult result = IncludeResolver.expandEntry(
                IncludeResolver.memoryPack(files), "lib/f0.glsl",
                "#include \"f1.glsl\"\nvoid main() {}\n", 0);
        Assertions.assertFalse(result.clean());
        Assertions.assertTrue(result.text().contains("nesting too deep"),
                "depth limit must fire:\n" + result.text());
        Assertions.assertTrue(result.maxDepth() > IncludeResolver.MAX_DEPTH);
    }

    @Test
    public void testRelativeIncludeWithUnknownIncluderMisses() {
        IncludeResolver.PackFiles files = pack(
                "lib/a.glsl", "float a() { return 1.0; }\n");
        String entry = "#include \"lib/a.glsl\"\nvoid main() {}\n";
        // No entry path: the relative spec cannot be anchored, but this must still not throw.
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, null, entry, 0);
        Assertions.assertFalse(result.clean());
        Assertions.assertTrue(result.text().contains("unknown includer"),
                "expected an unknown-includer error:\n" + result.text());
    }

    @Test
    public void testLineMarkersMapToPackFiles() {
        IncludeResolver.PackFiles files = pack(
                "program/gbuffers_terrain.glsl",
                "vec4 shade() { return vec4(1.0); }\n");
        String entry = """
                #version 120
                #include "/program/gbuffers_terrain.glsl"
                void main() {}
                """;
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "gbuffers_terrain.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        String[] lines = result.text().split("\n");
        // Entry line 1 (#version) stays first so #version keeps its required position.
        Assertions.assertTrue(lines[0].startsWith("#version"), "first line: " + lines[0]);
        Assertions.assertTrue(result.text().contains("#line 1 \"shaders/program/gbuffers_terrain.glsl\""),
                "spliced file must open with a #line marker:\n" + result.text());
        Assertions.assertTrue(result.text().contains("#line 3 \"shaders/gbuffers_terrain.vsh\""),
                "control must return to the entry at the following line:\n" + result.text());
    }

    @Test
    public void testCommentedIncludeIsIgnored() {
        IncludeResolver.PackFiles files = pack();
        String entry = """
                #version 120
                // #include "/program/absent.glsl"
                /* #include "/program/also-absent.glsl" */
                /*
                #include "/program/hidden.glsl"
                */
                void main() {}
                """;
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertEquals(0, result.missing());
        Assertions.assertEquals(entry.replace("\r\n", "\n").replace('\r', '\n')
                .strip().split("\n").length, result.text().split("\n").length);
    }

    @Test
    public void testMissingPackIsStructuredMiss() {
        IncludeResolver.PackFiles files = IncludeResolver.unavailable("no Iris shader pack is active");
        String entry = "#include \"/program/x.glsl\"\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertFalse(result.clean());
        Assertions.assertTrue(result.text().contains("pack files unavailable"),
                "expected an unavailable-pack error:\n" + result.text());
        Assertions.assertTrue(result.text().contains("x.glsl"));
    }

    @Test
    public void testNoIncludesPassesThroughIdentical() {
        IncludeResolver.PackFiles files = pack();
        String entry = "#version 450 core\nvoid main() {}\n";
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertTrue(result.clean());
        Assertions.assertEquals(entry.replace("\r\n", "\n"), result.text().replace("\r\n", "\n"));
        Assertions.assertEquals(0, result.filesExpanded());
    }

    @Test
    public void testNestedVersionIsDropped() {
        IncludeResolver.PackFiles files = pack(
                "lib/a.glsl", "#version 120\nfloat a() { return 1.0; }\n");
        String entry = """
                #version 150
                #include "lib/a.glsl"
                void main() {}
                """;
        IncludeResolver.ExpansionResult result =
                IncludeResolver.expandEntry(files, "entry.vsh", entry, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        long versions = result.text().lines().filter(l -> l.trim().startsWith("#version")).count();
        Assertions.assertEquals(1, versions, "exactly one #version may survive:\n" + result.text());
        Assertions.assertTrue(result.text().startsWith("#version 150"));
    }

    @Test
    public void testMemoPerGenerationAndEntry() {
        IncludeResolver.clearMemoForTests();
        try {
            IncludeResolver.PackFiles files = pack(
                    "lib/a.glsl", "float a() { return 1.0; }\n");
            String entry = "#include \"lib/a.glsl\"\nvoid main() {}\n";
            IncludeResolver.ExpansionResult first =
                    IncludeResolver.expandEntry(files, "entry.vsh", entry, 7);
            IncludeResolver.ExpansionResult second =
                    IncludeResolver.expandEntry(files, "entry.vsh", entry, 7);
            Assertions.assertEquals(first.text(), second.text());
            Assertions.assertEquals(1, IncludeResolver.memoSizeForTests(),
                    "same (generation, entry) must memoize");

            // A generation change (pack/dimension switch) must never reuse the older expansion.
            IncludeResolver.ExpansionResult third =
                    IncludeResolver.expandEntry(files, "entry.vsh", entry, 8);
            Assertions.assertEquals(first.text(), third.text());
            Assertions.assertEquals(2, IncludeResolver.memoSizeForTests(),
                    "a new generation must re-expand");

            // Failures are never memoized.
            IncludeResolver.ExpansionResult miss =
                    IncludeResolver.expandEntry(files, "other.vsh",
                            "#include \"/program/absent.glsl\"\n", 7);
            Assertions.assertFalse(miss.clean());
            Assertions.assertEquals(2, IncludeResolver.memoSizeForTests());
        } finally {
            IncludeResolver.clearMemoForTests();
        }
    }

    /**
     * Reads a fixture zip through a zip-backed {@link IncludeResolver.PackFiles}: the entry uses
     * the fix5 shape ({@code #include </program/gbuffers_terrain.glsl>}), the program file nests
     * an absolute and a relative include, and one include relies on case-insensitive matching.
     * The expanded unit must carry {@code #line} markers — and must compile to SPIR-V, while the
     * unexpanded entry must fail exactly the way the fix5 log shows.
     */
    @Test
    public void testFixtureZipWithProgramIncludes() throws Exception {
        Path dir = Files.createTempDirectory("vulkairis-include-fixture");
        Path zipPath = dir.resolve("FixturePack.zip");
        Map<String, String> zipEntries = new LinkedHashMap<>();
        zipEntries.put("shaders/gbuffers_terrain.vsh", """
                #version 120
                layout (location = 0) in vec3 Position;
                #include </program/gbuffers_terrain.glsl>
                void main() {
                    gl_Position = vec4(Position, 1.0) + terrainShade();
                }
                """);
        zipEntries.put("shaders/program/gbuffers_terrain.glsl", """
                #include "/lib/common.glsl"
                #include "terrain_helper.glsl"
                #include "/lib/caseutil.glsl"
                vec4 terrainShade() { return shadeTerrain() + helperValue() + caseValue(); }
                """);
        zipEntries.put("shaders/lib/common.glsl", """
                vec4 shadeTerrain() { return vec4(1.0, 0.5, 0.25, 1.0); }
                """);
        zipEntries.put("shaders/program/terrain_helper.glsl", """
                vec4 helperValue() { return vec4(0.25); }
                """);
        zipEntries.put("shaders/lib/CaseUtil.glsl", """
                vec4 caseValue() { return vec4(0.5); }
                """);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (Map.Entry<String, String> entry : zipEntries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        ZipPackFiles files = ZipPackFiles.open(zipPath);
        String entrySource = new String(
                readZipEntry(zipPath, "shaders/gbuffers_terrain.vsh"), StandardCharsets.UTF_8);
        IncludeResolver.ExpansionResult result = IncludeResolver.expandEntry(
                files, "gbuffers_terrain.vsh", entrySource, 0);
        Assertions.assertTrue(result.clean(), "errors: " + result.errors());
        Assertions.assertEquals(0, result.missing());
        Assertions.assertEquals(1, result.caseInsensitiveHits());
        Assertions.assertTrue(result.text().contains("vec4 shadeTerrain()"));
        Assertions.assertTrue(result.text().contains("vec4 helperValue()"));
        Assertions.assertTrue(result.text().contains("vec4 caseValue()"));
        Assertions.assertTrue(result.text().contains("#line 1 \"shaders/program/gbuffers_terrain.glsl\""),
                "program splice must be marked:\n" + result.text());

        // End to end: the expanded unit translates and compiles; the raw entry cannot.
        String preprocessed = ShaderTranslator.preprocessGlsl(result.text(), true);
        ByteBuffer spirv = SpirvCompiler.compileGlslToSpirv(
                "gbuffers_terrain.vsh", preprocessed, Shaderc.shaderc_glsl_vertex_shader);
        Assertions.assertNotNull(spirv);
        Assertions.assertTrue(spirv.remaining() > 0);

        String rawPreprocessed = ShaderTranslator.preprocessGlsl(entrySource, true);
        Assertions.assertThrows(RuntimeException.class, () -> SpirvCompiler.compileGlslToSpirv(
                "gbuffers_terrain.vsh", rawPreprocessed, Shaderc.shaderc_glsl_vertex_shader),
                "the unexpanded entry must still fail (fix5 shape)");
    }

    private static byte[] readZipEntry(Path zipPath, String name) throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(zipPath, Map.of())) {
            return Files.readAllBytes(zip.getPath(name));
        }
    }

    /**
     * Test-only pack reader over a fixture zip. Production never mounts a second copy of the
     * archive — it reads through Iris's loaded pack via
     * {@code IrisBridge.packFiles()} instead.
     */
    static final class ZipPackFiles implements IncludeResolver.PackFiles {
        private final Map<String, List<String>> files;

        private ZipPackFiles(Map<String, List<String>> files) {
            this.files = Map.copyOf(files);
        }

        static ZipPackFiles open(Path zipPath) throws IOException {
            Map<String, List<String>> files = new LinkedHashMap<>();
            try (FileSystem zip = FileSystems.newFileSystem(zipPath, Map.of())) {
                Path root = zip.getPath("/");
                List<Path> entries = new ArrayList<>();
                try (var stream = Files.walk(root)) {
                    stream.filter(Files::isRegularFile).forEach(entries::add);
                }
                for (Path path : entries) {
                    String name = path.toString().replace('\\', '/');
                    if (!name.endsWith(".glsl") && !name.endsWith(".vsh") && !name.endsWith(".fsh")) {
                        continue;
                    }
                    String rel = IncludeResolver.canonicalKey(name);
                    if (rel.isEmpty()) {
                        continue;
                    }
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    List<String> lines = new ArrayList<>(List.of(
                            text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)));
                    if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                        lines.remove(lines.size() - 1);
                    }
                    files.putIfAbsent(rel, List.copyOf(lines));
                }
            }
            return new ZipPackFiles(files);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String describe() {
            return "fixture-zip(" + files.size() + " files)";
        }

        @Override
        public List<String> readLines(String shadersRelativePath) {
            return files.get(shadersRelativePath);
        }

        @Override
        public Set<String> knownPaths() {
            return files.keySet();
        }
    }
}
