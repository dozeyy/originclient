package com.origin.client.client.render;

/**
 * The startup loading screen's bar fill: it only ever moves forward within one
 * load, keeps moving while the load runs, and reaches full when the reload is
 * done (never before), gliding all the way.
 *
 * <p>Why it can't just draw the reload's own fraction (CI frame log 2026-10-19,
 * every module): vanilla's {@code getActualProgress()} is
 * (2 x tasks done + 2 x apply steps done + listeners prepared) /
 * (2 x tasks started + 2 x apply steps queued + listener count). A listener's
 * render-thread apply step only enters the denominator once it is queued, which
 * is after every listener has prepared, so the fraction climbs to 0.93-1.0
 * during preparation and then DROPS (to 0.57 on 1.16.5, 0.68 on 1.21.1, 0.73
 * on 1.20.4) when the apply steps are queued, then climbs again to 1 at done.
 * On 1.21.2+ thousands of worker tasks are counted too, so it sits at 0.9999
 * for the whole apply stretch instead. The apply stretch was 30-55% of the time
 * the loading screen was up (CI). The old rule followed the raw value and
 * re-started the bar on any drop of more than 0.25: the bar jumped backward
 * (1.16.5-1.19.4, 1.21, 1.21.1), or sat still (1.20.x, 1.21.2+).
 *
 * <p>The rule now, per frame:
 * <ul>
 *   <li>the reload's fraction counts for {@link #PREP_SHARE} of the bar while it
 *       runs (raw 1 before done is not "finished"); done is 1;</li>
 *   <li>it rises from {@code last}: the bar covers the same share of what is
 *       LEFT, {@code target += (1 - target) * (raw - last) / (1 - last)}. With no
 *       dips this is exactly {@code target = raw}; after a dip the bar moves on
 *       every rise, never backward;</li>
 *   <li>it doesn't rise (a dip, or one long step): the bar creeps on toward at
 *       most {@link #CREEP_MAX} of what is left ({@link #CREEP_MS}), so it never
 *       stands still and never reaches full before the reload is done;</li>
 *   <li>a different reload (another ReloadInstance) replacing one that had not
 *       finished: the bar CONTINUES from where it is and the new reload's
 *       progress fills what is left (to the player it is still one load);</li>
 *   <li>a load that starts after the last one finished (resource pack change,
 *       F3+T): the bar starts over from that load's progress.</li>
 * </ul>
 * The drawn fill glides toward the target ({@code tauMs}), never below the last
 * drawn value, the same at any frame rate.
 *
 * <p>Pure logic (no Minecraft types), Java 8 clean: the same file is in every
 * Fabric module (shared/ for 1.20+, a byte-identical copy in each pre-1.20
 * module) and is tested by tools/loading-bar-tests.
 */
public final class LoadBar {
	/** The bar share the reload's own fraction maps to before it is done. */
	static final double PREP_SHARE = 0.75;
	/** While the fraction doesn't rise, the bar creeps toward this share of what is left... */
	static final double CREEP_MAX = 0.75;
	/** ...with this time constant (ms). */
	static final double CREEP_MS = 1200.0;

	private static final Object NONE = new Object();

	private Object reload = NONE;  // the reload being shown (identity; null = unknown)
	private boolean finished;      // the load being shown has completed
	private double last;           // its (scaled) fraction last frame
	private double target = -1;    // monotonic mapped progress, 0..1 (-1 = nothing shown yet)
	private double creepFrom;      // target when the fraction last rose
	private double creepAt;        // clock (ms) then
	private double fill = -1;      // what is drawn (glides toward target)
	private double fillAt;         // clock (ms) of the last update

	/**
	 * One frame. {@code reload} identifies the running reload (null when it can't
	 * be read), {@code done} says it has finished, {@code raw} is its fraction
	 * done, {@code nowMs} a monotonic clock. Returns the fill to draw, 0..1.
	 */
	public double update(Object reload, boolean done, double raw, double nowMs, double tauMs) {
		raw = done ? 1 : PREP_SHARE * (raw != raw ? 0 : Math.max(0, Math.min(1, raw)));   // NaN-safe
		if (target < 0 || (finished && !done)) {
			// The first frame, or a new load after a finished one: a fresh bar.
			this.reload = reload;
			finished = done;
			last = raw;
			target = raw;
			creepFrom = raw;
			creepAt = nowMs;
			fill = raw;
			fillAt = nowMs;
			return fill;
		}
		if (reload != this.reload) {
			// It replaced a load still in progress: keep going from here.
			this.reload = reload;
			last = 0;
		}
		if (done) {
			target = 1;
			finished = true;
		} else if (raw > last) {
			target = Math.max(target, target + (1 - target) * (raw - last) / (1 - last));
			creepFrom = target;
			creepAt = nowMs;
		} else {
			double creep = creepFrom + (1 - creepFrom) * CREEP_MAX * (1 - Math.exp(-Math.max(0, nowMs - creepAt) / CREEP_MS));
			target = Math.max(target, creep);
		}
		last = raw;
		double k = tauMs > 0 ? Math.exp(-Math.max(0, nowMs - fillAt) / tauMs) : 0;
		fill = Math.max(fill, target + (fill - target) * k);
		if (done && fill > 0.9999) {
			fill = 1;
		}
		fillAt = nowMs;
		return fill;
	}
}
