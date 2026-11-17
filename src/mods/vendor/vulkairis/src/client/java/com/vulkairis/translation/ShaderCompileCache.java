package com.vulkairis.translation;

import com.vulkairis.diagnostics.DiagnosticManager;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SPIR-V compile cache (Phase 12, extended Phase C): validated bytecode keyed by a
 * Vitrail-style module key — translated-source hash, shaderc stage, preprocessor defines,
 * compiler profile digest and the full dependency version stamp (mod/game/loader/LWJGL).
 * Only successful compilations are stored — invalid or unsupported stages never produce a
 * cache entry. Callers receive independent duplicates; the canonical buffer stays cached.
 * Bounded LRU in memory with hit/miss stats, plus bounded on-disk persistence of the
 * SPIR-V words so a second load of the same pack is cache-hot.
 *
 * <p>Disk layout: one raw-words file per full key ({@code vkc-<sha256(key)>.spv}) under
 * {@code <gameDir>/vulkairis/shader_cache}. Old entries from a previous mod/game/loader
 * version hash to different filenames and are therefore never hit; the directory is pruned
 * to a bounded file count and each entry is size-capped. Persistence is best-effort and
 * never throws into the compile path; it can be opted out with
 * {@code -Dvulkairis.compileCache.persist=false} or {@link #setPersistenceEnabled(boolean)}.
 * Headless environments (no game directory) silently skip disk I/O and stay memory-only.
 */
public final class ShaderCompileCache {

    private static final int MAX_ENTRIES = 64;
    /** Largest single module kept in memory or on disk (256 KiB of SPIR-V words). */
    private static final int MAX_ENTRY_BYTES = 256 * 1024;
    /** Largest module written to disk (512 KiB); anything bigger stays memory-only. */
    private static final long MAX_DISK_ENTRY_BYTES = 512L * 1024L;
    /** Bounded persisted module count; oldest files are pruned first. */
    private static final int MAX_DISK_ENTRIES = 128;
    private static final String DISK_PREFIX = "vkc-";
    private static final String DISK_SUFFIX = ".spv";

    /** Pinned fallbacks when the runtime cannot be introspected (headless tests). */
    private static final String PINNED_MOD_VERSION = "0.8.12+v21.0.5.0";
    private static final String PINNED_GAME_VERSION = "1.21.1";
    private static final String PINNED_LOADER_VERSION = "0.19.5";
    private static final String PINNED_LWJGL_VERSION = "3.3.3";

    private static final Map<String, ByteBuffer> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ByteBuffer> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private static final AtomicLong HITS = new AtomicLong(0);
    private static final AtomicLong MISSES = new AtomicLong(0);
    private static final AtomicLong DISK_HITS = new AtomicLong(0);
    private static final AtomicLong DISK_WRITES = new AtomicLong(0);

    private static volatile boolean persistenceEnabled =
            !"false".equalsIgnoreCase(System.getProperty("vulkairis.compileCache.persist", "true"));
    private static volatile Path persistenceDirOverride = null;
    private static volatile boolean persistenceDirResolved = false;
    private static volatile Path persistenceDirCached = null;

    private static volatile String versionStampCached = null;

    private ShaderCompileCache() {
    }

    /** @return the legacy cache key (translated-source hash, kind, profile digest) */
    public static String key(String translatedHash, int shadercKind, String profileDigest) {
        return (translatedHash != null ? translatedHash : "null") + "|" + shadercKind + "|"
                + (profileDigest != null ? profileDigest : "null");
    }

    /**
     * @return the full module key: any compile-affecting change (source, stage, defines,
     *         profile or dependency versions) alters it, so stale entries can never hit
     */
    public static String key(String sourceHash, int shadercKind, String definesDigest,
                             String profileDigest, String versionStamp) {
        return "src=" + (sourceHash != null ? sourceHash : "null")
                + "|stage=" + shadercKind
                + "|defines=" + (definesDigest != null ? definesDigest : "nodefines")
                + "|profile=" + (profileDigest != null ? profileDigest : "null")
                + "|versions=" + (versionStamp != null ? versionStamp : "unknown");
    }

    /**
     * @return a short stable digest of the preprocessor defines affecting compilation;
     *         {@code "nodefines"} when empty so define-free compiles share entries
     */
    public static String definesDigest(Map<String, String> defines) {
        if (defines == null || defines.isEmpty()) {
            return "nodefines";
        }
        List<String> names = new ArrayList<>();
        for (String name : defines.keySet()) {
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return "nodefines";
        }
        Collections.sort(names);
        StringBuilder canonical = new StringBuilder();
        for (String name : names) {
            canonical.append(name).append('=')
                    .append(String.valueOf(defines.get(name))).append(';');
        }
        String hash = DiagnosticManager.hashSource(canonical.toString());
        return "defines-" + (hash != null ? hash
                : Integer.toHexString(canonical.toString().hashCode()));
    }

    /**
     * @return the dependency version stamp baked into every full key
     *         ({@code mod=…|mc=…|loader=…|lwjgl=…}); pinned fallbacks apply headless
     */
    public static String versionStamp() {
        String cached = versionStampCached;
        if (cached != null) {
            return cached;
        }
        String stamp = computeVersionStamp();
        versionStampCached = stamp;
        return stamp;
    }

    private static String computeVersionStamp() {
        String mod = PINNED_MOD_VERSION;
        String game = PINNED_GAME_VERSION;
        String loader = PINNED_LOADER_VERSION;
        try {
            FabricLoader fabric = FabricLoader.getInstance();
            try {
                loader = fabric.getModContainer("fabricloader")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse(loader);
            } catch (Throwable ignored) {
            }
            try {
                mod = fabric.getModContainer("vulkairis")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse(mod);
            } catch (Throwable ignored) {
            }
            try {
                game = fabric.getModContainer("minecraft")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString())
                        .orElse(game);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
            // Headless (no loader runtime): pinned fallbacks keep the key stable.
        }
        String lwjgl = PINNED_LWJGL_VERSION;
        try {
            String runtime = org.lwjgl.Version.getVersion();
            if (runtime != null && !runtime.isBlank()) {
                lwjgl = runtime;
            }
        } catch (Throwable ignored) {
        }
        return "mod=" + mod + "|mc=" + game + "|loader=" + loader + "|lwjgl=" + lwjgl;
    }

    /**
     * @return an independent duplicate positioned at 0, or {@code null} on miss (counted).
     *         Memory is consulted first; persisted SPIR-V words are re-admitted on disk hit.
     */
    public static ByteBuffer lookup(String key) {
        ByteBuffer cached = CACHE.get(key);
        if (cached != null) {
            HITS.incrementAndGet();
            ByteBuffer view = cached.duplicate();
            view.position(0);
            return view;
        }
        ByteBuffer fromDisk = loadFromDisk(key);
        if (fromDisk != null) {
            return fromDisk;
        }
        MISSES.incrementAndGet();
        return null;
    }

    /**
     * Stores validated bytecode in memory and best-effort on disk; null/empty/oversized
     * inputs are never stored and persistence failures never throw.
     */
    public static void store(String key, ByteBuffer spirv) {
        if (key == null || spirv == null || !spirv.hasRemaining()) {
            return;
        }
        if (spirv.remaining() > MAX_ENTRY_BYTES) {
            return;
        }
        ByteBuffer canonical = ByteBuffer.allocateDirect(spirv.remaining());
        int position = spirv.position();
        canonical.put(spirv.duplicate());
        canonical.flip();
        spirv.position(position);
        CACHE.put(key, canonical);
        persistToDisk(key, canonical);
    }

    /** @return whether a successful result may be cached (success only, validated bytes) */
    public static boolean shouldCache(ShaderCompileResult result) {
        return result != null && result.succeeded();
    }

    /** @return cache hits so far */
    public static long hits() {
        return HITS.get();
    }

    /** @return cache misses so far */
    public static long misses() {
        return MISSES.get();
    }

    /** @return disk re-admissions so far (cold memory, warm disk) */
    public static long diskHits() {
        return DISK_HITS.get();
    }

    /** @return successful disk writes so far */
    public static long diskWrites() {
        return DISK_WRITES.get();
    }

    /** @return whether disk persistence is currently enabled */
    public static boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    /** Opt-out flag for disk persistence; memory caching is unaffected. */
    public static void setPersistenceEnabled(boolean enabled) {
        persistenceEnabled = enabled;
    }

    /** Overrides the persistence directory (tests); {@code null} restores lazy resolution. */
    public static void setPersistenceDirForTests(Path dir) {
        persistenceDirOverride = dir;
    }

    /** Clears entries and stats; tests only. Disk files are left alone (see purge below). */
    public static void clearForTests() {
        CACHE.clear();
        HITS.set(0);
        MISSES.set(0);
        DISK_HITS.set(0);
        DISK_WRITES.set(0);
    }

    /** Drops memory entries but keeps stats; tests only (models a cold memory / warm disk). */
    public static void evictMemoryForTests() {
        CACHE.clear();
    }

    /**
     * Deletes persisted {@code .spv} files, but only under an explicit test override
     * directory — never the real game cache. Tests only.
     */
    public static void purgeDiskForTests() {
        Path dir = persistenceDirOverride;
        if (dir == null) {
            return;
        }
        try {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.list(dir)) {
                for (Path file : stream.toList()) {
                    String name = file.getFileName().toString();
                    if (name.startsWith(DISK_PREFIX) && name.endsWith(DISK_SUFFIX)) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Path persistenceDir() {
        Path override = persistenceDirOverride;
        if (override != null) {
            return override;
        }
        if (!persistenceDirResolved) {
            synchronized (ShaderCompileCache.class) {
                if (!persistenceDirResolved) {
                    persistenceDirCached = gameCacheDir();
                    persistenceDirResolved = true;
                }
            }
        }
        return persistenceDirCached;
    }

    /**
     * Resolves the on-disk cache lazily: touching FabricLoader at class-load time crashes
     * headless unit tests (no game directory), so a miss here simply disables disk I/O.
     */
    private static Path gameCacheDir() {
        try {
            return FabricLoader.getInstance().getGameDir()
                    .resolve("vulkairis").resolve("shader_cache");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String diskFileName(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return DISK_PREFIX + hex + DISK_SUFFIX;
        } catch (Throwable t) {
            return DISK_PREFIX + Integer.toHexString(key.hashCode()) + DISK_SUFFIX;
        }
    }

    private static void persistToDisk(String key, ByteBuffer canonical) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            Path dir = persistenceDir();
            if (dir == null) {
                return;
            }
            int bytes = canonical.remaining();
            if (bytes <= 0 || bytes > MAX_DISK_ENTRY_BYTES || (bytes & 3) != 0) {
                return;
            }
            Files.createDirectories(dir);
            byte[] data = new byte[bytes];
            canonical.duplicate().get(data);
            Path target = dir.resolve(diskFileName(key));
            Path tmp = dir.resolve(diskFileName(key) + ".tmp");
            Files.write(tmp, data, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            DISK_WRITES.incrementAndGet();
            pruneDisk(dir);
        } catch (Throwable ignored) {
            // Best-effort: a failed write must never fail the compile.
        }
    }

    private static ByteBuffer loadFromDisk(String key) {
        if (!persistenceEnabled) {
            return null;
        }
        try {
            Path dir = persistenceDir();
            if (dir == null) {
                return null;
            }
            Path file = dir.resolve(diskFileName(key));
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long size = Files.size(file);
            if (size <= 0 || size > MAX_DISK_ENTRY_BYTES || (size & 3) != 0) {
                return null;
            }
            byte[] data = Files.readAllBytes(file);
            if (data.length == 0 || (data.length & 3) != 0 || data.length > MAX_ENTRY_BYTES) {
                return null;
            }
            ByteBuffer canonical = ByteBuffer.allocateDirect(data.length);
            canonical.put(data);
            canonical.flip();
            CACHE.put(key, canonical);
            DISK_HITS.incrementAndGet();
            ByteBuffer view = canonical.duplicate();
            view.position(0);
            return view;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void pruneDisk(Path dir) {
        try (var stream = Files.list(dir)) {
            List<Path> entries = new ArrayList<>();
            for (Path file : stream.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(DISK_PREFIX) && name.endsWith(DISK_SUFFIX)
                        && !name.endsWith(".tmp")) {
                    entries.add(file);
                }
            }
            if (entries.size() <= MAX_DISK_ENTRIES) {
                return;
            }
            entries.sort((a, b) -> {
                try {
                    return Long.compare(Files.getLastModifiedTime(a).toMillis(),
                            Files.getLastModifiedTime(b).toMillis());
                } catch (Throwable t) {
                    return 0;
                }
            });
            for (int i = 0; i < entries.size() - MAX_DISK_ENTRIES; i++) {
                try {
                    Files.deleteIfExists(entries.get(i));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
