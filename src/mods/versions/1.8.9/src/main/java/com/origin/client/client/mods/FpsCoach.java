package com.origin.client.client.mods;

import com.origin.client.client.gui.FpsCoachScreen;
import com.origin.client.client.gui.FpsCoachToast;
import com.origin.client.client.util.KeyCodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.Display;

import java.util.Arrays;
import java.util.List;

// FPS Coach (Origin original): quietly times every frame while you play, and
// when you sit below your target FPS for ~20 s, offers a short ranked list of
// the settings most likely costing you frames (FpsCoachTips). "Try it" makes
// ONE change and MEASURES the difference in the same spot -- 4 s before, the
// change, a settle, 8 s after -- then shows the result with Keep / Undo.
// Measured, never guessed.
//
// What counts: only frames played in a world with no screen open, not paused,
// window focused, after the first 30 s in a world and 10 s after a dimension
// change or teleport (chunk loading). Frame times come from Forge's
// RenderTickEvent END (one call per rendered frame, on the main thread, via
// ServerHooks -> frame()); all decisions and every settings change happen in the
// client tick (ServerHooks -> tick()), never mid-frame. If frames ever stop
// arriving, the tick falls back to Minecraft's own once-a-second FPS count
// (Minecraft.getDebugFPS) so nothing hangs.
//
// Never nags: one small toast, a cooldown between toasts, per-tip "don't
// suggest this again", and never while fighting or with a screen open.
public final class FpsCoach {
	private FpsCoach() {
	}

	public static final String ID = "fpscoach";

	private static final long JOIN_GRACE_MS = 30_000;      // ignore the first 30 s in a world
	private static final long TELEPORT_GRACE_MS = 10_000;  // and 10 s after a dimension change / teleport
	private static final long LOW_FOR_MS = 20_000;         // below target this long before a toast
	private static final long COMBAT_MS = 10_000;          // "in combat" lasts this long after a hit
	private static final double BEFORE_MS = 4_000;         // test: measure the current settings
	private static final double AFTER_MS = 8_000;          // test: measure with the change
	private static final int MAX_TIPS = 6;

	// ---- frame ring (render thread only; read from the tick = same thread) ----
	private static final int CAP = 1 << 15, MASK = CAP - 1;
	private static final long[] END = new long[CAP];
	private static final float[] DT = new float[CAP];
	private static int head = 0, size = 0;
	private static long lastFrameNanos = 0;
	private static long lastFrameEventMs = 0;

	// ---- watch state ----
	private static boolean recordWatch = false, recordable = false;
	private static WorldClient lastLevel = null;
	private static Vec3 lastPos = null;
	private static long joinedAt = 0, teleportAt = 0, lastCombatAt = 0, lastEvalAt = 0, lowSince = 0, lastToastAt = 0;
	private static boolean keyWasDown = false;
	private static final long[] MEM_FLOOR = new long[20];
	private static int memSlot = 0;
	private static long memMinThisSecond = Long.MAX_VALUE, memSecondAt = 0;

	// ---- test state ----
	private enum Phase { NONE, BEFORE, SETTLE, AFTER }

	/** Average FPS and 1% low over a stretch of frames; low = 0 when not computed. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Stats {
		private final int frames;
		private final double ms;
		private final double avg;
		private final double low;

		public Stats(int frames, double ms, double avg, double low) {
			this.frames = frames;
			this.ms = ms;
			this.avg = avg;
			this.low = low;
		}

		public int frames() {
			return this.frames;
		}

		public double ms() {
			return this.ms;
		}

		public double avg() {
			return this.avg;
		}

		public double low() {
			return this.low;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Stats)) {
				return false;
			}
			Stats other = (Stats) obj;
			return this.frames == other.frames
					&& Double.compare(this.ms, other.ms) == 0
					&& Double.compare(this.avg, other.avg) == 0
					&& Double.compare(this.low, other.low) == 0;
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(frames, ms, avg, low);
		}

		public static final Stats EMPTY = new Stats(0, 0, 0, 0);
	}

	/** A finished (or stopped) test. after == null when it stopped before measuring. */
	// Java 8 has no records: a final value class with the same canonical
	// constructor, accessors and equals/hashCode the record had.
	public static final class Result {
		private final String change;
		private final Stats before;
		private final Stats after;
		private final boolean moved;
		private final boolean stopped;
		private final Runnable undo;

		public Result(String change, Stats before, Stats after, boolean moved, boolean stopped, Runnable undo) {
			this.change = change;
			this.before = before;
			this.after = after;
			this.moved = moved;
			this.stopped = stopped;
			this.undo = undo;
		}

		public String change() {
			return this.change;
		}

		public Stats before() {
			return this.before;
		}

		public Stats after() {
			return this.after;
		}

		public boolean moved() {
			return this.moved;
		}

		public boolean stopped() {
			return this.stopped;
		}

		public Runnable undo() {
			return this.undo;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof Result)) {
				return false;
			}
			Result other = (Result) obj;
			return java.util.Objects.equals(this.change, other.change)
					&& java.util.Objects.equals(this.before, other.before)
					&& java.util.Objects.equals(this.after, other.after)
					&& this.moved == other.moved
					&& this.stopped == other.stopped
					&& java.util.Objects.equals(this.undo, other.undo);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(change, before, after, moved, stopped, undo);
		}
	}

	private static Phase phase = Phase.NONE;
	private static FpsCoachTips.Tip testTip = null;
	private static Runnable testUndo = null;
	private static Vec3 testStart = null;
	private static boolean testMoved = false;
	private static Stats testBefore = null;
	private static final float[] TEST_DT = new float[CAP];
	private static int testN = 0;
	private static double testMs = 0, settleLeftMs = 0;
	private static long lastNoticeAt = 0;
	private static Result result = null;
	private static boolean resultOpened = false;

	/** Kept for OriginClientMod's call. 1.8.9: ServerHooks drives {@link #tick}
	 *  (ClientTickEvent END) and {@link #frame} (RenderTickEvent END), each wrapped
	 *  so the coach can never take the game down. */
	public static void register() {
	}

	// ---- per frame ----

	/** One rendered frame (Forge RenderTickEvent END, main thread). */
	public static void frame() {
		long now = System.nanoTime();
		lastFrameEventMs = Minecraft.getSystemTime();
		Minecraft mc = Minecraft.getMinecraft();
		boolean test = phase != Phase.NONE;
		if (mc.currentScreen != null || !(test ? recordable : recordWatch)) {
			lastFrameNanos = 0;
			return;
		}
		if (lastFrameNanos != 0) {
			sample(now, (float) Math.min(1000.0, (now - lastFrameNanos) / 1_000_000.0));
		}
		lastFrameNanos = now;
	}

	private static void sample(long nowNanos, float dtMs) {
		if (phase == Phase.NONE) {
			END[head] = nowNanos;
			DT[head] = dtMs;
			head = (head + 1) & MASK;
			if (size < CAP) {
				size++;
			}
		} else if (phase == Phase.SETTLE) {
			settleLeftMs -= dtMs;
		} else {
			if (testN < TEST_DT.length) {
				TEST_DT[testN++] = dtMs;
			}
			testMs += dtMs;
		}
	}

	// ---- per tick ----

	/** Once per client tick (Forge ClientTickEvent END). */
	public static void tick(Minecraft mc) {
		long now = Minecraft.getSystemTime();
		// "Reset Hidden Tips" is a switch that acts like a button: it brings every
		// hidden tip back, then turns itself off again. Works with the mod off.
		if (Mods.bool(ID, "resetHidden")) {
			FpsCoachTips.unhideAll();
			Mods.set(ID, "resetHidden", false);
		}

		WorldClient level = mc.theWorld;
		if (level != lastLevel) {
			if (lastLevel == null) {
				joinedAt = now;
			} else {
				teleportAt = now;   // dimension change (or a server switch)
			}
			if (phase != Phase.NONE) {
				stopTestEarly();
			}
			lastLevel = level;
			lastPos = null;
			size = 0;
			lowSince = 0;
		}
		EntityPlayerSP p = mc.thePlayer;
		if (p == null || level == null) {
			recordWatch = recordable = false;
			keyWasDown = false;
			return;
		}

		Vec3 pos = new Vec3(p.posX, p.posY, p.posZ);
		if (lastPos != null && pos.squareDistanceTo(lastPos) > 32 * 32) {
			teleportAt = now;
		}
		lastPos = pos;
		if (p.hurtTime > 0 || (mc.gameSettings.keyBindAttack.isKeyDown() && mc.objectMouseOver != null
				&& mc.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY)) {
			lastCombatAt = now;
		}
		sampleMemory(now);
		handleKey(mc);

		recordable = mc.currentScreen == null && !mc.isGamePaused() && Display.isActive();
		boolean warm = now - joinedAt >= JOIN_GRACE_MS && now - teleportAt >= TELEPORT_GRACE_MS;
		recordWatch = recordable && Mods.on(ID) && phase == Phase.NONE && warm;

		// Fallback: if per-frame events stopped arriving, feed ~one tick's worth
		// of frames at Minecraft's own FPS count so averages and tests keep going.
		if (recordable && now - lastFrameEventMs > 2000 && (phase != Phase.NONE || recordWatch)) {
			// 1.8.9: the public static Minecraft.getDebugFPS() (last second's count).
			int fps = Math.max(1, Minecraft.getDebugFPS());
			long nanos = System.nanoTime();
			for (int i = 0, n = Math.max(1, Math.round(fps / 20f)); i < n; i++) {
				sample(nanos, 1000f / fps);
			}
		}

		if (phase != Phase.NONE) {
			tickTest(mc, now);
		} else if (now - lastEvalAt >= 1000) {
			lastEvalAt = now;
			evaluate(mc, now);
		}

		if (result != null && !resultOpened && mc.currentScreen == null && !inCombat(now)) {
			resultOpened = true;
			mc.displayGuiScreen(new FpsCoachScreen(null));
		}
	}

	private static void handleKey(Minecraft mc) {
		boolean down = Mods.on(ID) && mc.currentScreen == null && KeyCodes.isDown(Mods.keyCode(ID, "key"));
		if (down && !keyWasDown) {
			mc.displayGuiScreen(new FpsCoachScreen(null));
		}
		keyWasDown = down;
	}

	private static void evaluate(Minecraft mc, long now) {
		if (!recordWatch) {
			lowSince = 0;
			return;
		}
		Stats s = stats(5_000, false);
		if (s.ms() < 2_500) {
			return;   // not enough fresh frames yet
		}
		int target = target();
		if (s.avg() < target - Math.max(2.0, target * 0.05)) {
			if (lowSince == 0) {
				lowSince = now;
			}
		} else {
			lowSince = 0;
		}
		if (lowSince == 0 || now - lowSince < LOW_FOR_MS || !Mods.bool(ID, "showToast")) {
			return;
		}
		long cooldown = (long) (Mods.num(ID, "cooldown") * 60_000);
		if (lastToastAt != 0 && now - lastToastAt < cooldown) {
			return;
		}
		if (inCombat(now) || mc.currentScreen != null) {
			return;   // try again next second
		}
		List<FpsCoachTips.Tip> tips = tips(mc, s.avg());
		if (tips.isEmpty()) {
			return;
		}
		FpsCoachToast.show(mc, tips.size(), keyName());
		lastToastAt = now;
	}

	private static void sampleMemory(long now) {
		Runtime rt = Runtime.getRuntime();
		long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
		memMinThisSecond = Math.min(memMinThisSecond, used);
		if (now - memSecondAt >= 1000) {
			memSecondAt = now;
			MEM_FLOOR[memSlot] = memMinThisSecond;
			memSlot = (memSlot + 1) % MEM_FLOOR.length;
			memMinThisSecond = Long.MAX_VALUE;
		}
	}

	/** Lowest heap use over the last ~20 s: what's left after garbage collection. */
	private static long memFloorMb() {
		long min = Long.MAX_VALUE;
		for (long v : MEM_FLOOR) {
			if (v == 0) {
				return 0;   // not 20 s of samples yet
			}
			min = Math.min(min, v);
		}
		return min;
	}

	private static boolean inCombat(long now) {
		return lastCombatAt != 0 && now - lastCombatAt < COMBAT_MS;
	}

	// ---- stats ----

	private static Stats stats(long windowMs, boolean withLow) {
		if (size == 0) {
			return Stats.EMPTY;
		}
		long from = END[(head - 1) & MASK] - windowMs * 1_000_000L;
		int n = 0;
		double ms = 0;
		for (int i = 0; i < size; i++) {
			int idx = (head - 1 - i) & MASK;
			if (END[idx] < from) {
				break;
			}
			ms += DT[idx];
			n++;
		}
		if (n == 0 || ms <= 0) {
			return Stats.EMPTY;
		}
		double low = 0;
		if (withLow) {
			float[] copy = new float[n];
			for (int i = 0; i < n; i++) {
				copy[i] = DT[(head - 1 - i) & MASK];
			}
			low = onePercentLow(copy, n);
		}
		return new Stats(n, ms, n * 1000.0 / ms, low);
	}

	/** FPS of the slowest 1% of frames (their average frame time). */
	private static double onePercentLow(float[] dts, int n) {
		Arrays.sort(dts, 0, n);
		int k = Math.max(1, n / 100);
		double sum = 0;
		for (int i = n - k; i < n; i++) {
			sum += dts[i];
		}
		return sum <= 0 ? 0 : k * 1000.0 / sum;
	}

	private static Stats testStats() {
		if (testN == 0 || testMs <= 0) {
			return Stats.EMPTY;
		}
		double ms = 0;
		for (int i = 0; i < testN; i++) {
			ms += TEST_DT[i];
		}
		double low = onePercentLow(Arrays.copyOf(TEST_DT, testN), testN);
		return new Stats(testN, ms, testN * 1000.0 / ms, low);
	}

	// ---- the "Try it" test ----

	private static void tickTest(Minecraft mc, long now) {
		if (testStart != null && new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)
				.squareDistanceTo(testStart) > 12 * 12) {
			testMoved = true;
		}
		switch (phase) {
			case BEFORE: {
				if (testMs >= BEFORE_MS) {
					testBefore = testStats();
					testUndo = testTip.apply().apply(mc);
					if (testUndo == null) {
						phase = Phase.NONE;
						notice(mc, "FPS Coach: that setting is already changed");
						return;
					}
					phase = Phase.SETTLE;
					settleLeftMs = testTip.settleMs();
					resetTestBuffer();
				}
				break;
			}
			case SETTLE: {
				if (settleLeftMs <= 0) {
					phase = Phase.AFTER;
					resetTestBuffer();
				}
				break;
			}
			case AFTER: {
				if (testMs >= AFTER_MS) {
					finish(new Result(testTip.change(), testBefore, testStats(), testMoved, false, testUndo));
					return;
				}
				break;
			}
			default: {
				break;
			}
		}
		if (now - lastNoticeAt >= 1000) {
			lastNoticeAt = now;
			String what = phase == Phase.BEFORE ? "measuring your FPS" : "trying " + testTip.change();
			notice(mc, "FPS Coach: " + what + " -- stay where you are (" + secondsLeft() + "s)");
		}
	}

	private static void resetTestBuffer() {
		testN = 0;
		testMs = 0;
		lastFrameNanos = 0;
	}

	private static void finish(Result r) {
		phase = Phase.NONE;
		testTip = null;
		testUndo = null;
		result = r;
		resultOpened = false;
		lastToastAt = Minecraft.getSystemTime();   // the player is engaged; no toast right after
		lowSince = 0;
		size = 0;                         // old frames were from the old settings
	}

	private static void stopTestEarly() {
		if (testUndo != null) {
			finish(new Result(testTip.change(), testBefore, null, testMoved, true, testUndo));
		} else {
			phase = Phase.NONE;
			testTip = null;
		}
	}

	// 1.8.9: the action bar is GuiIngame.setRecordPlaying(component, false).
	private static void notice(Minecraft mc, String text) {
		if (mc.thePlayer != null && mc.ingameGUI != null) {
			mc.ingameGUI.setRecordPlaying(new ChatComponentText(text), false);
		}
	}

	// ---- API for the screen ----

	public static int target() {
		return (int) Math.round(Mods.num(ID, "targetFps"));
	}

	/** The last ~10 s of counted play: average FPS and 1% low. */
	public static Stats recent() {
		return stats(10_000, true);
	}

	public static List<FpsCoachTips.Tip> tips(Minecraft mc) {
		return tips(mc, recent().avg());
	}

	private static List<FpsCoachTips.Tip> tips(Minecraft mc, double avg) {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory() == Long.MAX_VALUE ? 0 : rt.maxMemory() / (1024 * 1024);
		List<FpsCoachTips.Tip> all = FpsCoachTips.build(mc, avg, target(), memFloorMb(), max);
		return all.size() > MAX_TIPS ? all.subList(0, MAX_TIPS) : all;
	}

	public static boolean testing() {
		return phase != Phase.NONE;
	}

	public static String testingChange() {
		return testTip != null ? testTip.change() : "";
	}

	public static int secondsLeft() {
		double left;
		switch (phase) {
			case BEFORE:
				left = (BEFORE_MS - testMs) + testTip.settleMs() + AFTER_MS;
				break;
			case SETTLE:
				left = Math.max(0, settleLeftMs) + AFTER_MS;
				break;
			case AFTER:
				left = AFTER_MS - testMs;
				break;
			default:
				left = 0;
		}
		return (int) Math.ceil(Math.max(0, left) / 1000.0);
	}

	/** Starts a test of one tip. The caller closes its screen first. */
	public static void startTest(FpsCoachTips.Tip tip) {
		Minecraft mc = Minecraft.getMinecraft();
		if (tip.apply() == null || phase != Phase.NONE || mc.thePlayer == null) {
			return;
		}
		testTip = tip;
		testUndo = null;
		testBefore = null;
		testStart = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
		testMoved = false;
		result = null;
		resetTestBuffer();
		phase = Phase.BEFORE;
		lastNoticeAt = 0;
	}

	/** Stops a running test and puts the setting back if it was already changed. */
	public static void stopTest() {
		if (testUndo != null) {
			// Stop is a click in FpsCoachScreen, drained mid-frame; the undo can switch OptiFine shaders
			// (setShaderPack + uninit) or reload chunks, so it runs at the next client tick instead.
			com.origin.client.client.gui.OriginScreen.outsideFrame(testUndo);
		}
		phase = Phase.NONE;
		testTip = null;
		testUndo = null;
		size = 0;
		notice(Minecraft.getMinecraft(), "FPS Coach: test stopped, nothing changed");
	}

	public static Result result() {
		return result;
	}

	public static void keep() {
		result = null;
	}

	public static void undo() {
		Result r = result;
		result = null;
		if (r != null && r.undo() != null) {
			com.origin.client.client.gui.OriginScreen.outsideFrame(r.undo());   // Undo click: see stopTest
			size = 0;
			notice(Minecraft.getMinecraft(), "FPS Coach: put back the way it was");
		}
	}

	/** The open-coach key's name, or null when it is unbound.
	 *  1.8.9: an LWJGL2 code (mouse buttons are -100 + n), named the vanilla way. */
	public static String keyName() {
		int code = Mods.keyCode(ID, "key");
		if (code == KeyCodes.UNBOUND || code == 0) {
			return null;
		}
		try {
			return KeyCodes.name(code);
		} catch (Throwable t) {
			return null;
		}
	}
}
