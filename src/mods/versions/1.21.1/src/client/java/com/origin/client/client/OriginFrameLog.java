package com.origin.client.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only frame-time logger for the loading -> main menu hand-off. OFF unless
 * the JVM is started with {@code -Dorigin.frameLog=true} (dev runs:
 * {@code .\gradlew.bat runClient -PframeLog}); when off every entry point is a
 * single static-final check and nothing is ever printed.
 *
 * <p>When on it records every render-thread frame from the first loading frame
 * until the title's entrance has finished, and prints, all on lines starting
 * {@code [OriginFrameLog]}:
 * <ul>
 *   <li>at "reload done" (bar full): every frame over {@link #SLOW_MS} from the
 *       {@link #LEAD_MS} before it; after that each slow frame as it ends;</li>
 *   <li>for each slow frame: where the time went (reload tasks run this frame,
 *       the GUI render, the buffer swap, the title screen's own render), the
 *       reload apply steps over {@link #TASK_MS} by listener, whether it was the
 *       title's first frame(s), and GC pause time (GarbageCollectorMXBean deltas,
 *       so it works without -Xlog:gc);</li>
 *   <li>a per-frame trace of the hand-off itself (frame ms + how far the scene
 *       clock moved), the frame-step evidence that the animation never jumps;</li>
 *   <li>one summary line at the end.</li>
 * </ul>
 * "uptime" is the JVM's (the same clock -Xlog:gc stamps its lines with).
 * Render thread only, except {@link #reloadTask}, which only ever runs there too
 * (it is the reload's apply executor) but is cheap and lock-free regardless.
 */
public final class OriginFrameLog {
	public static final boolean ENABLED = Boolean.getBoolean("origin.frameLog");

	/** A frame longer than this is logged. */
	private static final double SLOW_MS = 20.0;
	/** A reload apply step longer than this is named on its frame's line. */
	private static final double TASK_MS = 16.0;
	/** How far before "reload done" the slow-frame list starts. */
	private static final double LEAD_MS = 3000.0;
	/** Trace every frame from this long before reload done to the end. */
	private static final double TRACE_LEAD_MS = 600.0;
	/** Hard stop if the title's entrance end is never seen (vanilla menu, broken renderer). */
	private static final double MAX_AFTER_DONE_MS = 8000.0;
	private static final String P = "[OriginFrameLog] ";

	public enum Phase { LOAD, DONE, HOLD, DISSOLVE, TITLE }

	private static final int CAP = 8192;
	private static final long[] start = new long[CAP];
	private static final float[] frameMs = new float[CAP], tasksMs = new float[CAP], renderMs = new float[CAP],
			swapMs = new float[CAP], titleMs = new float[CAP], gcMs = new float[CAP], sceneStep = new float[CAP];
	private static final int[] titleIndex = new int[CAP], gcCount = new int[CAP];
	private static final Phase[] phase = new Phase[CAP];
	private static final String[] tasks = new String[CAP];
	private static int n = 0;           // frames recorded (the current one is n - 1)
	private static boolean finished = false;

	// The frame in progress.
	private static long frameStart = 0, renderStart = 0, swapStart = 0, titleStart = 0, taskStart = 0;
	private static double curTasks, curRender, curSwap, curTitle;
	private static StringBuilder curNamed;
	private static int titleFrames = 0;
	private static boolean titleThisFrame = false;
	private static double lastScene = Double.NaN;
	private static long lastGcMs, lastGcCount;
	private static List<GarbageCollectorMXBean> gcBeans;

	// Marks (nanoTime; 0 = not yet).
	private static long doneAt = 0, holdAt = 0, dissolveAt = 0, overlayGoneAt = 0, endAt = 0;
	private static double entranceEndScene = Double.NaN;
	private static Phase current = Phase.LOAD;

	private OriginFrameLog() {
	}

	// ---- hooks ----

	/** Minecraft.runTick HEAD: one frame ends, the next begins. */
	public static void frame(boolean overlayUp) {
		if (!ENABLED || finished) {
			return;
		}
		long now = System.nanoTime();
		if (n > 0) {
			closeFrame(now);
		} else {
			gcBeans = pauseBeans();
			long[] gc = gcTotals();
			lastGcMs = gc[0];
			lastGcCount = gc[1];
			com.origin.client.OriginClient.LOGGER.info(P + "on: frames over {} ms, reload steps over {} ms, from {} ms before the reload finishes"
					+ " until the title's entrance ends (uptime {} s)", SLOW_MS, TASK_MS, (long) LEAD_MS, uptimeS());
		}
		if (current == Phase.DONE) {
			// The dissolve follows the completion frame directly unless a settle
			// hold marks itself (mark("hold-start") relabels its own frame).
			current = Phase.DISSOLVE;
		}
		if (doneAt != 0 && !overlayUp && overlayGoneAt == 0) {
			overlayGoneAt = now;
			current = Phase.TITLE;
			mark("overlay-gone");
		}
		double scene = com.origin.client.client.render.RingField.peekMs();
		boolean entranceOver = !Double.isNaN(entranceEndScene) && overlayGoneAt != 0 && scene >= entranceEndScene;
		boolean timeout = doneAt != 0 && (now - doneAt) / 1e6 > MAX_AFTER_DONE_MS;
		if (entranceOver || timeout || n >= CAP) {
			endAt = now;
			mark(entranceOver ? "entrance-end" : timeout ? "end (timeout: entrance end not seen)" : "end (buffer full)");
			finished = true;
			summary();
			return;
		}
		int i = n++;
		start[i] = now;
		phase[i] = current;
		frameStart = now;
		curTasks = curRender = curSwap = curTitle = 0;
		curNamed = null;
		titleThisFrame = false;
	}

	/** True once the report has been printed (dev-shots waits for it). */
	public static boolean done() {
		return !ENABLED || finished;
	}

	public static void tasksBegin() {
		if (ENABLED) {
			taskStart = System.nanoTime();
		}
	}

	public static void tasksEnd() {
		if (ENABLED && taskStart != 0) {
			curTasks += (System.nanoTime() - taskStart) / 1e6;
			taskStart = 0;
		}
	}

	/**
	 * The startup loading overlay is rendering with its reload finished: the bar
	 * is full, and this frame runs the completion (the title screen is built).
	 * Called from the overlay's render, so it lands on the completion frame itself.
	 */
	public static void reloadSeenDone() {
		if (!ENABLED || finished || doneAt != 0 || n == 0) {
			return;
		}
		doneAt = start[n - 1];
		current = Phase.DONE;
		phase[n - 1] = Phase.DONE;
		com.origin.client.OriginClient.LOGGER.info(P + "mark reload-done (bar full) uptime {} s", uptimeS());
		flushLead();
	}

	private static long sectionStart;

	/** A named stretch of render-thread work begins (FrameLogOverlayMixin: onFinish, screen.init). */
	public static void sectionBegin() {
		if (ENABLED) {
			sectionStart = System.nanoTime();
		}
	}

	/** ...and ends: named on its frame's line (any length). */
	public static void sectionEnd(String name) {
		if (!ENABLED || finished || sectionStart == 0) {
			return;
		}
		named(name, (System.nanoTime() - sectionStart) / 1e6);
		sectionStart = 0;
	}

	private static long pollStart;
	private static boolean pollTagged;

	/** ReloadTaskBudgetMixin is about to poll one queued task. */
	public static void pollBegin() {
		if (ENABLED) {
			pollStart = System.nanoTime();
			pollTagged = false;
		}
	}

	/** ...and it finished: a slow one that no reload listener claimed is named by its class. */
	public static void pollEnd(Object task) {
		if (!ENABLED || finished || pollStart == 0) {
			return;
		}
		double ms = (System.nanoTime() - pollStart) / 1e6;
		pollStart = 0;
		if (ms >= TASK_MS && !pollTagged) {
			named(task == null ? "task?" : "task:" + task.getClass().getName(), ms);
		}
	}

	/** A reload apply step ran (tagged by ReloadTaskTagMixin with its listener). */
	public static void reloadTask(String listener, double ms) {
		if (!ENABLED || finished || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
			return;
		}
		pollTagged = true;
		if (ms >= TASK_MS) {
			named(listener, ms);
		}
	}

	public static void renderBegin() {
		if (ENABLED) {
			renderStart = System.nanoTime();
		}
	}

	public static void renderEnd() {
		if (ENABLED && renderStart != 0) {
			curRender += (System.nanoTime() - renderStart) / 1e6;
			renderStart = 0;
		}
	}

	public static void swapBegin() {
		if (ENABLED) {
			swapStart = System.nanoTime();
		}
	}

	public static void swapEnd() {
		if (ENABLED && swapStart != 0) {
			curSwap += (System.nanoTime() - swapStart) / 1e6;
			swapStart = 0;
		}
	}

	public static void titleBegin() {
		if (ENABLED) {
			titleStart = System.nanoTime();
		}
	}

	public static void titleEnd() {
		if (ENABLED && titleStart != 0) {
			curTitle += (System.nanoTime() - titleStart) / 1e6;
			titleStart = 0;
			if (!titleThisFrame) {
				titleThisFrame = true;
				titleFrames++;
			}
		}
	}

	/** A named moment in the hand-off (hold start, dissolve start, ...). */
	public static void mark(String what) {
		if (!ENABLED || finished) {
			return;
		}
		long now = System.nanoTime();
		Phase relabel = null;
		if (what.equals("hold-start") && holdAt == 0) {
			holdAt = now;
			relabel = Phase.HOLD;
		} else if (what.equals("dissolve-start") && dissolveAt == 0) {
			dissolveAt = now;
			relabel = Phase.DISSOLVE;
		}
		if (relabel != null && n > 0 && current != Phase.TITLE) {
			current = relabel;
			phase[n - 1] = relabel;   // the frame this happened in
		}
		com.origin.client.OriginClient.LOGGER.info(P + "mark {} t={} ms uptime {} s", what, fmt(sinceDone(now)), uptimeS());
	}

	/** The title's entrance ends at this scene-clock time (RingField.sceneMs). */
	public static void entranceEndsAt(double sceneMs) {
		if (ENABLED) {
			entranceEndScene = sceneMs;
		}
	}

	// ---- recording ----

	private static void named(String what, double ms) {
		if (curNamed == null) {
			curNamed = new StringBuilder();
		} else {
			curNamed.append(',');
		}
		curNamed.append(what).append(':').append(fmt(ms));
	}

	private static void closeFrame(long now) {
		int i = n - 1;
		frameMs[i] = (float) ((now - frameStart) / 1e6);
		tasksMs[i] = (float) curTasks;
		renderMs[i] = (float) curRender;
		swapMs[i] = (float) curSwap;
		titleMs[i] = (float) curTitle;
		titleIndex[i] = titleThisFrame ? titleFrames : 0;
		tasks[i] = curNamed == null ? null : curNamed.toString();
		long[] gc = gcTotals();
		gcMs[i] = gc[0] - lastGcMs;
		gcCount[i] = (int) (gc[1] - lastGcCount);
		lastGcMs = gc[0];
		lastGcCount = gc[1];
		double scene = com.origin.client.client.render.RingField.peekMs();
		sceneStep[i] = Double.isNaN(lastScene) ? 0f : (float) (scene - lastScene);
		lastScene = scene;
		if (doneAt != 0) {
			// Live from reload done on: slow frames, and the trace.
			printFrame(i);
		}
	}

	private static void flushLead() {
		long from = doneAt - (long) (LEAD_MS * 1e6);
		for (int i = 0; i < n - 1; i++) {   // the frame in progress prints when it closes
			if (start[i] >= from) {
				printFrame(i);
			}
		}
	}

	private static void printFrame(int i) {
		double t = sinceDone(start[i]);
		if (frameMs[i] > SLOW_MS) {
			com.origin.client.OriginClient.LOGGER.info(P + "slow t={} ms frame={} ms phase={} tasks={} render={} swap={} title={}{} gc={} ms/{} reload=[{}] scene+{} ms",
					fmt(t), fmt(frameMs[i]), phase[i], fmt(tasksMs[i]), fmt(renderMs[i]), fmt(swapMs[i]), fmt(titleMs[i]),
					titleIndex[i] > 0 && titleIndex[i] <= 3 ? " (title frame " + titleIndex[i] + ")" : "",
					fmt(gcMs[i]), gcCount[i], tasks[i] == null ? "" : tasks[i], fmt(sceneStep[i]));
		}
		if (t >= -TRACE_LEAD_MS) {
			com.origin.client.OriginClient.LOGGER.info(P + "trace t={} frame={} scene+{} phase={}",
					fmt(t), fmt(frameMs[i]), fmt(sceneStep[i]), phase[i]);
		}
	}

	private static void summary() {
		// Window A: LEAD_MS before reload done -> end. Window B: bar full (reload done) -> end.
		long fromA = doneAt == 0 ? 0 : doneAt - (long) (LEAD_MS * 1e6);
		Stats a = stats(fromA, false), b = stats(doneAt, false), visible = stats(doneAt, true);
		long[] gc = gcTotals();
		com.origin.client.OriginClient.LOGGER.info(P + "summary lead+handoff: frames={} max={} ms over33={} over20={} median={} ms | from bar-full: frames={} max={} ms over33={} over20={} median={} ms"
						+ " | visible (dissolve+entrance): frames={} max={} ms over33={} over20={} median={} ms max/median={} | worst={} | gc in window={} ms/{} | hold={} ms | bar-full->entrance-end={} ms",
				a.count, fmt(a.max), a.over33, a.over20, fmt(a.median),
				b.count, fmt(b.max), b.over33, b.over20, fmt(b.median),
				visible.count, fmt(visible.max), visible.over33, visible.over20, fmt(visible.median),
				visible.median > 0 ? fmt(visible.max / visible.median) : "-",
				a.worst, fmt(a.gcMs), a.gcCount,
				holdAt != 0 && dissolveAt != 0 ? fmt((dissolveAt - holdAt) / 1e6) : "-",
				doneAt != 0 ? fmt((endAt - doneAt) / 1e6) : "-");
	}

	private record Stats(int count, double max, int over33, int over20, double median, String worst, double gcMs, int gcCount) {
	}

	/** visibleOnly: only frames where the hand-off moves on screen (dissolve + title entrance). */
	private static Stats stats(long fromNs, boolean visibleOnly) {
		List<Float> list = new ArrayList<>();
		double max = 0, gcSum = 0;
		int over33 = 0, over20 = 0, gcN = 0, worstI = -1;
		for (int i = 0; i < n; i++) {
			if (fromNs == 0 || start[i] < fromNs) {
				continue;
			}
			if (visibleOnly && phase[i] != Phase.DISSOLVE && phase[i] != Phase.TITLE) {
				continue;
			}
			float f = frameMs[i];
			list.add(f);
			if (f > max) {
				max = f;
				worstI = i;
			}
			if (f > 33) {
				over33++;
			}
			if (f > 20) {
				over20++;
			}
			gcSum += gcMs[i];
			gcN += gcCount[i];
		}
		list.sort(null);
		double median = list.isEmpty() ? 0 : list.get(list.size() / 2);
		String worst = worstI < 0 ? "-" : "t=" + fmt(sinceDone(start[worstI])) + " " + phase[worstI]
				+ (tasks[worstI] == null ? "" : " [" + tasks[worstI] + "]")
				+ (titleIndex[worstI] > 0 && titleIndex[worstI] <= 3 ? " title-frame-" + titleIndex[worstI] : "");
		return new Stats(list.size(), max, over33, over20, median, worst, gcSum, gcN);
	}

	// ---- helpers ----

	private static double sinceDone(long ns) {
		return doneAt == 0 ? Double.NaN : (ns - doneAt) / 1e6;
	}

	private static String fmt(double v) {
		return Double.isNaN(v) ? "?" : String.format(java.util.Locale.ROOT, "%.1f", v);
	}

	private static String uptimeS() {
		return String.format(java.util.Locale.ROOT, "%.3f", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
	}

	// Pause-time collectors only: G1's "G1 Concurrent GC" and ZGC's "Cycles" bean
	// count concurrent work that does not stop the render thread.
	private static List<GarbageCollectorMXBean> pauseBeans() {
		List<GarbageCollectorMXBean> out = new ArrayList<>();
		for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
			String name = b.getName();
			if (!name.contains("Concurrent") && !name.contains("Cycles")) {
				out.add(b);
			}
		}
		return out;
	}

	private static long[] gcTotals() {
		long ms = 0, count = 0;
		if (gcBeans != null) {
			for (GarbageCollectorMXBean b : gcBeans) {
				ms += Math.max(0, b.getCollectionTime());
				count += Math.max(0, b.getCollectionCount());
			}
		}
		return new long[]{ms, count};
	}
}
