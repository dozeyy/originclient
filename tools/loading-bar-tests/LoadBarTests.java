import com.origin.client.client.render.LoadBar;

import java.util.Random;

// LoadBar (the loading screen's bar fill) without Minecraft. The properties:
// within one load the drawn fill never decreases; it never reaches 1 before the
// reload is done and reaches exactly 1 shortly after; it never stands still
// while the load runs (it keeps moving through dips and long steps); no frame
// jumps (glide, no snap); a reload replacing an unfinished one continues the
// bar; a load after a finished one starts it over. The raw sequences of cases 2
// and 3 are the CI frame-log traces that showed the bug (2026-10-19). Run by
// run.py against the shared copy (every module's copy must be byte-identical).
public class LoadBarTests {
	static final double TAU = 140.0, FRAME = 16.0;
	static int passed = 0, failed = 0;

	static void check(boolean ok, String what) {
		if (ok) {
			passed++;
		} else {
			failed++;
			if (failed < 40) {
				System.out.println("FAIL: " + what);
			}
		}
	}

	/** A load as frames: raw[i] at frame i (16 ms each), done from frame doneAt on. */
	static double[] load(LoadBar bar, Object reload, double[] raw, int doneAt, String name) {
		double[] f = new double[raw.length];
		double prev = -1;
		for (int i = 0; i < raw.length; i++) {
			boolean done = i >= doneAt;
			f[i] = bar.update(reload, done, raw[i], i * FRAME, TAU);
			check(f[i] >= 0 && f[i] <= 1, name + ": out of range at " + i + ": " + f[i]);
			check(f[i] >= prev, name + ": went back at frame " + i + " (" + prev + " -> " + f[i] + ")");
			if (prev >= 0) {
				check(f[i] - prev <= 0.06, name + ": jumped " + (f[i] - prev) + " at frame " + i);
			}
			if (!done) {
				check(f[i] < 1, name + ": full before done at frame " + i);
			}
			prev = f[i];
		}
		return f;
	}

	/** Frames [from, to) all not done: the fill must move over every 250 ms window. */
	static void moving(double[] f, int from, int to, String name) {
		int w = (int) (250 / FRAME);
		for (int i = from; i + w < to; i += w) {
			check(f[i + w] > f[i] + 1e-4, name + ": stood still frames " + i + ".." + (i + w) + " at " + f[i]);
		}
	}

	/** Within 0.5 s of done (the settle hold) the bar reads full; exactly 1 within 1.6 s. */
	static void nearlyFull(double[] f, int doneAt, String name) {
		int half = doneAt + (int) (500 / FRAME), full = doneAt + (int) (1600 / FRAME);
		check(f.length > full, name + ": test too short");
		check(f[half] >= 0.99, name + ": not ~full 0.5 s after done (" + f[half] + ")");
		check(f[full] == 1.0, name + ": not exactly 1 1.6 s after done (" + f[full] + ")");
	}

	static double[] seq(int n, double... knots) {
		// Piecewise: knots are (frame, value) pairs; holds the value until the next knot.
		double[] r = new double[n];
		int k = 0;
		for (int i = 0; i < n; i++) {
			while (k + 2 < knots.length && i >= knots[k + 2]) {
				k += 2;
			}
			r[i] = knots[k + 1];
		}
		return r;
	}

	static double[] ramp(double[] r, int from, int to, double a, double b) {
		for (int i = from; i < to; i++) {
			r[i] = a + (b - a) * (i - from) / (double) (to - from);
		}
		return r;
	}

	public static void main(String[] args) {
		Object reload = new Object();

		// 1. A steadily rising load: tracks it, never full before done, full after.
		{
			LoadBar bar = new LoadBar();
			double[] raw = ramp(new double[460], 0, 300, 0, 1);
			for (int i = 300; i < 460; i++) {
				raw[i] = 1;
			}
			double[] f = load(bar, reload, raw, 330, "rising");
			moving(f, 0, 330, "rising");
			nearlyFull(f, 330, "rising");
		}

		// 2. 1.16.5 (CI): preparation takes raw to 1.0, the apply steps are queued
		//    (raw 0.5745) and run for ~2.1 s, then done.
		{
			LoadBar bar = new LoadBar();
			double[] raw = ramp(new double[480], 0, 230, 0.3, 1.0);
			for (int i = 230; i < 480; i++) {
				raw[i] = i < 360 ? 0.5745 : 1.0;
			}
			double[] f = load(bar, reload, raw, 360, "1.16.5");
			moving(f, 0, 360, "1.16.5");
			nearlyFull(f, 360, "1.16.5");
		}

		// 3. 1.21.10 (CI): raw reaches 0.9999 during preparation and stays there for
		//    the ~1.2 s apply stretch.
		{
			LoadBar bar = new LoadBar();
			double[] raw = ramp(new double[360], 0, 160, 0.2, 0.9999);
			for (int i = 160; i < 360; i++) {
				raw[i] = i < 235 ? 0.9999 : 1.0;
			}
			double[] f = load(bar, reload, raw, 235, "1.21.10");
			moving(f, 0, 235, "1.21.10");
			nearlyFull(f, 235, "1.21.10");
		}

		// 4. 1.21.1 (CI): 0.97 -> 0.9355 -> 1.0 (not done) -> 0.8919 -> 0.6842 -> 0.978 -> done.
		{
			LoadBar bar = new LoadBar();
			double[] raw = seq(330, 0, 0.38, 20, 0.52, 40, 0.62, 60, 0.76, 80, 0.93, 100, 0.9655,
					120, 0.9355, 140, 1.0, 145, 0.8919, 150, 0.6842, 165, 0.9683, 180, 0.973, 200, 0.978, 215, 1.0);
			double[] f = load(bar, reload, raw, 215, "1.21.1");
			moving(f, 0, 215, "1.21.1");
			nearlyFull(f, 215, "1.21.1");
		}

		// 5. A second reload replacing an unfinished one: continues, never back, ends full.
		{
			LoadBar bar = new LoadBar();
			double before = 0;
			for (int i = 0; i < 60; i++) {
				before = bar.update(reload, false, i / 100.0, i * FRAME, TAU);
			}
			Object second = new Object();
			double prev = before;
			for (int i = 0; i < 300; i++) {
				double raw = Math.min(1, 0.05 + i * 0.01);
				boolean done = i >= 150;
				double v = bar.update(second, done, raw, (60 + i) * FRAME, TAU);
				check(v >= prev, "replace: went back at " + i + " (" + prev + " -> " + v + ")");
				prev = v;
			}
			check(prev == 1.0, "replace: ends full (" + prev + ")");
		}

		// 6. A load after a finished one (resource pack change): the bar starts over.
		{
			LoadBar bar = new LoadBar();
			for (int i = 0; i < 100; i++) {
				bar.update(reload, i >= 50, i / 50.0, i * FRAME, TAU);
			}
			double v = bar.update(new Object(), false, 0.02, 200 * FRAME, TAU);
			check(v < 0.05, "new load after a finished one starts over (" + v + ")");
		}

		// 7. Unknown identity (accessor missing: vanilla's own value, done = it reached 1):
		//    a finished bar starts over when the next load begins.
		{
			LoadBar bar = new LoadBar();
			for (int i = 0; i <= 100; i++) {
				double raw = Math.min(1, i / 50.0);
				bar.update(null, raw >= 1, raw, i * FRAME, TAU);
			}
			double v = bar.update(null, false, 0.1, 200 * FRAME, TAU);
			check(v < 0.1, "null identity: new load starts over (" + v + ")");
		}

		// 8. The dissolve passes 1 for a done reload, sometimes after the overlay's
		//    reload reference is gone (identity null): no step back, ends full.
		{
			LoadBar bar = new LoadBar();
			double a = 0;
			for (int i = 0; i < 40; i++) {
				a = bar.update(reload, false, i / 40.0, i * FRAME, TAU);
			}
			double b = bar.update(reload, true, 1, 40 * FRAME, TAU);
			double c = bar.update(null, true, 1, 41 * FRAME, TAU);
			check(b >= a && c >= b, "dissolve: no step back (" + a + " -> " + b + " -> " + c + ")");
		}

		// 9. Review case (08d004e6): raw inches 0.99 -> 0.9999 over 3 s, a tiny rise every
		//    frame. The bar must keep moving as visibly as on a flat raw (it used to
		//    restart the creep every frame and crawl ~1 px).
		{
			LoadBar inch = new LoadBar(), flat = new LoadBar();
			int frames = (int) (3000 / FRAME);
			double a0 = 0, a1 = 0, b0 = 0, b1 = 0;
			for (int i = 0; i <= 40; i++) {   // reach 0.99 first
				inch.update(reload, false, 0.99 * i / 40, i * FRAME, TAU);
				flat.update(reload, false, 0.99 * i / 40, i * FRAME, TAU);
			}
			double prev = -1;
			for (int i = 1; i <= frames; i++) {
				double t = (40 + i) * FRAME;
				double v = inch.update(reload, false, 0.99 + 0.0099 * i / frames, t, TAU);
				double f = flat.update(reload, false, 0.99, t, TAU);
				check(v >= prev && v < 1, "micro-rise: monotonic, not full");
				prev = v;
				if (i == 1) {
					a0 = v;
					b0 = f;
				}
				a1 = v;
				b1 = f;
			}
			check(a1 - a0 >= 0.8 * (b1 - b0), "micro-rise: moves like a flat raw (" + (a1 - a0) + " vs " + (b1 - b0) + ")");
		}

		// 10. A non-finite clock reading never turns the fill NaN.
		{
			LoadBar bar = new LoadBar();
			bar.update(reload, false, 0.2, 0, TAU);
			double v = bar.update(reload, false, 0.3, Double.NaN, TAU);
			double w = bar.update(reload, false, 0.4, Double.POSITIVE_INFINITY, TAU);
			double x = bar.update(reload, false, 0.5, 500, TAU);
			check(!Double.isNaN(v) && !Double.isNaN(w) && !Double.isNaN(x) && x >= w && w >= v, "NaN clock: fill stays a number (" + v + ", " + w + ", " + x + ")");
		}

		// 11. Fuzz: random loads with random dips and plateaus at 8..1000 fps.
		Random rnd = new Random(42);
		for (int trial = 0; trial < 2000; trial++) {
			LoadBar bar = new LoadBar();
			double raw = 0, t = 0, prev = -1;
			int frames = 20 + rnd.nextInt(400);
			for (int i = 0; i < frames; i++) {
				double r = rnd.nextDouble();
				if (r < 0.08) {
					raw = Math.max(0, raw - rnd.nextDouble() * 0.6);
				} else if (r < 0.7) {
					raw = Math.min(1, raw + rnd.nextDouble() * 0.05);
				}
				t += 1 + rnd.nextDouble() * 120;
				double v = bar.update(reload, false, raw, t, TAU);
				check(v >= prev && v < 1, "fuzz " + trial + ": frame " + i + " (" + prev + " -> " + v + ")");
				prev = v;
			}
			for (int i = 0; i < 200; i++) {
				t += 16;
				double v = bar.update(reload, true, 1, t, TAU);
				check(v >= prev, "fuzz " + trial + ": back after done");
				prev = v;
			}
			check(prev == 1.0, "fuzz " + trial + ": ends at exactly 1 (" + prev + ")");
		}

		// --- The world-loading bar (LoadBar.scene): frame = the game's frame count. ---

		// 12. Up to 1.21.8 (CI 1.21.1 / 1.16.5): "Preparing for world creation" (no
		// measure) -> the chunk count 0 -> 100% -> "Loading terrain" (no measure).
		// The old bar jumped from full back to the sweep there.
		{
			LoadBar bar = new LoadBar();
			long frame = 100;
			double t = 0, prev = -1;
			boolean measured = false;
			for (int i = 0; i < 520; i++) {
				double p = i < 40 ? -1 : i < 140 ? 0 : i < 340 ? (i - 140) / 200.0 : i < 360 ? 1 : -1;
				frame++;
				t += FRAME;
				double v = bar.scene(frame, p, t, TAU);
				measured |= p >= 0;
				if (!measured) {
					check(v == -1, "world 1.21.1: sweeps before the first count (" + v + ")");
					continue;
				}
				check(v >= 0 && v < 1, "world 1.21.1: in range, never full (" + v + " at " + i + ")");
				check(prev < 0 || v >= prev, "world 1.21.1: went back at " + i + " (" + prev + " -> " + v + ")");
				check(prev < 0 || v - prev <= 0.06, "world 1.21.1: jumped " + (v - prev) + " at " + i);
				if (i >= 361 && i % 15 == 0) {
					check(v > prev, "world 1.21.1: stands still in Loading terrain at " + i);
				}
				prev = v;
			}
			check(prev > 0.9, "world 1.21.1: near full at the end (" + prev + ")");
		}

		// 13. 1.21.10+ (CI): the count sits at 100% while the client waits for chunks,
		// with a 3 s render-thread stall (JEI starting at login: no frames at all).
		{
			LoadBar bar = new LoadBar();
			long frame = 5000;
			double t = 0, prev = -1;
			for (int i = 0; i < 400; i++) {
				double p = i < 200 ? i / 200.0 : 1;
				frame++;
				t += i == 250 ? 3000 : FRAME;
				double v = bar.scene(frame, p, t, TAU);
				check(v >= prev && v < 1, "world 1.21.10: monotonic, never full (" + prev + " -> " + v + " at " + i + ")");
				prev = v;
			}
		}

		// 14. A few frames without the scene inside one load (a screen swap) keep the
		// bar; a menu or the world (more than NEW_LOAD_FRAMES) ends the load, and the
		// next one sweeps again, then starts a fresh bar from its own count.
		{
			LoadBar bar = new LoadBar();
			long frame = 0;
			double t = 0, v = -1;
			for (int i = 0; i < 300; i++) {
				t += FRAME;
				v = bar.scene(++frame, i / 300.0, t, TAU);
			}
			double before = v;
			frame += LoadBar.NEW_LOAD_FRAMES;    // exactly the limit: still the same load
			t += FRAME;
			v = bar.scene(++frame, -1, t, TAU);
			check(v >= before, "world: a short gap keeps the bar (" + before + " -> " + v + ")");
			frame += 5000;                        // played the world for a while
			t += 80000;
			v = bar.scene(++frame, -1, t, TAU);
			check(v == -1, "world: a new load sweeps again (\"Saving world\") (" + v + ")");
			frame += 2000;
			t += 30000;
			v = bar.scene(++frame, 0.02, t, TAU);
			check(v >= 0 && v < 0.05, "world: a new load starts over from its count (" + v + ")");
			v = bar.scene(frame - 50, 0.03, t + FRAME, TAU);   // a frame counter that went back: new load too
			check(v >= 0 && v < 0.05, "world: frame counter reset starts over (" + v + ")");
		}

		// 15. Fuzz: random measured stages, unmeasured scenes, stalls and short gaps.
		Random wr = new Random(7);
		for (int trial = 0; trial < 2000; trial++) {
			LoadBar bar = new LoadBar();
			long frame = wr.nextInt(100000);
			double t = wr.nextDouble() * 1e6, prev = -1, p = -1;
			int n = 20 + wr.nextInt(500);
			for (int i = 0; i < n; i++) {
				double r = wr.nextDouble();
				if (r < 0.05) {
					p = -1;                                     // a scene with no measure
				} else if (r < 0.1) {
					p = wr.nextDouble() * 0.3;                  // a new stage restarts its count
				} else if (r < 0.7) {
					p = Math.min(1, Math.max(0, p) + wr.nextDouble() * 0.04);
				}
				frame += 1 + (wr.nextDouble() < 0.03 ? wr.nextInt((int) LoadBar.NEW_LOAD_FRAMES) : 0);
				t += wr.nextDouble() < 0.01 ? 4000 : 1 + wr.nextDouble() * 120;
				double v = bar.scene(frame, p, t, TAU);
				check(v < 1 && (prev < 0 ? true : v >= prev), "world fuzz " + trial + ": frame " + i + " (" + prev + " -> " + v + ")");
				prev = v;
			}
		}

		System.out.println("LoadBar: " + passed + " checks passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}
}
