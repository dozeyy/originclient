package com.vulkairis.test;

import com.vulkairis.translation.TranslationWorker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Phase 12: background work honors pipeline generations — stale tasks never install results.
 */
public class TranslationWorkerTest {

    @Test
    public void testValueReturnedForCurrentGeneration() {
        TranslationWorker.Task<Integer> task =
                TranslationWorker.submit("probe", 41L, () -> 42);
        Optional<Integer> value = TranslationWorker.takeIfCurrent(task, 41L, 5000L);
        Assertions.assertEquals(Optional.of(42), value);
    }

    @Test
    public void testStaleTaskYieldsNothingAndCancels() {
        TranslationWorker.Task<Integer> task =
                TranslationWorker.submit("stale", 41L, () -> 42);
        Optional<Integer> value = TranslationWorker.takeIfCurrent(task, 42L, 5000L);
        Assertions.assertTrue(value.isEmpty(), "stale generations must never install results");
        Assertions.assertTrue(task.future().isCancelled() || task.future().isDone());
    }

    @Test
    public void testFailingTaskYieldsNothing() {
        TranslationWorker.Task<Integer> task = TranslationWorker.submit("boom", 7L, () -> {
            throw new IllegalStateException("nope");
        });
        Assertions.assertTrue(TranslationWorker.takeIfCurrent(task, 7L, 5000L).isEmpty());
    }

    @Test
    public void testPurgeDropsOnlyStaleQueuedTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // Occupy both worker threads so the generation-41 tasks below stay queued.
        TranslationWorker.submit("occupy-1", 41L, () -> {
            try {
                release.await(10L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        });
        TranslationWorker.submit("occupy-2", 41L, () -> {
            try {
                release.await(10L, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 2;
        });
        Thread.sleep(500L);

        TranslationWorker.Task<Integer> stale1 = TranslationWorker.submit("stale-1", 41L, () -> 1);
        TranslationWorker.Task<Integer> stale2 = TranslationWorker.submit("stale-2", 41L, () -> 2);
        TranslationWorker.Task<Integer> stale3 = TranslationWorker.submit("stale-3", 41L, () -> 3);

        int dropped = TranslationWorker.purgeStale(42L);
        Assertions.assertTrue(dropped >= 3,
                "all queued stale tasks must drop, dropped=" + dropped);
        Assertions.assertTrue(stale1.future().isCancelled());
        Assertions.assertTrue(stale2.future().isCancelled());
        Assertions.assertTrue(stale3.future().isCancelled());

        release.countDown();
        TranslationWorker.Task<Integer> fresh =
                TranslationWorker.submit("fresh", 42L, () -> 7);
        Assertions.assertEquals(Optional.of(7),
                TranslationWorker.takeIfCurrent(fresh, 42L, 5000L));
        Assertions.assertEquals(0, TranslationWorker.purgeStale(42L), "nothing stale remains");
    }
}
