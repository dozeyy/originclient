package com.origin.client.client.hud;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

// Every frame's duration, for the FPS module's 1% low / frame time / frame-time
// graph. 1.8.9: HudHooks calls frame() from Forge's RenderTickEvent START, once
// per frame on the render thread (1.21.1 uses a GameRenderer mixin; the tick
// event is the same moment here and needs no mixin on OptiFine's renderer): one nanoTime subtraction and one array store, no allocation. The
// expensive bits only run when something asks: the 1% low sorts a copy when the
// HUD refreshes its text (4x a second), and the GC check below runs only on a
// spike frame.
//
// Spikes: a frame much slower than the running average (2.5x, and at least 8 ms
// over it). For each spike we ask the JVM whether a garbage-collection PAUSE
// overlapped that frame (the collectors' last-GC start + duration), so the graph
// can show "this hitch was the JVM cleaning memory" (gold) vs. anything else
// (red: chunk loading, a shader compile, disk...). Concurrent collector beans
// (G1 Concurrent GC, ZGC Cycles) are skipped: they don't stop the game.
// Pure JDK (Java 8: com.sun.management is HotSpot's own, present on the Java 8
// runtimes 1.8.9 launches with); unit-tested in tools/packhub-tests (1.21.1 copy).
public final class FrameStats {
	private FrameStats() {
	}

	public static final int SIZE = 512;
	/** Gaps longer than this are a pause/minimise/loading screen, not a frame. */
	private static final float MAX_FRAME_MS = 1000;

	private static final float[] MS = new float[SIZE];
	private static final byte[] KIND = new byte[SIZE]; // 0 normal, 1 spike, 2 GC spike
	private static final float[] SCRATCH = new float[SIZE];
	private static int head = 0, count = 0;
	private static long lastNanos = 0;
	private static double avg = 0;

	public static final byte NORMAL = 0, SPIKE = 1, GC = 2;

	private static long frames = 0;

	/** Frames seen so far (every call, never capped): the world-loading bar tells world loads apart with it. */
	public static long frames() {
		return frames;
	}

	/** Render thread, once per frame. */
	public static void frame(long nanos) {
		frames++;
		long prev = lastNanos;
		lastNanos = nanos;
		if (prev == 0) {
			return;
		}
		float ms = (nanos - prev) / 1_000_000f;
		if (ms <= 0 || ms > MAX_FRAME_MS) {
			return;
		}
		byte kind = NORMAL;
		if (count > 30 && ms > avg * 2.5 && ms > avg + 8) {
			kind = gcOverlapped(ms) ? GC : SPIKE;
			SpikeSink sk = sink;
			if (sk != null && ms >= SPIKE_LOG_MS) {
				sk.spike(ms, (float) avg, kind); // rare by definition; the sink rate-limits
			}
		}
		avg = count == 0 ? ms : avg + (ms - avg) * 0.05;
		MS[head] = ms;
		KIND[head] = kind;
		head = (head + 1) % SIZE;
		if (count < SIZE) {
			count++;
		}
	}

	/** Spikes at least this long (ms) go to the sink; shorter ones only colour the graph. */
	public static final float SPIKE_LOG_MS = 40;

	/** Told about each long spike frame (render thread), with the running average and GC/SPIKE kind. */
	public interface SpikeSink {
		void spike(float ms, float avgMs, byte kind);
	}

	private static volatile SpikeSink sink;

	public static void setSpikeSink(SpikeSink s) {
		sink = s;
	}

	/** Frames recorded so far (up to SIZE). */
	public static int count() {
		return count;
	}

	/** The i-th most recent frame time in ms (0 = newest). */
	public static float ms(int back) {
		return MS[Math.floorMod(head - 1 - back, SIZE)];
	}

	public static byte kind(int back) {
		return KIND[Math.floorMod(head - 1 - back, SIZE)];
	}

	/** Average frame time of the newest frames covering ~windowMs. */
	public static float averageMs(float windowMs) {
		float sum = 0;
		int n = 0;
		while (n < count && sum < windowMs) {
			sum += ms(n++);
		}
		return n == 0 ? 0 : sum / n;
	}

	/** FPS of the slowest 1% of the recorded frames (their average frame time). 0 = not enough data. */
	public static int onePercentLow() {
		int n = count;
		if (n < 100) {
			return 0;
		}
		for (int i = 0; i < n; i++) {
			SCRATCH[i] = ms(i);
		}
		Arrays.sort(SCRATCH, 0, n);
		int k = Math.max(1, n / 100);
		double sum = 0;
		for (int i = n - k; i < n; i++) {
			sum += SCRATCH[i];
		}
		return sum <= 0 ? 0 : (int) Math.round(k * 1000.0 / sum);
	}

	/** Test seam: forget everything. */
	static void reset() {
		head = 0;
		count = 0;
		lastNanos = 0;
		avg = 0;
	}

	// ---- GC attribution (spike frames only) ----

	private static List<GarbageCollectorMXBean> beans;
	private static long jvmStart = -1;

	private static boolean gcOverlapped(float frameMs) {
		try {
			if (beans == null) {
				beans = ManagementFactory.getGarbageCollectorMXBeans();
				jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
			}
			long nowUptime = System.currentTimeMillis() - jvmStart;
			long frameStart = nowUptime - (long) Math.ceil(frameMs) - 1;
			for (GarbageCollectorMXBean b : beans) {
				String name = b.getName();
				if (name.contains("Concurrent") || name.contains("Cycles")) {
					continue;
				}
				if (b instanceof com.sun.management.GarbageCollectorMXBean) {
					com.sun.management.GcInfo info = ((com.sun.management.GarbageCollectorMXBean) b).getLastGcInfo();
					if (info != null && info.getEndTime() >= frameStart && info.getStartTime() <= nowUptime) {
						return true;
					}
				}
			}
		} catch (Throwable t) {
			beans = java.util.Collections.emptyList(); // no HotSpot management API: spikes just show as spikes
		}
		return false;
	}
}
