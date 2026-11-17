package com.vulkairis.translation;

import com.vulkairis.diagnostics.DiagnosticManager;
import com.vulkairis.iris.IrisBridge;
import com.vulkairis.iris.ShaderPackProgram;
import com.vulkairis.iris.SourceProvenance;
import org.lwjgl.util.shaderc.Shaderc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ahead-of-draw shader warmup (Phase C).
 *
 * <p>After {@code IrisBridge.onPipelinePrepared} discovers the active dimension's program set,
 * this helper pre-compiles every graphics program's vertex and fragment sources on a dedicated
 * daemon worker ({@code vulkairis-warmup-*}), so the first real draw finds warm translation
 * and SPIR-V caches instead of paying for shaderc compilation on the render thread. The call
 * never blocks: work is queued on a bounded executor and the method returns immediately, so
 * world load is never stalled by warmup.
 *
 * <p>This helper only <em>reads</em> the existing IrisBridge hooks
 * ({@link IrisBridge#getProgramCatalog()}, {@link IrisBridge#getCurrentPackName()}) and the
 * pipeline generation ({@link SourceProvenance#currentGeneration()}); it adds no hooks and
 * refactors nothing. Each entry is compiled under the generation it was requested with — a
 * pack or dimension change mid-warmup marks the remaining entries stale and they are
 * skipped, never installed over newer output. Compiles flow through
 * {@link SpirvCompiler}, so the bounded compile-slot cap, stack discipline and module
 * cache apply here exactly as on the draw path.
 *
 * <p>Progress is an immutable snapshot ({@link #progress()}) plus listener notifications and
 * start/finish diagnostic records, which is what Compatibility tools already observe.
 */
public final class ShaderWarmup {

    private static final Logger LOGGER = LoggerFactory.getLogger("Vulkairis/Warmup");
    private static final int QUEUE_CAPACITY = 128;
    private static final int MAX_ENTRIES_PER_REQUEST = 256;

    /** One pre-compilable stage source. */
    public record WarmupEntry(String name, String source, int shadercKind) {
    }

    /** Immutable progress snapshot for Compatibility tools and status queries. */
    public record Progress(
            String packName,
            long generation,
            int total,
            int completed,
            int succeeded,
            int failed,
            int skipped,
            boolean running,
            long updatedAtMs) {
    }

    /** Receives a fresh snapshot after every entry settles and at request boundaries. */
    public interface Listener {
        void onProgress(Progress snapshot);
    }

    private static final ThreadFactory DAEMON_FACTORY = new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "vulkairis-warmup-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    };

    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            DAEMON_FACTORY,
            new ThreadPoolExecutor.DiscardPolicy());

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<String> PACK = new AtomicReference<>("none");
    private static final AtomicLong GENERATION = new AtomicLong(0);
    private static final AtomicInteger TOTAL = new AtomicInteger(0);
    private static final AtomicInteger COMPLETED = new AtomicInteger(0);
    private static final AtomicInteger SUCCEEDED = new AtomicInteger(0);
    private static final AtomicInteger FAILED = new AtomicInteger(0);
    private static final AtomicInteger SKIPPED = new AtomicInteger(0);
    private static final AtomicLong UPDATED_AT_MS = new AtomicLong(0);

    private ShaderWarmup() {
    }

    /**
     * Queues a warmup of the active dimension's program set as currently discovered by
     * IrisBridge. Reads existing hooks only; returns immediately without blocking.
     *
     * @return {@code true} when at least one stage was queued, {@code false} when there is
     *         no pack, no program set, or nothing compilable
     */
    public static boolean requestWarmup() {
        long generation = SourceProvenance.currentGeneration();
        String packName = IrisBridge.getCurrentPackName();
        List<WarmupEntry> entries = new ArrayList<>();
        try {
            var catalog = IrisBridge.getProgramCatalog();
            if (catalog == null) {
                return false;
            }
            for (ShaderPackProgram program : catalog.getGraphicsPrograms()) {
                if (!(program instanceof ShaderPackProgram.Graphics graphics)
                        || graphics.source() == null) {
                    continue;
                }
                // DH degrade pass: without the DH mod the distant programs can never compile,
                // so they are not even queued (the capability-gate note is the labeled record).
                if (!com.vulkairis.compatibility.DistantHorizonsSupport.isAvailable()
                        && com.vulkairis.compatibility.DistantHorizonsSupport.isDistantProgram(program)) {
                    continue;
                }
                graphics.source().getVertexSource().ifPresent(src -> entries.add(
                        new WarmupEntry(graphics.name() + "_vert", src,
                                Shaderc.shaderc_glsl_vertex_shader)));
                graphics.source().getFragmentSource().ifPresent(src -> entries.add(
                        new WarmupEntry(graphics.name() + "_frag", src,
                                Shaderc.shaderc_glsl_fragment_shader)));
            }
        } catch (Throwable t) {
            LOGGER.debug("[Vulkairis/Warmup] Program-set snapshot unavailable: {}", t.toString());
            return false;
        }
        return requestWarmup(packName, entries, generation);
    }

    /**
     * Queues an explicit entry set under the given pipeline generation. Detached from Iris
     * (headless-safe); the catalog-backed {@link #requestWarmup()} delegates here.
     *
     * @return {@code true} when at least one stage was queued
     */
    public static boolean requestWarmup(List<WarmupEntry> entries, long generation) {
        return requestWarmup("explicit", entries, generation);
    }

    private static boolean requestWarmup(String packName, List<WarmupEntry> entries,
                                         long generation) {
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        PACK.set(packName != null ? packName : "explicit");
        GENERATION.set(generation);
        TOTAL.set(Math.min(entries.size(), MAX_ENTRIES_PER_REQUEST));
        COMPLETED.set(0);
        SUCCEEDED.set(0);
        FAILED.set(0);
        SKIPPED.set(Math.max(0, entries.size() - MAX_ENTRIES_PER_REQUEST));
        touch();
        int submitted = 0;
        for (WarmupEntry entry : entries) {
            if (submitted >= MAX_ENTRIES_PER_REQUEST) {
                break;
            }
            if (entry == null || entry.source() == null || entry.source().isBlank()) {
                SKIPPED.incrementAndGet();
                TOTAL.decrementAndGet();
                continue;
            }
            final WarmupEntry queued = entry;
            try {
                EXECUTOR.execute(() -> warmOne(queued, generation));
                submitted++;
            } catch (Throwable t) {
                LOGGER.debug("[Vulkairis/Warmup] Queue rejected '{}': {}",
                        entry.name(), t.toString());
                SKIPPED.incrementAndGet();
            }
        }
        if (submitted == 0) {
            publish();
            return false;
        }
        final int queuedCount = submitted;
        final String summaryPack = PACK.get();
        try {
            EXECUTOR.execute(() -> DiagnosticManager.log(DiagnosticManager.Category.SHADER,
                    "Shader warmup finished",
                    summaryPack + ": " + SUCCEEDED.get() + " compiled, "
                            + FAILED.get() + " failed, " + SKIPPED.get()
                            + " skipped (" + queuedCount + " queued)"));
        } catch (Throwable ignored) {
        }
        DiagnosticManager.log(DiagnosticManager.Category.SHADER, "Shader warmup started",
                summaryPack + ": " + queuedCount + " stage(s) queued on a worker");
        publish();
        return true;
    }

    private static void warmOne(WarmupEntry entry, long generation) {
        try {
            if (SourceProvenance.currentGeneration() != generation) {
                SKIPPED.incrementAndGet();
                return;
            }
            boolean isVertex = entry.shadercKind() == Shaderc.shaderc_glsl_vertex_shader;
            String translated;
            try {
                translated = ShaderTranslator.preprocessGlsl(entry.source(), isVertex, entry.name());
            } catch (Throwable t) {
                FAILED.incrementAndGet();
                LOGGER.debug("[Vulkairis/Warmup] Translation failed for '{}': {}",
                        entry.name(), t.toString());
                return;
            }
            ShaderCompileResult result;
            try {
                result = SpirvCompiler.compileGlslToSpirvDetailed(
                        entry.name(), translated, entry.shadercKind());
            } catch (Error fatal) {
                FAILED.incrementAndGet();
                throw fatal;
            } catch (Throwable t) {
                FAILED.incrementAndGet();
                LOGGER.debug("[Vulkairis/Warmup] Compilation failed for '{}': {}",
                        entry.name(), t.toString());
                return;
            }
            if (result != null && result.succeeded()) {
                SUCCEEDED.incrementAndGet();
            } else {
                FAILED.incrementAndGet();
            }
        } finally {
            COMPLETED.incrementAndGet();
            publish();
        }
    }

    /** @return an immutable snapshot of warmup progress for Compatibility tools */
    public static Progress progress() {
        boolean running = EXECUTOR.getActiveCount() > 0 || !EXECUTOR.getQueue().isEmpty();
        return new Progress(PACK.get(), GENERATION.get(), TOTAL.get(), COMPLETED.get(),
                SUCCEEDED.get(), FAILED.get(), SKIPPED.get(), running,
                UPDATED_AT_MS.get());
    }

    public static void addListener(Listener listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Waits (without touching the world thread contract — this is a test/diagnostic aid)
     * until the worker drains or the timeout elapses.
     *
     * @return {@code true} when the worker is idle
     */
    public static boolean awaitIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        for (;;) {
            if (EXECUTOR.getActiveCount() == 0 && EXECUTOR.getQueue().isEmpty()) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return EXECUTOR.getActiveCount() == 0 && EXECUTOR.getQueue().isEmpty();
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /** Resets counters and listeners; tests only. In-flight tasks settle harmlessly. */
    public static void resetForTests() {
        LISTENERS.clear();
        PACK.set("none");
        GENERATION.set(0);
        TOTAL.set(0);
        COMPLETED.set(0);
        SUCCEEDED.set(0);
        FAILED.set(0);
        SKIPPED.set(0);
        UPDATED_AT_MS.set(0);
    }

    private static void touch() {
        UPDATED_AT_MS.set(System.currentTimeMillis());
    }

    private static void publish() {
        touch();
        Progress snapshot = progress();
        for (Listener listener : LISTENERS) {
            try {
                listener.onProgress(snapshot);
            } catch (Throwable ignored) {
            }
        }
    }
}
