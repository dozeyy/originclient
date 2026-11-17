package com.vulkairis.test;

import com.vulkairis.client.gui.services.ShaderPackService;
import com.vulkairis.translation.TranslationWorker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GUI plan Phase 5 + Revision 2 §12.4: folder-driven discovery. Cards come only from the
 * live scan; corrupt, duplicate and unrelated archives warn without becoming selectable;
 * lifecycle changes reconcile on rescan.
 */
public class ShaderPackServiceTest {

    private static final String VERT = """
            #version 150 core
            in vec3 iris_Position;
            void main() {
                gl_Position = vec4(iris_Position, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 150 core
            out vec4 fragColor;
            void main() {
                fragColor = vec4(1.0);
            }
            """;

    private Path tempDir() throws Exception {
        return Files.createTempDirectory("vulkairis-packscan-test");
    }

    private void cleanup(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void writePack(Path dir, String fileName, String vert, String frag) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            if (vert != null) {
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
                out.write(vert.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            if (frag != null) {
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.fsh"));
                out.write(frag.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private void writeUnrelatedZip(Path dir, String fileName) throws Exception {
        try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve(fileName)))) {
            out.putNextEntry(new ZipEntry("readme.txt"));
            out.write("not a shader pack".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    @Test
    public void testTwoInstalledPacks() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "ComplementaryReimagined_r5.8.1.zip", VERT, FRAG);
            writePack(dir, "BSL_v10.1.3.zip", VERT + "\n// bsl\n", FRAG);
            var result = ShaderPackService.scan(dir);
            Assertions.assertTrue(result.scanned());
            Assertions.assertEquals(2, result.packs().size(),
                    "exactly the two discovered packs must appear");
            List<String> names = result.packs().stream()
                    .map(ShaderPackService.DiscoveredPack::displayName).toList();
            Assertions.assertTrue(names.contains("ComplementaryReimagined_r5.8.1"));
            Assertions.assertTrue(names.contains("BSL_v10.1.3"));
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testNoExtraPacks() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "ComplementaryReimagined_r5.8.1.zip", VERT, FRAG);
            writePack(dir, "BSL_v10.1.3.zip", VERT + "\n// bsl\n", FRAG);
            var result = ShaderPackService.scan(dir);
            // Sildur's is absent from the folder: no card may appear after scan or rescan.
            Assertions.assertTrue(result.packs().stream().noneMatch(pack ->
                            pack.displayName().toLowerCase(java.util.Locale.ROOT).contains("sildur")
                                    || pack.fileName().toLowerCase(java.util.Locale.ROOT).contains("sildur")),
                    "absent packs must never appear");
            var rescan = ShaderPackService.scan(dir);
            Assertions.assertEquals(result.packs().size(), rescan.packs().size());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testInvalidFilesWarnWithoutCards() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Good.zip", VERT, FRAG);
            Files.write(dir.resolve("Corrupt.zip"), new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00});
            writeUnrelatedZip(dir, "notes.zip");
            // Same content under another name: a duplicate, not a second pack.
            Files.copy(dir.resolve("Good.zip"), dir.resolve("Good2.zip"));
            Files.writeString(dir.resolve("readme.txt"), "not an archive");

            var result = ShaderPackService.scan(dir);
            Assertions.assertEquals(1, result.packs().size(),
                    "only the valid pack is selectable, got: " + result.packs());
            Assertions.assertEquals("Good", result.packs().get(0).displayName());
            Assertions.assertTrue(result.warnings().size() >= 3,
                    "corrupt, unrelated and duplicate files must warn: " + result.warnings());
            Assertions.assertTrue(result.warnings().stream()
                            .anyMatch(w -> w.fileName().equals("Corrupt.zip")),
                    "warnings carry filenames: " + result.warnings());
            Assertions.assertTrue(result.warnings().stream()
                            .anyMatch(w -> w.fileName().equals("Good2.zip")
                                    && w.reason().contains("duplicate")),
                    "content duplicates must be named: " + result.warnings());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testFileLifecycle() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Alpha.zip", VERT, FRAG);
            var first = ShaderPackService.scan(dir);
            Assertions.assertEquals(1, first.packs().size());

            writePack(dir, "Beta.zip", VERT + "\n// beta\n", FRAG);
            var added = ShaderPackService.scan(dir);
            Assertions.assertEquals(2, added.packs().size(), "added files appear on rescan");

            Files.move(dir.resolve("Alpha.zip"), dir.resolve("Gamma.zip"));
            var renamed = ShaderPackService.scan(dir);
            Assertions.assertTrue(renamed.packs().stream()
                            .anyMatch(p -> p.fileName().equals("Gamma.zip")),
                    "renames appear as the new name");
            Assertions.assertTrue(renamed.packs().stream()
                            .noneMatch(p -> p.fileName().equals("Alpha.zip")),
                    "old names disappear");

            Files.delete(dir.resolve("Beta.zip"));
            var removed = ShaderPackService.scan(dir);
            Assertions.assertEquals(1, removed.packs().size(), "removed files disappear");

            // Replace Gamma.zip with different content: its stamp must change.
            String before = removed.packs().get(0).contentStamp();
            try (var out = new ZipOutputStream(Files.newOutputStream(dir.resolve("Gamma.zip")))) {
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
                byte[] bigger = (VERT + "\n// extra\n").getBytes(StandardCharsets.UTF_8);
                out.write(bigger);
                out.closeEntry();
                out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.fsh"));
                out.write(FRAG.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            var replaced = ShaderPackService.scan(dir);
            String after = replaced.packs().stream()
                    .filter(p -> p.fileName().equals("Gamma.zip"))
                    .findFirst().orElseThrow().contentStamp();
            Assertions.assertNotEquals(before, after, "replaced files change stamps");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testEmptyAndMissingFolders() throws Exception {
        Path dir = tempDir();
        try {
            var empty = ShaderPackService.scan(dir);
            Assertions.assertTrue(empty.scanned());
            Assertions.assertTrue(empty.packs().isEmpty(), "empty folder shows no cards");
            Assertions.assertTrue(empty.warnings().isEmpty());

            var missing = ShaderPackService.scan(dir.resolve("nope"));
            Assertions.assertFalse(missing.scanned());
            Assertions.assertTrue(missing.directoryMissing());
            Assertions.assertFalse(missing.error().isBlank());
            Assertions.assertTrue(ShaderPackService.packsOrEmpty(missing).isEmpty());
            Assertions.assertTrue(ShaderPackService.packsOrEmpty(null).isEmpty());
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testAsyncScanInstalls() throws Exception {
        Path dir = tempDir();
        try {
            writePack(dir, "Async.zip", VERT, FRAG);
            var task = ShaderPackService.scanAsync(7701L, dir);
            Optional<ShaderPackService.ScanResult> result =
                    TranslationWorker.takeIfCurrent(task, 7701L, 10_000L);
            Assertions.assertTrue(result.isPresent());
            Assertions.assertEquals(1, result.get().packs().size());

            var stale = ShaderPackService.scanAsync(7702L, dir);
            Assertions.assertTrue(
                    TranslationWorker.takeIfCurrent(stale, 7703L, 10_000L).isEmpty(),
                    "stale scan generations must not install");
        } finally {
            cleanup(dir);
        }
    }

    @Test
    public void testTruncatedZipIsUnreadable() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(buffer)) {
            out.putNextEntry(new ZipEntry("shaders/gbuffers_terrain.vsh"));
            out.write(VERT.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        byte[] bytes = buffer.toByteArray();
        Path dir = tempDir();
        try {
            Files.write(dir.resolve("Truncated.zip"),
                    java.util.Arrays.copyOf(bytes, bytes.length / 2));
            var result = ShaderPackService.scan(dir);
            Assertions.assertTrue(result.packs().isEmpty());
            Assertions.assertTrue(result.warnings().stream()
                    .anyMatch(w -> w.fileName().equals("Truncated.zip")));
        } finally {
            cleanup(dir);
        }
    }
}
