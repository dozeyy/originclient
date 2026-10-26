package com.origin.client.client;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Bench-only A/B switches for the Phase 6 optimisations (judge fix, 2026-10-25): each one turns ONE kept pick
 * back to its old behaviour so the bench can measure "pick on" vs "pick off" in the same round
 * (tools/bench/ab189.py). Read once at class init from {@code -Dorigin.bench.off=<list>} or env
 * {@code ORIGIN_BENCH_OFF}, and ONLY when bench mode itself is on (origin.bench / ORIGIN_BENCH set, which
 * never happens in a player's game) -- so for players every flag is a constant false the JIT folds away.
 *
 * Names: {@code hud} = pick 3 (fill batching + cached HUD strings), {@code minimap} = pick 4 (reused scan
 * position + unchanged-chunk skip), {@code particles} = pick 5 (per-config-epoch particle snapshot +
 * Mods.on cache). Deliberately independent of OriginBench so loading it pulls in no game classes.
 */
public final class BenchSwitches {
	private static final List<String> OFF = parse();

	/** Pick 3 off: Gfx.batchBegin/End do nothing (every fill is its own draw) and HUD strings are rebuilt per call. */
	public static final boolean HUD_OFF = OFF.contains("hud");
	/** Pick 4 off: a new BlockPos per scanned column / y step, and every refresh scan recolours + re-uploads. */
	public static final boolean MINIMAP_OFF = OFF.contains("minimap");
	/** Pick 5 off: particle settings and Mods.on are resolved again on every call. */
	public static final boolean PARTICLES_OFF = OFF.contains("particles");

	private BenchSwitches() {
	}

	/** The switched-off picks as written into bench.json ("" = every pick as shipped). */
	public static String describe() {
		return String.join(",", OFF);
	}

	private static List<String> parse() {
		String bench = read("origin.bench", "ORIGIN_BENCH");
		if (bench == null || bench.trim().isEmpty()) {
			return java.util.Collections.emptyList();   // a player's game: never switch anything
		}
		String off = read("origin.bench.off", "ORIGIN_BENCH_OFF");
		if (off == null || off.trim().isEmpty()) {
			return java.util.Collections.emptyList();
		}
		return Arrays.asList(off.toLowerCase(Locale.ROOT).replace(" ", "").split(","));
	}

	private static String read(String prop, String env) {
		String v = System.getProperty(prop);
		return v != null ? v : System.getenv(env);
	}
}
