package com.vulkairis.vulkan.pipeline;

import com.vulkairis.backend.vulkanmod.VulkanBackendAdapter;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Manages graphics pipeline compilation, driver-level VkPipelineCache persistence,
 * and in-memory pipeline caching to eliminate stutters.
 */
public class PipelineManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/PipelineManager");

    /**
     * Resolves the cache file lazily: touching FabricLoader at class-load time crashes headless
     * unit tests (no game directory), the same failure mode {@code ConfigManager} already fixed.
     */
    private static Path cacheFile() {
        try {
            return FabricLoader.getInstance().getGameDir().resolve("vulkairis")
                    .resolve("pipeline_cache.bin");
        } catch (Throwable t) {
            return null;
        }
    }


    private static long vkPipelineCache = 0L;
    private static final Map<PipelineKey, Long> MEMORY_PIPELINE_CACHE = new ConcurrentHashMap<>();
    private static boolean initialized = false;
    /** Phase 12: in-memory pipeline cache hits/misses for the performance report. */
    private static final java.util.concurrent.atomic.AtomicLong MEMORY_HITS =
            new java.util.concurrent.atomic.AtomicLong(0);
    private static final java.util.concurrent.atomic.AtomicLong MEMORY_MISSES =
            new java.util.concurrent.atomic.AtomicLong(0);

    public static synchronized long getOrCreatePipelineCache() {
        if (initialized && vkPipelineCache != 0L) return vkPipelineCache;

        // GUI plan Phase 4: the cache control is real. Disabled caches bypass cleanly.
        try {
            if (!com.vulkairis.config.VulkairisConfigManager.getConfig().isPipelineCacheEnabled()) {
                return 0L;
            }
        } catch (Throwable ignored) {
        }

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) {
            LOGGER.warn("[Vulkairis] Cannot initialize VkPipelineCache: VkDevice not available");
            return 0L;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer initialData = loadCacheDataFromDisk();

            VkPipelineCacheCreateInfo createInfo = VkPipelineCacheCreateInfo.calloc(stack)
                    .sType(VK10.VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO);

            if (initialData != null && initialData.hasRemaining()) {
                createInfo.pInitialData(initialData);
                LOGGER.info("[Vulkairis] Populating VkPipelineCache with {} bytes of cached driver data",
                        initialData.remaining());
            }

            LongBuffer pCache = stack.mallocLong(1);
            int result = VK10.vkCreatePipelineCache(device, createInfo, null, pCache);
            if (result != VK10.VK_SUCCESS) {
                LOGGER.error("[Vulkairis] Failed to create VkPipelineCache, Vulkan error: {}", result);
                return 0L;
            }

            vkPipelineCache = pCache.get(0);
            initialized = true;
            LOGGER.info("[Vulkairis] Initialized driver VkPipelineCache 0x{}", Long.toHexString(vkPipelineCache));
            return vkPipelineCache;
        }
    }

    private static ByteBuffer loadCacheDataFromDisk() {
        try {
            Path cacheFile = cacheFile();
            if (cacheFile == null || !Files.exists(cacheFile)) return null;

            byte[] bytes = Files.readAllBytes(cacheFile);
            if (!isUsableCacheBlob(bytes)) {
                LOGGER.warn("[Vulkairis] Discarding invalid pipeline cache blob ({} bytes)",
                        bytes.length);
                return null;
            }

            ByteBuffer directBuffer = ByteBuffer.allocateDirect(bytes.length);
            directBuffer.put(bytes);
            directBuffer.flip();
            return directBuffer;
        } catch (Exception e) {
            LOGGER.warn("[Vulkairis] Could not load pipeline cache from disk: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validates a pipeline-cache blob against Vulkan's expected header before it reaches
     * {@code vkCreatePipelineCache}: 32-byte minimum, length field matching the blob size,
     * header version 1. Anything else (truncation, version drift, crash-torn writes) is
     * discarded so the driver falls back to a clean rebuild instead of rejecting the cache.
     */
    public static boolean isUsableCacheBlob(byte[] bytes) {
        if (bytes == null || bytes.length < 32) {
            return false;
        }
        ByteBuffer header = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int declaredLength = header.getInt(0);
        int version = header.getInt(4);
        if (declaredLength != bytes.length) {
            return false;
        }
        return version == 1;
    }

    /**
     * Writes cache data atomically (temp file plus atomic move with a plain-move fallback) so
     * a crash mid-write can never leave a torn blob behind for the next launch to choke on.
     */
    public static boolean writeAtomically(Path target, byte[] bytes) {
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "pipeline_cache", ".tmp");
            try {
                Files.write(temp, bytes, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            LOGGER.warn("[Vulkairis] Atomic cache write notice: {}", e.getMessage());
            return false;
        }
    }

    public static synchronized void savePipelineCacheToDisk() {
        if (vkPipelineCache == 0L) return;

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (!VulkanBackendAdapter.isLive(device)) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer pDataSize = stack.mallocPointer(1);
            int res1 = VK10.vkGetPipelineCacheData(device, vkPipelineCache, pDataSize, null);
            if (res1 != VK10.VK_SUCCESS) return;

            long dataSize = pDataSize.get(0);
            if (dataSize <= 0) return;

            ByteBuffer dataBuffer = ByteBuffer.allocateDirect((int) dataSize);
            int res2 = VK10.vkGetPipelineCacheData(device, vkPipelineCache, pDataSize, dataBuffer);
            if (res2 != VK10.VK_SUCCESS) return;

            Path cacheFile = cacheFile();
            if (cacheFile == null) return;
            if (!Files.exists(cacheFile.getParent())) {
                Files.createDirectories(cacheFile.getParent());
            }

            byte[] bytes = new byte[(int) dataSize];
            dataBuffer.get(bytes);
            if (writeAtomically(cacheFile, bytes)) {
                LOGGER.info("[Vulkairis] Saved {} bytes of compiled GPU pipelines to {}", dataSize, cacheFile);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis] Pipeline cache save notice: {}", t.getMessage());
        }
    }

    public static long getOrCreatePipeline(PipelineKey key, LongSupplier pipelineCreator) {
        boolean cacheEnabled = true;
        try {
            cacheEnabled = com.vulkairis.config.VulkairisConfigManager.getConfig()
                    .isPipelineCacheEnabled();
        } catch (Throwable ignored) {
        }
        if (!cacheEnabled) {
            return pipelineCreator.getAsLong();
        }
        Long cached = MEMORY_PIPELINE_CACHE.get(key);
        if (cached != null) {
            MEMORY_HITS.incrementAndGet();
            return cached;
        }
        MEMORY_MISSES.incrementAndGet();
        return MEMORY_PIPELINE_CACHE.computeIfAbsent(key, k -> {
            LOGGER.debug("[Vulkairis] Compiling new pipeline for key: {}", k);
            long handle = pipelineCreator.getAsLong();
            LOGGER.info("[Vulkairis] Cached GraphicsPipeline 0x{} in memory for format '{}'",
                    Long.toHexString(handle), k.vertexFormatName());
            return handle;
        });
    }

    /** @return in-memory pipeline cache hits so far */
    public static long memoryHits() {
        return MEMORY_HITS.get();
    }

    /** @return in-memory pipeline cache misses so far */
    public static long memoryMisses() {
        return MEMORY_MISSES.get();
    }

    /**
     * GUI plan Phase 4: cache invalidation. Destroys the driver cache, drops memory entries,
     * deletes the disk blob, and resets stats, so stale data across driver or shader changes
     * cannot survive. Safe headless and idempotent.
     */
    public static synchronized void invalidateCache() {
        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device) && vkPipelineCache != 0L) {
            try {
                VK10.vkDestroyPipelineCache(device, vkPipelineCache, null);
            } catch (Throwable ignored) {
            }
        }
        vkPipelineCache = 0L;
        MEMORY_PIPELINE_CACHE.clear();
        MEMORY_HITS.set(0);
        MEMORY_MISSES.set(0);
        initialized = false;
        try {
            Path cacheFile = cacheFile();
            if (cacheFile != null) {
                Files.deleteIfExists(cacheFile);
            }
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis] Cache invalidation notice: {}", t.getMessage());
        }
        LOGGER.info("[Vulkairis] Pipeline cache invalidated.");
    }

    public static void cleanUp() {
        try {
            savePipelineCacheToDisk();
        } catch (Throwable ignored) {
        }

        VkDevice device = VulkanBackendAdapter.getDevice();
        if (VulkanBackendAdapter.isLive(device) && vkPipelineCache != 0L) {
            try {
                VK10.vkDestroyPipelineCache(device, vkPipelineCache, null);
            } catch (Throwable ignored) {
            }
        }
        vkPipelineCache = 0L;

        MEMORY_PIPELINE_CACHE.clear();
        initialized = false;
        LOGGER.info("[Vulkairis] Cleaned up PipelineManager.");
    }
}
