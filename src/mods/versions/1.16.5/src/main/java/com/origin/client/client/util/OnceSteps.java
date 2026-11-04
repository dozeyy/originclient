package com.origin.client.client.util;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * A config fix-up that runs exactly ONCE per player config, gated by a version marker in the
 * config's meta block (originclient-mods.json "meta"), never by an in-memory flag: an
 * in-memory flag resets every launch, so a "one-time" step would rerun each session and
 * undo whatever the player did since (review 2026-11-04: the moved default keys were
 * rewritten to the old V/M/G every launch for a player who turned the mod on later).
 *
 * <p>The marker is written BEFORE the step runs and whatever the step finds, so a config
 * with nothing to fix is marked done too. It reaches disk with the config's next save, and
 * Settings Sync carries it like any other meta value, so every version agrees it is done.
 * Until it is on disk a later load simply runs the step again on the same, unchanged state
 * (same outcome), which is why each step must only ever fill gaps, never overwrite.
 *
 * <p>Plain strings in and out (the caller adapts its JSON), so it is pure, Java 8, and
 * tested by tools/qol-tests. Version-independent, so it lives in shared/.
 */
public final class OnceSteps {
	private OnceSteps() {
	}

	/**
	 * Runs {@code step} if the marker stored under {@code marker} (its text via
	 * {@code read}, null when absent) is below {@code version}; a hand-edited non-number
	 * counts as not done. Returns true when the step ran.
	 */
	public static boolean run(String marker, int version, Function<String, String> read,
			BiConsumer<String, Integer> write, Runnable step) {
		if (done(read.apply(marker), version)) {
			return false;
		}
		write.accept(marker, version);
		step.run();
		return true;
	}

	static boolean done(String stored, int version) {
		if (stored == null) {
			return false;
		}
		try {
			return Integer.parseInt(stored.trim()) >= version;
		} catch (NumberFormatException handEdited) {
			return false;
		}
	}
}
