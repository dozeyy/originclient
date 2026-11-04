import com.origin.client.client.BorderlessFit;

// BorderlessFit (Borderless Fullscreen's window maths) without Minecraft. The
// properties: the window goes borderless on the monitor holding its centre (else the
// one it overlaps most, else none); "covers" means exactly the monitor rect; the
// keep-covering guard waits for a fresh fit to settle, checks at most twice a second,
// and caps re-fits so a window that keeps refusing the size is never fought in a
// loop. Case "will-2026-11-04" is the reported bug's shape (sizes illustrative): a
// game started with Borderless on ends up frameless but smaller than its monitor,
// which must read as "not covering" so it gets fitted again.
// Run by run.py against the shared copy (every module's copy must be byte-identical).
public class BorderlessFitTests {
	static int passed = 0, failed = 0;

	static void check(boolean ok, String what) {
		if (ok) {
			passed++;
		} else {
			failed++;
			System.out.println("FAIL: " + what);
		}
	}

	public static void main(String[] args) {
		int[] left = {0, 0, 2560, 1440};
		int[] right = {2560, 200, 1920, 1080};
		int[][] two = {left, right};

		// pickMonitor
		check(BorderlessFit.pickMonitor(640, 360, 1280, 720, two) == 0, "window centred on the left monitor picks it");
		check(BorderlessFit.pickMonitor(3000, 400, 1280, 720, two) == 1, "window on the right monitor picks it");
		check(BorderlessFit.pickMonitor(2000, 300, 1280, 720, two) == 1,
				"straddling window goes where its centre is (centre x 2640 is on the right)");
		check(BorderlessFit.pickMonitor(2300, 0, 1280, 100, two) == 0,
				"centre in no monitor (above the right one): the most-overlapped one wins");
		check(BorderlessFit.pickMonitor(-5000, -5000, 100, 100, two) == -1, "off every monitor: -1 (caller uses primary)");
		check(BorderlessFit.pickMonitor(0, 0, 100, 100, new int[0][]) == -1, "no monitors: -1");
		check(BorderlessFit.pickMonitor(0, 0, 100, 100, null) == -1, "null monitor list: -1");
		check(BorderlessFit.pickMonitor(10, 10, 100, 100, new int[][] {null, left}) == 1, "a monitor GLFW could not read is skipped");
		check(BorderlessFit.pickMonitor(-1920, 0, 1920, 1080, new int[][] {left, {-1920, 0, 1920, 1080}}) == 1,
				"monitor left of the primary (negative x) is found");
		check(BorderlessFit.pickMonitor(Integer.MAX_VALUE - 10, 0, 100, 100, two) == -1, "huge coordinates do not overflow");

		// covers
		check(BorderlessFit.covers(0, 0, 2560, 1440, left), "exact monitor rect covers");
		check(!BorderlessFit.covers(0, 0, 1920, 1080, left), "will-2026-11-04: frameless top-left 1920x1080 on a 2560x1440 monitor is NOT covering");
		check(!BorderlessFit.covers(0, 0, 2561, 1440, left), "one pixel wider is not an exact cover");
		check(!BorderlessFit.covers(1, 0, 2560, 1440, left), "shifted by one is not a cover");
		check(!BorderlessFit.covers(0, 0, 2560, 1440, null), "no monitor never covers");

		// Guard: settle after a fit, then at most one look per CHECK_MS.
		BorderlessFit.Guard g = new BorderlessFit.Guard();
		long t = 1_000_000;
		g.reset(t);
		check(!g.shouldCheck(t + 100), "no look right after entering borderless (window still settling)");
		check(!g.shouldCheck(t + BorderlessFit.Guard.SETTLE_MS - 1), "no look until the settle time passed");
		check(g.shouldCheck(t + BorderlessFit.Guard.SETTLE_MS), "first look once settled");
		check(!g.shouldCheck(t + BorderlessFit.Guard.SETTLE_MS + 10), "no second look within CHECK_MS");
		check(g.shouldCheck(t + BorderlessFit.Guard.SETTLE_MS + BorderlessFit.Guard.CHECK_MS), "next look after CHECK_MS");

		// Guard: a re-fit restarts the settle wait; at most BURST_MAX re-fits per BURST_MS.
		long now = t + 2_000;
		check(g.mayRefit(now), "first re-fit allowed (the reported case gets fixed)");
		check(!g.shouldCheck(now + 100), "after a re-fit, wait for it to settle before looking again");
		int allowed = 1;
		for (int i = 1; i < 10; i++) {
			if (g.mayRefit(now + i * 1_000)) {
				allowed++;
			}
		}
		check(allowed == BorderlessFit.Guard.BURST_MAX, "a window that keeps refusing gets " + BorderlessFit.Guard.BURST_MAX
				+ " re-fits per burst, not one per check (got " + allowed + ")");
		check(g.mayRefit(now + BorderlessFit.Guard.BURST_MS + 5_000), "the cap lifts once the burst window has passed");
		g.reset(now + 20_000);
		int afterReset = 0;
		for (int i = 0; i < BorderlessFit.Guard.BURST_MAX; i++) {
			if (g.mayRefit(now + 20_001 + i)) {
				afterReset++;
			}
		}
		check(afterReset == BorderlessFit.Guard.BURST_MAX, "a real toggle (reset) gives a full budget again");

		// A fresh guard (no fit yet) may look straight away.
		check(new BorderlessFit.Guard().shouldCheck(5), "a guard that never fitted looks immediately");

		System.out.println("BorderlessFit: " + passed + " passed, " + failed + " failed");
		System.exit(failed == 0 ? 0 : 1);
	}
}
