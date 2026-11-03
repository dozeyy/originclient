package com.origin.client.client.social;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The real time, as Origin's service and Mojang see it, so a player whose PC
 * clock, date or time zone is wrong still gets friends, parties, chat and
 * cosmetics (Will, 2026-11-03: "the client doesnt need to verify your time and
 * date on your pc"). The PC clock is never the truth for anything a server
 * checks: every HTTPS answer from Origin's service (and Mojang's certificate
 * call) carries a `Date` header, and the difference to the PC clock is kept
 * here as an offset.
 *
 * Two timelines, one rule:
 *  - values that go TO a server or are compared with a server's times (the
 *    /v1/auth `at`, Mojang's certificate expiry, the Origin token's expiry) use
 *    {@link #now()};
 *  - times that come FROM a server and are shown or compared with the PC clock
 *    on this side (chat times, "last seen") go through {@link #toLocal(long)}.
 *
 * `Date` has whole seconds, so an answer moves the offset only when it differs
 * by more than {@link #TOLERANCE_MS}: the offset stays stable between syncs (no
 * jitter in shown times) and still follows a real change (the player fixes the
 * clock mid-session). Until the first answer the offset is 0 (the PC clock).
 *
 * Pure JDK 8 (java.time), no Minecraft code: identical in every module, 1.8.9 included.
 */
public final class ServerClock {
	private ServerClock() {
	}

	/** An answer moves the offset only when it is off by more than this (Date has 1 s resolution). */
	static final long TOLERANCE_MS = 2_000L;
	/** A Date before this (2024-01-01) is a broken header, not a time. */
	private static final long SANE_FLOOR_MS = 1_704_067_200_000L;

	private static volatile long offsetMs = 0L;
	private static volatile boolean known = false;

	/** The real time in epoch ms (the PC clock corrected by the last server answer). */
	public static long now() {
		return System.currentTimeMillis() + offsetMs;
	}

	/** A server's epoch-ms time on the PC clock's timeline (0 / missing stays as is). */
	public static long toLocal(long serverMs) {
		return serverMs <= 0 ? serverMs : serverMs - offsetMs;
	}

	/** The current offset (server minus PC clock), ms. */
	public static long offsetMs() {
		return offsetMs;
	}

	/**
	 * Takes an HTTP `Date` header value (RFC 1123, "Tue, 03 Nov 2026 13:01:52 GMT")
	 * read just now. Null or unreadable values are ignored.
	 */
	public static void observeDate(String httpDate) {
		long server = parseDate(httpDate);
		if (server > 0) {
			observe(server, System.currentTimeMillis());
		}
	}

	/** Records that the server's clock read `serverMs` when the PC clock read `localMs`. */
	public static synchronized void observe(long serverMs, long localMs) {
		long next = nextOffset(known, offsetMs, serverMs, localMs);
		offsetMs = next;
		known = true;
	}

	/**
	 * The offset after one answer (pure, for tests): `Date` is truncated to the
	 * second, so the server's time is taken as the middle of that second. The
	 * old offset stays when the new one is within {@link #TOLERANCE_MS} of it.
	 */
	static long nextOffset(boolean known, long current, long serverMs, long localMs) {
		long candidate = serverMs + 500L - localMs;
		if (!known) {
			return Math.abs(candidate) <= TOLERANCE_MS ? 0L : candidate;
		}
		return Math.abs(candidate - current) <= TOLERANCE_MS ? current : candidate;
	}

	/** RFC 1123 date -> epoch ms, or -1 when it isn't one (or is before 2024). */
	static long parseDate(String httpDate) {
		if (httpDate == null || httpDate.trim().isEmpty()) {
			return -1L;
		}
		try {
			long ms = ZonedDateTime.parse(httpDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
			return ms >= SANE_FLOOR_MS ? ms : -1L;
		} catch (RuntimeException e) {
			return -1L;
		}
	}

	/** Tests only: back to "no answer yet". */
	static synchronized void reset() {
		offsetMs = 0L;
		known = false;
	}
}
