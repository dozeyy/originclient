package com.vulkairis.client.gui.services;

import com.vulkairis.translation.TranslationWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Filesystem-driven shader-pack discovery (GUI plan Phase 5).
 *
 * <p>The configured shader-pack folder is the <em>only</em> source of truth: every card is
 * built from a current scan result, never from a built-in list of popular packs. Only
 * readable, valid {@code .zip} archives become selectable packs (folder-based packs stay out
 * until verified against the exact Iris version); corrupt, unreadable, duplicate and
 * unrelated archives surface as scan warnings with filename and reason. Nothing is
 * extracted — validation reads the central directory plus bounded metadata only.</p>
 */
public final class ShaderPackService {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/PackService");

    /** Maximum central-directory entries inspected per archive (zip-bomb guard). */
    static final int MAX_SCAN_ENTRIES = 20_000;
    /** Maximum bytes read for optional metadata files. */
    static final int MAX_METADATA_BYTES = 65_536;

    /** One selectable pack built from a real scan result. */
    public record DiscoveredPack(
            String fileName,
            String displayName,
            long sizeBytes,
            long lastModifiedMillis,
            Optional<String> author,
            Optional<String> version) {

        /** @return stable identity within one folder scan */
        public String stableId() {
            return fileName;
        }

        /** @return content stamp for change detection (size plus mtime) */
        public String contentStamp() {
            return sizeBytes + ":" + lastModifiedMillis;
        }
    }

    /** One non-selectable file with its reason. */
    public record ScanWarning(String fileName, String reason) {
    }

    /** Immutable scan result published to the UI thread. */
    public record ScanResult(
            Path directory,
            List<DiscoveredPack> packs,
            List<ScanWarning> warnings,
            long scannedAtMillis,
            boolean directoryMissing,
            String error) {

        public ScanResult {
            packs = packs == null ? List.of() : List.copyOf(packs);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** @return whether the scan itself ran (as opposed to a folder error) */
        public boolean scanned() {
            return !directoryMissing && error == null;
        }

        public static ScanResult missing(Path directory, String error) {
            return new ScanResult(directory, List.of(), List.of(),
                    System.currentTimeMillis(), true, error);
        }
    }

    private ShaderPackService() {
    }

    /**
     * Scans synchronously (call off the render thread, or use {@link #scanAsync}). Never
     * throws: folder errors land in the result.
     */
    public static ScanResult scan(Path directory) {
        if (directory == null) {
            return ScanResult.missing(null, "no shader-pack folder configured");
        }
        if (isModsFolder(directory)) {
            return new ScanResult(directory, List.of(), List.of(),
                    System.currentTimeMillis(), false,
                    "refusing to scan the Fabric mods folder: shader packs come only from the"
                            + " configured Iris shader-pack folder, never from run/mods");
        }
        try {
            if (!Files.isDirectory(directory)) {
                return ScanResult.missing(directory,
                        "shader-pack folder is missing: " + directory);
            }
        } catch (Throwable t) {
            return ScanResult.missing(directory, "cannot access folder: " + t.getMessage());
        }

        Map<String, Path> candidates = new TreeMap<>();
        List<ScanWarning> warnings = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    String name = entry.getFileName().toString();
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    if (lowerName.endsWith(".jar")) {
                        // Fabric mods are never shader packs, even when they sit next
                        // to the folder. Skip silently: warning about every mod jar
                        // would be noise, and listing one as a pack would be a lie.
                        continue;
                    }
                    if (!lowerName.endsWith(".zip")) {
                        continue;
                    }
                    if (candidates.putIfAbsent(name, entry) != null) {
                        warnings.add(new ScanWarning(name, "duplicate filename; skipped"));
                    }
                } catch (Throwable t) {
                    warnings.add(new ScanWarning(entry.getFileName().toString(),
                            "unreadable: " + t.getMessage()));
                }
            }
        } catch (Throwable t) {
            return new ScanResult(directory, List.of(), warnings,
                    System.currentTimeMillis(), false,
                    "scan failed: " + t.getMessage());
        }

        List<DiscoveredPack> packs = new ArrayList<>();
        Map<String, String> seenNames = new TreeMap<>();
        Map<String, String> seenContent = new TreeMap<>();
        for (Map.Entry<String, Path> candidate : candidates.entrySet()) {
            String fileName = candidate.getKey();
            Path path = candidate.getValue();
            try {
                DiscoveredPack pack = validate(path);
                if (pack == null) {
                    warnings.add(new ScanWarning(fileName,
                            "not a shader pack (no shader programs under shaders/)"));
                    continue;
                }
                String duplicateOf = seenNames.putIfAbsent(
                        pack.displayName().toLowerCase(Locale.ROOT), fileName);
                if (duplicateOf != null) {
                    warnings.add(new ScanWarning(fileName,
                            "duplicate of " + duplicateOf + "; only the first is selectable"));
                    continue;
                }
                String contentHash = contentHash(path);
                String sameContent = contentHash != null
                        ? seenContent.putIfAbsent(contentHash, fileName) : null;
                if (sameContent != null) {
                    warnings.add(new ScanWarning(fileName,
                            "duplicate content of " + sameContent + "; only the first is selectable"));
                    continue;
                }
                packs.add(pack);
            } catch (Throwable t) {
                warnings.add(new ScanWarning(fileName,
                        "unreadable archive: " + t.getMessage()));
                LOGGER.debug("[Vulkairis/PackService] Skipping {}: {}", fileName, t.getMessage());
            }
        }
        return new ScanResult(directory, packs, warnings, System.currentTimeMillis(), false, null);
    }

    /** Scans off-thread; install the result only when the generation still matches. */
    public static TranslationWorker.Task<ScanResult> scanAsync(long generation, Path directory) {
        return TranslationWorker.submit("pack-scan", generation, () -> scan(directory));
    }

    /**
     * Validates one archive: readable zip with at least one shader program under
     * {@code shaders/}. Returns {@code null} for unrelated zips.
     */
    static DiscoveredPack validate(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String displayName = fileName.substring(0, fileName.length() - ".zip".length());
        long size = Files.size(path);
        long modified = Files.getLastModifiedTime(path).toMillis();

        boolean hasPrograms = false;
        int entries = 0;
        String author = null;
        String version = null;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements()) {
                ZipEntry entry = names.nextElement();
                entries++;
                if (entries > MAX_SCAN_ENTRIES) {
                    throw new IOException("archive indexes too many entries");
                }
                String entryName = entry.getName();
                if (!entry.isDirectory() && entryName.startsWith("shaders/")
                        && isProgramFile(entryName)) {
                    hasPrograms = true;
                }
                if (!entry.isDirectory() && author == null
                        && isMetadataFile(entryName)
                        && entry.getSize() >= 0 && entry.getSize() <= MAX_METADATA_BYTES) {
                    Properties properties = new Properties();
                    try (InputStream in = zip.getInputStream(entry)) {
                        properties.load(in);
                    } catch (Throwable ignored) {
                    }
                    author = trimToNull(properties.getProperty("author"));
                    version = trimToNull(properties.getProperty("version"));
                }
                if (hasPrograms && author != null) {
                    break;
                }
            }
        }
        if (!hasPrograms) {
            return null;
        }
        return new DiscoveredPack(fileName, displayName, size, modified,
                Optional.ofNullable(author), Optional.ofNullable(version));
    }

    private static boolean isProgramFile(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".gsh")
                || lower.endsWith(".csh") || lower.endsWith(".tcs") || lower.endsWith(".tes")
                || lower.endsWith(".tesc") || lower.endsWith(".tese");
    }

    private static boolean isMetadataFile(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.equals("shaderpack.properties") || lower.equals("pack.properties");
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Content hash for duplicate detection (same bytes under different names). Failures yield
     * {@code null} (no duplicate claim) instead of breaking the scan.
     */
    static String contentHash(Path path) {
        try (var in = new java.io.BufferedInputStream(Files.newInputStream(path))) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** @return packs from the result, or an empty list when the scan did not run */
    public static List<DiscoveredPack> packsOrEmpty(ScanResult result) {
        if (result == null || !result.scanned()) {
            return List.of();
        }
        return Collections.unmodifiableList(result.packs());
    }

    /**
     * Phase F guard: the Fabric mods folder ({@code run/mods}) is never a shader-pack
     * source. Refuses when the directory <em>is</em> the game's {@code mods} folder, and —
     * defensively, since Iris names its folder {@code shaderpacks} — when the last path
     * segment itself is {@code mods}. Fabric mod jars must never appear as pack cards.
     */
    static boolean isModsFolder(Path directory) {
        if (directory == null) {
            return false;
        }
        try {
            Path fileName = directory.getFileName();
            if (fileName != null && fileName.toString().equalsIgnoreCase("mods")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            Path modsDir = net.fabricmc.loader.api.FabricLoader.getInstance()
                    .getGameDir().resolve("mods").toAbsolutePath().normalize();
            return directory.toAbsolutePath().normalize().startsWith(modsDir);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
