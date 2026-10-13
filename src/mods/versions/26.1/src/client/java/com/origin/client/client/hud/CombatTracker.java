package com.origin.client.client.hud;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Remembers which players YOU have recently hit, so the Tab Editor can paint them
 * red and float them to the top of the player list while a fight is live.
 *
 * Purely client-side and purely local: the only feed is
 * {@code MultiPlayerGameModeMixin}, which fires on every melee swing that lands on
 * an entity. Nothing here talks to the server, and no state survives the window —
 * an entry older than the combat window is treated as gone, which is also why
 * switching servers needs no reset hook.
 */
public final class CombatTracker {
	private CombatTracker() {
	}

	/** target UUID -> System.currentTimeMillis() of the last hit we landed on them. */
	private static final Map<UUID, Long> HITS = new HashMap<>();

	/** Anything older than this is dead weight no window can ever ask for again. */
	private static final long PRUNE_AFTER_MS = 120_000L;

	/** Called from the attack mixin for every entity we swing at that is a player. */
	public static void hit(UUID target) {
		long now = System.currentTimeMillis();
		if (HITS.size() > 32) {
			HITS.values().removeIf(t -> now - t > PRUNE_AFTER_MS);
		}
		HITS.put(target, now);
	}

	/**
	 * Timestamp of the last hit we landed on {@code id}, or 0 when we never hit them
	 * or the hit has aged out of {@code windowMs}. 0 sorts last, so callers can feed
	 * this straight into a comparator to lift live combat targets to the top.
	 */
	public static long lastHit(UUID id, long windowMs) {
		Long t = HITS.get(id);
		if (t == null) {
			return 0L;
		}
		return (System.currentTimeMillis() - t) <= windowMs ? t : 0L;
	}
}
