package com.origin.client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;

import java.util.ArrayDeque;

// Ping mod samples. The source is the latency the SERVER already reports for
// you in the player list (NetworkPlayerInfo.getResponseTime) -- the number the
// tab list shows -- so measuring it costs no packets at all. Sampled every
// "Update Interval" seconds; the readout is the mean of the last "Averaged
// Samples". Spike detection compares the newest sample against the average
// before it.
// 1.8.9: PlayerInfo/getLatency are NetworkPlayerInfo/getResponseTime; the
// connection is Minecraft.getNetHandler().
public final class PingSampler {
	private PingSampler() {
	}

	private static final ArrayDeque<Integer> SAMPLES = new ArrayDeque<>();
	private static long lastSampleAt = 0;
	private static volatile int average = -1;   // -1 = no connection / no sample yet
	private static volatile int spike = 0;      // newest sample minus the prior average (ms)

	public static int average() {
		return average;
	}

	public static int spike() {
		return spike;
	}

	public static void tick(Minecraft mc) {
		if (!Mods.on("ping") || mc.thePlayer == null || mc.getNetHandler() == null) {
			SAMPLES.clear();
			average = -1;
			spike = 0;
			lastSampleAt = 0;
			return;
		}
		long now = System.currentTimeMillis();
		long interval = (long) (Math.max(1, Mods.num("ping", "updateInterval")) * 1000);
		if (lastSampleAt != 0 && now - lastSampleAt < interval) {
			return;
		}
		NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
		if (info == null) {
			return;
		}
		lastSampleAt = now;
		int sample = Math.max(0, info.getResponseTime());
		int prior = average;
		int keep = (int) Math.max(2, Math.min(20, Mods.num("ping", "samples")));
		SAMPLES.addLast(sample);
		while (SAMPLES.size() > keep) {
			SAMPLES.removeFirst();
		}
		long sum = 0;
		for (int v : SAMPLES) {
			sum += v;
		}
		average = (int) Math.round(sum / (double) SAMPLES.size());
		spike = prior < 0 ? 0 : sample - prior;
	}
}
