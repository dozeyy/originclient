package com.vulkairis.translation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded background compile worker (Phase 12).
 *
 * <p>CPU translation and shaderc compilation may run here instead of the render thread: the
 * queue is bounded (64), threads are daemon (they never block shutdown), and every task
 * carries the pipeline generation it was submitted under. Reloads purge queued tasks from
 * older generations, and results are only installed when the task generation still matches —
 * a stale compile can never install a pipeline after a newer pack or dimension replaced it.
 * Vulkan object creation, submission and live-state mutation stay on the render thread;
 * only pure CPU work (translation, compilation to bytes) runs here.</p>
 */
public final class TranslationWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/CompileWorker");
    private static final int QUEUE_CAPACITY = 64;

    private static final ThreadFactory DAEMON_FACTORY = new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "vulkairis-compile-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    };

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 2, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            DAEMON_FACTORY,
            new ThreadPoolExecutor.DiscardPolicy());

    /** Load-spreading toggle (central flag mirrored from config). Defaults OFF. */
    private static volatile boolean loadSpreadingEnabled = false;
    /** Cap on spread workers (bounded by available processors, never unbounded). */
    private static final int SPREAD_WORKER_CAP = Math.max(2, Math.min(8,
            Runtime.getRuntime().availableProcessors()));
    /** Spread pool (created lazily only when load spreading is enabled). */
    private static volatile ThreadPoolExecutor spreadExecutor = null;
    /** Per-worker queue capacity used when spreading (small, so load spreads before saturation). */
    private static final int SPREAD_QUEUE_CAPACITY = 16;
    /** Queue depth at which pressure is considered high enough to spread. */
    public static final int LOAD_SPREADING_THRESHOLD = 4;

    /** Outstanding tasks, so reloads can cancel exactly the stale ones. */
    private static final java.util.Set<Task<?>> OUTSTANDING =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private TranslationWorker() {
    }

    /** A submitted unit of background work with its pipeline generation. */
    public record Task<T>(Future<T> future, long generation, String name) {
    }

    /**
     * Submits background work. A saturated queue discards (with a diagnostic) instead of
     * stalling the submitting thread.
     */
    public static <T> Task<T> submit(String name, long generation, Supplier<T> work) {
        ThreadPoolExecutor target = isLoadSpreadingEnabled() ? spreadExecutor() : EXECUTOR;
        Future<T> future = target.submit(() -> {
            try {
                return work.get();
            } catch (Throwable t) {
                LOGGER.warn("[Vulkairis/CompileWorker] Background task '{}' failed: {}",
                        name, t.getMessage());
                throw t;
            }
        });
        Task<T> task = new Task<>(future, generation, name);
        OUTSTANDING.add(task);
        return task;
    }

    /** Central load-spreading flag (mirrored from config by the settings service). */
    public static boolean isLoadSpreadingEnabled() {
        if (loadSpreadingEnabled) {
            return true;
        }
        try {
            return com.vulkairis.config.VulkairisConfigManager.getConfig()
                    .isLoadSpreadingEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Toggles load spreading; applies immediately to new submissions (no restart). */
    public static void setLoadSpreadingEnabled(boolean enabled) {
        loadSpreadingEnabled = enabled;
        if (!enabled) {
            // Leave the spread pool in place (if any) so in-flight tasks settle; it idles out.
        }
    }

    /** Lazily creates the bounded spread pool. */
    private static ThreadPoolExecutor spreadExecutor() {
        ThreadPoolExecutor existing = spreadExecutor;
        if (existing != null) {
            return existing;
        }
        synchronized (TranslationWorker.class) {
            if (spreadExecutor == null) {
                spreadExecutor = new ThreadPoolExecutor(
                        2, SPREAD_WORKER_CAP, 30L, TimeUnit.SECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(SPREAD_QUEUE_CAPACITY),
                        DAEMON_FACTORY,
                        new ThreadPoolExecutor.DiscardPolicy());
                spreadExecutor.allowCoreThreadTimeOut(true);
            }
            return spreadExecutor;
        }
    }

    /** @return the pool currently used for new submissions. */
    public static ThreadPoolExecutor activePool() {
        return isLoadSpreadingEnabled() ? spreadExecutor() : EXECUTOR;
    }

    /** @return a coarse pressure score in [0,1] across the active pool. */
    public static double pressure() {
        ThreadPoolExecutor pool = activePool();
        int max = Math.max(1, pool.getMaximumPoolSize());
        int active = pool.getActiveCount();
        int queued = pool.getQueue().size();
        int capacity = Math.max(1, pool.getQueue().remainingCapacity() + queued);
        double load = (active + (double) queued / capacity) / max;
        return Math.min(1.0, Math.max(0.0, load));
    }

    /** @return whether pressure is high enough that spreading is warranted. */
    public static boolean isUnderHeavyLoad() {
        ThreadPoolExecutor pool = activePool();
        return pool.getQueue().size() >= LOAD_SPREADING_THRESHOLD
                || pool.getActiveCount() >= pool.getCorePoolSize();
    }

    /**
     * Cancels outstanding tasks from older generations. Queued tasks are dropped; running
     * tasks finish harmlessly because installation still checks the generation.
     *
     * @return how many stale tasks were dropped
     */
    public static int purgeStale(long currentGeneration) {
        int dropped = 0;
        for (Task<?> task : OUTSTANDING.toArray(new Task<?>[0])) {
            if (task.future().isDone() || task.future().isCancelled()) {
                OUTSTANDING.remove(task);
                continue;
            }
            if (task.generation() != currentGeneration && task.future().cancel(false)) {
                OUTSTANDING.remove(task);
                dropped++;
            }
        }
        if (dropped > 0) {
            LOGGER.info("[Vulkairis/CompileWorker] Dropped {} stale task(s) for generation {}",
                    dropped, currentGeneration);
        }
        return dropped;
    }

    /**
     * Takes a task result only when its generation is still current.
     *
     * @return the value, or empty when stale, failed, timed out or cancelled
     */
    public static <T> Optional<T> takeIfCurrent(Task<T> task, long currentGeneration,
                                                long timeoutMs) {
        if (task == null || task.future() == null) {
            return Optional.empty();
        }
        if (task.generation() != currentGeneration) {
            task.future().cancel(false);
            OUTSTANDING.remove(task);
            return Optional.empty();
        }
        try {
            Optional<T> result = Optional.ofNullable(task.future().get(timeoutMs, TimeUnit.MILLISECONDS));
            OUTSTANDING.remove(task);
            return result;
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Throwable t) {
            LOGGER.warn("[Vulkairis/CompileWorker] Task '{}' failed: {}",
                    task.name(), t.getMessage());
            OUTSTANDING.remove(task);
            return Optional.empty();
        }
    }

    /** @return queued (not yet started) task count across all pools, for diagnostics */
    public static int queuedCount() {
        int total = EXECUTOR.getQueue().size();
        ThreadPoolExecutor spread = spreadExecutor;
        if (spread != null) {
            total += spread.getQueue().size();
        }
        return total;
    }

    /** @return a short status string for diagnostics/GUI. */
    public static String status() {
        ThreadPoolExecutor pool = isLoadSpreadingEnabled() ? (spreadExecutor != null ? spreadExecutor : EXECUTOR) : EXECUTOR;
        return "loadSpreading=" + isLoadSpreadingEnabled()
                + " active=" + pool.getActiveCount()
                + " queued=" + pool.getQueue().size()
                + " max=" + pool.getMaximumPoolSize()
                + " pressure=" + String.format(java.util.Locale.ROOT, "%.2f", pressure());
    }
}
