package com.origin.client.client.render;

/**
 * The startup loading screen's bar fill: it only ever moves forward within one
 * load, keeps moving while the load runs, and reaches full when the reload is
 * done (never before), gliding all the way. The world-loading scenes' bar
 * follows the same rules ({@link #scene}).
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
 *   <li>meanwhile it creeps, every frame: what is left shrinks as
 *       {@code dLeft/dt = -left^2 / CREEP_K_MS} ({@link #CREEP_K_MS}), so it
 *       never stands still (a dip, one long step, or a fraction inching up by
 *       tiny amounts) and slows the nearer it gets to full, so it never gets
 *       there before done. (An exponential creep restarted on each rise -- the
 *       first version -- compounded: every small rise gave it a fresh 75% of what
 *       was left, and a long, steadily rising load, 1.18.2's chunk count in CI
 *       at 1% a second for 90 s, drew a full bar long before done.)</li>
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
 * module (shared/ for 1.20+, a byte-identical copy in each pre-1.20 module
 * and in 1.8.9) and is tested by tools/loading-bar-tests.
 */
public final class LoadBar {
	/** The bar share the reload's own fraction maps to before it is done. */
	static final double PREP_SHARE = 0.75;
	/**
	 * The creep's scale (ms): from what is left {@code L} it takes {@code CREEP_K_MS * (1/L' - 1/L)}
	 * to reach {@code L'}: 0.25 -> 0.125 in 16 s, 0.05 -> 0.04 in 20 s.
	 */
	static final double CREEP_K_MS = 4000.0;

	private static final Object NONE = new Object();

	private Object reload = NONE;  // the reload being shown (identity; null = unknown)
	private boolean finished;      // the load being shown has completed
	private double last;           // its (scaled) fraction last frame
	private double target = -1;    // monotonic mapped progress, 0..1 (-1 = nothing shown yet)
	private double fill = -1;      // what is drawn (glides toward target)
	private double fillAt;         // clock (ms) of the last update

	/**
	 * One frame. {@code reload} identifies the running reload (null when it can't
	 * be read), {@code done} says it has finished, {@code raw} is its fraction
	 * done, {@code nowMs} a monotonic clock. Returns the fill to draw, 0..1.
	 */
	public double update(Object reload, boolean done, double raw, double nowMs, double tauMs) {
		raw = done ? 1 : PREP_SHARE * (raw != raw ? 0 : Math.max(0, Math.min(1, raw)));   // NaN-safe
		if (Double.isNaN(nowMs) || Double.isInfinite(nowMs)) {
			nowMs = fillAt;   // a broken clock reading: no time passes (else the fill turns NaN for good)
		}
		if (target < 0 || (finished && !done)) {
			// The first frame, or a new load after a finished one: a fresh bar.
			this.reload = reload;
			finished = done;
			last = raw;
			target = raw;
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
		} else {
			// A rise covers its share of what is left; then the creep (exact over
			// the frame's time, so the same at any frame rate).
			double left = 1 - target;
			if (raw > last) {
				left *= (1 - raw) / (1 - last);
			}
			left /= 1 + left * Math.max(0, nowMs - fillAt) / CREEP_K_MS;
			target = 1 - left;
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

	/** Frames drawn without the world-loading scene that mean the last world load is over. */
	public static final long NEW_LOAD_FRAMES = 10;

	private long sceneFrame = -1;  // the game's frame count at the last world-loading scene
	private double sceneRaw = -1;  // the last measured stage fraction of this world load (-1 = none yet)

	/**
	 * The world-loading scenes' bar (LevelLoadingScreen, ReceivingLevelScreen,
	 * ProgressScreen, ...): the same rules as the startup bar, with a world load
	 * as "the load". {@code frame} is the game's running frame count, {@code
	 * progress} the scene's measured fraction (0..1) or -1 when the scene has
	 * none. Returns the fill to draw, or -1 for the sweep.
	 *
	 * <p>Why (CI frame log 2026-10-19): up to 1.21.8 the chunk count runs to 100%
	 * and then "Loading terrain" (no measure) took over and the bar jumped from
	 * full back to the sweep; from 1.21.10 the count sits at 100% while the client
	 * still waits for chunks, so the bar was full seconds before the world was.
	 * Now:
	 * <ul>
	 *   <li>the sweep only shows before this world load's first measured stage; a
	 *       scene with no measure after that holds the bar and lets it creep;</li>
	 *   <li>a measured fraction drives {@link #update} (its share, rises, dips
	 *       held, creep), never done: the world replaces the scene the moment it
	 *       is ready, so the bar is never drawn full before that;</li>
	 *   <li>a new world load is told apart by frames, not time: more than
	 *       {@link #NEW_LOAD_FRAMES} frames drawn without the scene (a menu, the
	 *       world) end the last one. A render-thread stall inside one load (JEI
	 *       starting at login: 3 s, no frames at all) does not.</li>
	 * </ul>
	 */
	public double scene(long frame, double progress, double nowMs, double tauMs) {
		if (sceneFrame < 0 || frame < sceneFrame || frame - sceneFrame - 1 > NEW_LOAD_FRAMES) {
			target = -1;     // a new world load: a fresh bar at its first measured stage
			sceneRaw = -1;
		}
		sceneFrame = frame;
		if (progress >= 0) {   // false for NaN too: no measure this frame
			sceneRaw = Math.min(1, progress);
		}
		return sceneRaw < 0 ? -1 : update(this, false, sceneRaw, nowMs, tauMs);
	}
}
