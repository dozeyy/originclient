package com.vulkairis.diagnostics;

import com.vulkairis.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Gated artifact dumps for diagnostics (Phase 1).
 *
 * <p>Translated source, SPIR-V bytecode and reflection summaries are written to disk
 * <strong>only</strong> when the matching {@code VulkairisConfig} flag is explicitly enabled
 * (or when a caller passes an explicit failure dump). Nothing is dumped per-frame by default,
 * so normal runs produce zero dump I/O.
 *
 * <p>Dump failures never throw; they are logged once via {@link DiagnosticManager} dedup.
 */
public final class DiagnosticDumps {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Dumps");

    private DiagnosticDumps() {
    }

    /** @return the dump directory, overridable in tests via {@code -Dvulkairis.dumpDir=...} */
    static Path dumpDir() {
        String override = System.getProperty("vulkairis.dumpDir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of("run", "vulkairis-dumps");
    }

    static String sanitize(String name) {
        String base = (name == null || name.isBlank()) ? "unnamed" : name;
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Writes the translated GLSL source if {@code dumpTranslatedSource} is enabled.
     *
     * @return the written file, or {@code null} when dumping is disabled
     */
    public static Path dumpTranslatedSource(String shaderName, String translatedSource) {
        if (!isEnabled(ConfigFlag.TRANSLATED_SOURCE) || translatedSource == null) {
            return null;
        }
        return write(shaderName, "translated.glsl",
                translatedSource.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes the SPIR-V bytecode if {@code dumpSpirv} is enabled.
     *
     * @return the written file, or {@code null} when dumping is disabled
     */
    public static Path dumpSpirv(String shaderName, ByteBuffer spirv) {
        if (!isEnabled(ConfigFlag.SPIRV) || spirv == null || !spirv.hasRemaining()) {
            return null;
        }
        ByteBuffer copy = spirv.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return write(shaderName, "spv.bin", bytes);
    }

    /**
     * Writes a reflection summary if {@code dumpReflection} is enabled.
     *
     * @return the written file, or {@code null} when dumping is disabled
     */
    public static Path dumpReflection(String shaderName, String reflectionSummary) {
        if (!isEnabled(ConfigFlag.REFLECTION) || reflectionSummary == null) {
            return null;
        }
        return write(shaderName, "reflection.txt",
                reflectionSummary.getBytes(StandardCharsets.UTF_8));
    }

    private enum ConfigFlag {
        TRANSLATED_SOURCE,
        SPIRV,
        REFLECTION
    }

    private static boolean isEnabled(ConfigFlag flag) {
        try {
            return switch (flag) {
                case TRANSLATED_SOURCE -> ConfigManager.getConfig().isDumpTranslatedSource();
                case SPIRV -> ConfigManager.getConfig().isDumpSpirv();
                case REFLECTION -> ConfigManager.getConfig().isDumpReflection();
            };
        } catch (Throwable t) {
            return false;
        }
    }

    private static Path write(String shaderName, String suffix, byte[] bytes) {
        try {
            Path dir = dumpDir();
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitize(shaderName) + "-" + System.nanoTime() + "." + suffix);
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
            LOGGER.info("[Vulkairis] Diagnostic dump written: {}", file);
            return file;
        } catch (Throwable t) {
            DiagnosticManager.logStructured(DiagnosticManager.Category.RESOURCE,
                    DiagnosticManager.Phase.LIFECYCLE,
                    "Diagnostic dump failed", t.getMessage(), t,
                    shaderName, null, null, -1, null,
                    "Free disk space / check write permissions for the dump directory");
            return null;
        }
    }
}
