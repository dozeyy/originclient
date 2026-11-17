package com.vulkairis.client.gui.services;

import com.vulkairis.translation.ShaderReflection;
import com.vulkairis.translation.ShaderTranslator;
import com.vulkairis.translation.SpirvCompiler;
import com.vulkairis.translation.SpirvReflector;
import com.vulkairis.translation.SpirvValidator;
import org.lwjgl.util.shaderc.Shaderc;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pack smoke test (GUI plan Phase 5): runs a real archive's first vertex/fragment program
 * pair through the production translate/compile/validate/reflect path — no extraction, no
 * Iris pack loading, no game state. Badges show the outcome; the staged pack workflow
 * extends this to every program with progress.
 */
public final class PackSmokeTest {

    /** Maximum shader source bytes read from an archive. */
    static final int MAX_SOURCE_BYTES = 1_048_576;

    /** @param passed whether the pair reached complete reflection */
    public record SmokeResult(boolean passed, List<String> stages, String detail) {
    }

    private PackSmokeTest() {
    }

    /**
     * Tests one archive by its first program pair (preferred {@code gbuffers_terrain}, else
     * the first {@code .vsh}+{@code .fsh} pair under {@code shaders/}).
     */
    public static SmokeResult test(Path archive) {
        if (archive == null) {
            return new SmokeResult(false, List.of(), "no archive");
        }
        List<String> stages = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            String vertex = findProgram(zip, ".vsh", "gbuffers_terrain");
            String fragment = findProgram(zip, ".fsh", "gbuffers_terrain");
            if (vertex == null || fragment == null) {
                return new SmokeResult(false, List.of(),
                        "no vertex/fragment program pair under shaders/");
            }
            stages.add(vertex);
            stages.add(fragment);
            String vertSource = readBounded(zip, vertex);
            String fragSource = readBounded(zip, fragment);
            if (vertSource == null || fragSource == null) {
                return new SmokeResult(false, stages, "program sources unreadable");
            }

            String translatedVert = ShaderTranslator.preprocessGlsl(vertSource, true);
            String translatedFrag = ShaderTranslator.preprocessGlsl(fragSource, false);
            ByteBuffer vertSpirv;
            ByteBuffer fragSpirv;
            try {
                vertSpirv = SpirvCompiler.compileGlslToSpirv(vertex, translatedVert,
                        Shaderc.shaderc_glsl_vertex_shader);
                fragSpirv = SpirvCompiler.compileGlslToSpirv(fragment, translatedFrag,
                        Shaderc.shaderc_glsl_fragment_shader);
            } catch (RuntimeException e) {
                return new SmokeResult(false, stages, "compilation failed: " + e.getMessage());
            }
            if (!SpirvValidator.isValid(vertSpirv) || !SpirvValidator.isValid(fragSpirv)) {
                return new SmokeResult(false, stages, "SPIR-V validation failed");
            }
            ShaderReflection reflection = SpirvReflector.reflect(fragSpirv.duplicate());
            if (!reflection.isComplete() || reflection.getStageOutputs().isEmpty()) {
                return new SmokeResult(false, stages, "fragment reflection incomplete");
            }
            return new SmokeResult(true, stages,
                    vertSpirv.remaining() + "/" + fragSpirv.remaining() + " bytes, "
                            + reflection.getStageOutputs().size() + " output(s)");
        } catch (IOException e) {
            return new SmokeResult(false, stages, "unreadable archive: " + e.getMessage());
        } catch (RuntimeException e) {
            return new SmokeResult(false, stages, "test failed: " + e.getMessage());
        }
    }

    private static String findProgram(ZipFile zip, String suffix, String preferred) {
        String fallback = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            if (!name.startsWith("shaders/") || !name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                continue;
            }
            String base = name.substring(name.lastIndexOf('/') + 1);
            if (base.toLowerCase(Locale.ROOT).startsWith(preferred)) {
                return name;
            }
            if (fallback == null) {
                fallback = name;
            }
        }
        return fallback;
    }

    private static String readBounded(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        long size = entry.getSize();
        if (size < 0 || size > MAX_SOURCE_BYTES) {
            return null;
        }
        byte[] bytes;
        try (var in = zip.getInputStream(entry)) {
            bytes = in.readAllBytes();
        }
        if (bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
