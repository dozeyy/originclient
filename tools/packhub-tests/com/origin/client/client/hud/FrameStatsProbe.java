package com.origin.client.client.hud;

// Test door into FrameStats' package-private reset().
public final class FrameStatsProbe {
	private FrameStatsProbe() {
	}

	public static void reset() {
		FrameStats.reset();
	}
}
