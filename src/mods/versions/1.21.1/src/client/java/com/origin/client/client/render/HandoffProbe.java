package com.origin.client.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TEMPORARY measurement for the loading -> title handoff freeze (Will 2026-10-18).
 * Dev-shots runs only (ORIGIN_SHOTS set). From the first startup-overlay frame to
 * 8 s after the overlay goes, logs every frame's length and the animation clocks,
 * and a sampler thread grabs the render thread's stack every 20 ms so a long
 * frame names what it was doing. Removed once the cause is fixed.
 */
public final class HandoffProbe {
	private static final boolean ON = System.getenv("ORIGIN_SHOTS") != null;
	private static Thread render;
	private static volatile long frameId;
	private static volatile boolean armed;
	private static boolean done;
	private static long lastNs, armEndNs = Long.MAX_VALUE;
	private static int dumps;
	private static final Map<Long, List<StackTraceElement[]>> SAMPLES = new ConcurrentHashMap<>();

	private HandoffProbe() {
	}

	public static void event(String what) {
		if (ON && armed) {
			com.origin.client.OriginClient.LOGGER.info("origin-probe event f={} wall={} {}", frameId, System.currentTimeMillis(), what);
		}
	}

	/** Render thread, GameRenderer.render HEAD. */
	public static void frame() {
		if (!ON || done) {
			return;
		}
		try {
			long now = System.nanoTime();
			Minecraft mc = Minecraft.getInstance();
			Object ov = mc.getOverlay();
			if (render == null) {
				render = Thread.currentThread();
				Thread t = new Thread(HandoffProbe::sample, "origin-handoff-probe");
				t.setDaemon(true);
				t.start();
			}
			if (!armed && ov instanceof LoadingOverlay) {
				armed = true;
			}
			if (armed && ov == null && armEndNs == Long.MAX_VALUE) {
				armEndNs = now + 8_000_000_000L;
			}
			if (armed) {
				double dt = (now - lastNs) / 1e6;
				com.origin.client.OriginClient.LOGGER.info(String.format(
						"origin-probe f=%d dt=%.1f wall=%d overlay=%s screen=%s scene=%.1f debt=%.1f flightT=%.1f",
						frameId, dt, System.currentTimeMillis(),
						ov == null ? "-" : ov.getClass().getSimpleName(),
						mc.screen == null ? "-" : mc.screen.getClass().getSimpleName(),
						RingField.probeClock(), RingField.probeDebt(), MarkMotion.probeFlightT()));
				List<StackTraceElement[]> s = SAMPLES.remove(frameId);
				if (dt > 200 && s != null && dumps < 40) {
					dumps++;
					summarize(s);
				}
				if (now > armEndNs) {
					armed = false;
					done = true;
					SAMPLES.clear();
				}
			}
			lastNs = now;
			frameId++;
		} catch (Throwable ignored) {
			// measurement only
		}
	}

	private static void summarize(List<StackTraceElement[]> samples) {
		Map<String, Integer> count = new HashMap<>();
		Map<String, StackTraceElement[]> first = new HashMap<>();
		for (StackTraceElement[] st : samples) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(40, st.length); i++) {
				sb.append(st[i]).append('|');
			}
			String k = sb.toString();
			count.merge(k, 1, Integer::sum);
			first.putIfAbsent(k, st);
		}
		List<Map.Entry<String, Integer>> top = new ArrayList<>(count.entrySet());
		top.sort((a, b) -> b.getValue() - a.getValue());
		com.origin.client.OriginClient.LOGGER.info("origin-probe stacks f={} samples={} distinct={}", frameId, samples.size(), top.size());
		for (int n = 0; n < Math.min(3, top.size()); n++) {
			StackTraceElement[] st = first.get(top.get(n).getKey());
			StringBuilder sb = new StringBuilder("origin-probe  #" + n + " x" + top.get(n).getValue());
			for (int i = 0; i < Math.min(40, st.length); i++) {
				sb.append("\n    at ").append(st[i]);
			}
			com.origin.client.OriginClient.LOGGER.info(sb.toString());
		}
	}

	private static void sample() {
		while (!done) {
			try {
				Thread.sleep(20);
				if (armed && render != null) {
					long id = frameId;
					StackTraceElement[] st = render.getStackTrace();
					if (frameId == id) {
						SAMPLES.computeIfAbsent(id, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(st);
					}
				}
			} catch (Throwable ignored) {
				// measurement only
			}
		}
	}
}
