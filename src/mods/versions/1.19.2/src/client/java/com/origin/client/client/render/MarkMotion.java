package com.origin.client.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.math.Matrix4f;

/**
 * The Origin mark as three live strokes (Will 2026-10-18: "morphing shapes and
 * motion graphics", then "the origin ring pushing itself to its place" without
 * "fading out and fading in the new logo").
 *
 * <ul>
 *   <li>Loading screen ({@link #drawLoading}): a seed blooms into three nested
 *       circles, which keep morphing — circles, alternating triangles, a square
 *       and diamond, the Origin mark — turning together as they go, with one dot
 *       riding the outer stroke.</li>
 *   <li>Loading done -> title ({@link #drawFlight}): from wherever the loop was,
 *       each stroke morphs into its ring of the mark while it travels and shrinks
 *       onto the title's logo spot, the outer ring first.</li>
 *   <li>Title at rest ({@link #drawFormed}): the same strokes, formed. The flight's
 *       last frame IS this picture, so nothing is ever swapped.</li>
 * </ul>
 *
 * A stroke's shape is a blend of three closed curves (circle, rounded triangle,
 * rounded square) by radius, so any two states morph into each other and the
 * curve always stays closed. Drawn as anti-aliased quad strips (a one-pixel
 * feathered edge plus a soft glow), crisp at any size. Render thread only.
 */
final class MarkMotion {
	// State layout: shape weights (circle, triangle, square), then radius (of the
	// mark size), y aspect, rotation (deg), alpha.
	private static final int W_CIRCLE = 0, W_TRI = 1, W_SQUARE = 2, RADIUS = 3, ASPECT = 4, ROT = 5, ALPHA = 6, N = 7;

	/** The brand mark (OriginMark.xaml: ellipses 180x64, 150x54, 120x44 of 200, at 0/60/120 degrees). */
	private static final double[][] MARK = {
			{1, 0, 0, 0.450, 64 / 180.0, 0, 1},
			{1, 0, 0, 0.375, 54 / 150.0, 60, 1},
			{1, 0, 0, 0.300, 44 / 120.0, 120, 1},
	};
	private static final double[][] SEED = {
			{1, 0, 0, 0.02, 1, 90, 0},
			{1, 0, 0, 0.02, 1, 30, 0},
			{1, 0, 0, 0.02, 1, 90, 0},
	};
	private static final double[][] CIRCLES = {
			{1, 0, 0, 0.46, 1, 90, 1},
			{1, 0, 0, 0.34, 1, 30, 0.9},
			{1, 0, 0, 0.22, 1, 90, 0.8},
	};
	// Rotations put the triangles' corners up / down / up (corner at 60 deg + rot).
	private static final double[][] TRIANGLES = {
			{0, 1, 0, 0.47, 1, 90, 1},
			{0, 1, 0, 0.35, 1, 30, 0.9},
			{0, 1, 0, 0.23, 1, 90, 0.8},
	};
	private static final double[][] SQUARES = {
			{0, 0, 1, 0.42, 1, 90, 1},
			{0, 0, 1, 0.33, 1, 135, 0.9},
			{0, 0, 1, 0.22, 1, 90, 0.8},
	};
	private static final double[][][] LOOP = {CIRCLES, TRIANGLES, SQUARES, MARK};

	/** Seed -> circles. */
	private static final double INTRO_MS = 900;
	/** Each loop step: hold the shape, then morph to the next. */
	private static final double HOLD_MS = 480, MORPH_MS = 720;
	/** How much later each inner stroke starts the same move. */
	private static final double STAGGER_MS = 90;
	/** Every morph also turns the whole mark this much (degrees), plus a slow drift. */
	private static final double SPIN_PER_STEP = 60, DRIFT_DEG_PER_S = 4;
	/** Loading -> title flight, and its per-ring stagger. */
	static final double FLIGHT_MS = 950;
	private static final double FLIGHT_STAGGER_MS = 70;
	/** Curve samples per stroke. */
	private static final int SAMPLES = 128;
	private static final int RGB = 0xF2F2F2;

	// The loading mark's last frame, where the flight leaves from (snapCx < 0 = none).
	private static final double[][] snap = new double[3][N];
	private static double snapCx = -1, snapCy, snapSize, snapT;
	// RingField.sceneMs() of the flight's first frame; -1 = no flight this game.
	private static double flightAt = -1;

	private MarkMotion() {
	}

	// ---- Loading ----

	/** The loading loop at {@code t} ms since the loading screen first drew. */
	static void drawLoading(Gfx g, double cx, double cy, double size, double t, double alpha) {
		double[][] s;
		double dotT;
		if (settleT >= 0 && t > settleT) {
			double p = settleProgress(t);
			s = new double[3][];
			for (int k = 0; k < 3; k++) {
				s[k] = lerp(settleFrom[k], settleTo[k], p);
			}
			// The dot slows with the same curve (its speed at p = 0 matches the loop's) and fades.
			dotT = settleT + DOT_GLIDE_MS * p;
		} else {
			s = loadingState(t, t);
			dotT = t;
		}
		for (int k = 0; k < 3; k++) {
			System.arraycopy(s[k], 0, snap[k], 0, N);
		}
		snapCx = cx;
		snapCy = cy;
		snapSize = size;
		snapT = dotT;
		snapDot = dotAlpha(t);
		draw(g, cx, cy, size, s, alpha, dotAngle(dotT), snapDot);
	}

	// ---- Settle: the calm before the menu ----
	// The last stretch of the startup load runs every reload listener's apply step
	// on the render thread (atlas uploads, model bake, shaders, sound engine), and
	// with mods installed single steps take hundreds of ms. A frame that long is
	// invisible only if nothing is moving. So once the load reaches that stretch
	// (OriginScreenRenderer: reload work has been QUEUED for the render thread),
	// the loop glides in SETTLE_MS to the nearest clean shape of the loop (the
	// shape it is holding, or whichever end of the morph in progress is closer:
	// circles, triangles, squares or the mark, always concentric) and stops
	// there, the riding dot slowing and fading with it. Meanwhile
	// ReloadTaskBudgetMixin runs no queued render-thread task until atRest(), so
	// no long step can freeze the mark mid-motion or on an in-between shape (the
	// with-mods film, 2026-10-19: frozen ~8 s on a blob after a decay settle).
	// From then on only the bar moves (a bar that pauses reads as loading), the
	// main menu's settle hold shows this same still picture, and the flight to
	// the title starts from it. (Ported verbatim from 1.21.1 8f5c7ff3, 2026-10-19.)

	/** Loop time (ms) the settle was asked for; -1 = looping. */
	private static double settleT = -1;
	/** The glide from the shape at settleT to the clean rest shape. */
	static final double SETTLE_MS = 280;
	/** How far the dot still travels (in loop ms) while it slows: matches its speed at the start of an ease-out cubic. */
	private static final double DOT_GLIDE_MS = SETTLE_MS / 3.0;
	/** The three strokes when the settle began, and their clean rest pose. */
	private static final double[][] settleFrom = new double[3][], settleTo = new double[3][];
	// The dot's alpha in the last loading frame (the flight's dot starts from it).
	private static double snapDot = 1.0;

	/** Ask the loop to come to rest on its nearest clean shape (idempotent). */
	static void settle(double t) {
		if (settleT >= 0) {
			return;
		}
		settleT = t;
		double[][] now = loadingState(t, t);
		double intro = INTRO_MS + 2 * STAGGER_MS;
		double drift = t / 1000.0 * DRIFT_DEG_PER_S;
		if (t < intro) {
			// Still blooming from the seed: rest on the circles it is becoming.
			for (int k = 0; k < 3; k++) {
				settleFrom[k] = now[k];
				settleTo[k] = CIRCLES[k].clone();
				settleTo[k][ROT] += drift;
			}
			return;
		}
		double stepMs = HOLD_MS + MORPH_MS;
		long step = (long) Math.floor((t - intro) / stepMs);
		double within = (t - intro) - step * stepMs;
		double[] e = new double[3];
		double mean = 0;
		for (int k = 0; k < 3; k++) {
			e[k] = inOut((within - HOLD_MS - k * STAGGER_MS) / (MORPH_MS - 2 * STAGGER_MS));
			mean += e[k] / 3.0;
		}
		// The nearer end of the morph in progress (holding a shape: that shape, mean 0).
		long rest = mean < 0.5 ? step : step + 1;
		double[][] from = LOOP[(int) (step % LOOP.length)], to = LOOP[(int) ((step + 1) % LOOP.length)];
		double[][] shape = LOOP[(int) (rest % LOOP.length)];
		for (int k = 0; k < 3; k++) {
			settleFrom[k] = now[k];
			settleTo[k] = shape[k].clone();
			// Rotation continuous with the current one (no wrap at 360): exactly the
			// turn the loop itself gives that shape (its own angle + rest steps of
			// spin + the drift), measured from where this stroke is now.
			double base = from[k][ROT] + (to[k][ROT] - from[k][ROT]) * e[k];
			double want = now[k][ROT] + (shape[k][ROT] - base) + (rest - (step + e[k])) * SPIN_PER_STEP;
			// The same picture repeats every `period` degrees (circle: any angle,
			// square 90, triangle 120, the mark's ellipses 180): turn the short way.
			double period = symmetry(shape[k]);
			settleTo[k][ROT] = period <= 0 ? now[k][ROT]
					: want - period * Math.round((want - now[k][ROT]) / period);
		}
	}

	/** Rotational symmetry of a pure loop shape in degrees (0 = a circle: every angle looks the same). */
	private static double symmetry(double[] s) {
		if (s[W_TRI] >= 1.0) {
			return 120.0;
		}
		if (s[W_SQUARE] >= 1.0) {
			return 90.0;
		}
		return s[ASPECT] == 1.0 ? 0.0 : 180.0;   // a circle, or one of the mark's ellipses
	}

	/** 0..1 along the settle glide (ease-out cubic: leaves at speed, lands at rest). */
	private static double settleProgress(double t) {
		double u = Math.max(0.0, Math.min(1.0, (t - settleT) / SETTLE_MS));
		double v = 1.0 - u;
		return 1.0 - v * v * v;
	}

	/** True once a settle was asked for (until {@link #unsettle}). */
	static boolean settling() {
		return settleT >= 0;
	}

	/** A new reload began (a resource-pack change after startup): loop again. */
	static void unsettle() {
		settleT = -1;
	}

	/** True once the strokes have landed on their clean pose and the dot is gone: nothing moves but the bar. */
	static boolean atRest(double t) {
		return settleT >= 0 && t >= settleT + SETTLE_MS;
	}

	private static double dotAlpha(double t) {
		return settleT < 0 || t <= settleT ? 1.0 : 1.0 - smooth((t - settleT) / SETTLE_MS);
	}

	/** The loop's shapes at {@code t}, with its slow turn read at {@code driftT}. */
	private static double[][] loadingState(double t, double driftT) {
		double[][] out = new double[3][];
		double drift = driftT / 1000.0 * DRIFT_DEG_PER_S;
		if (t < INTRO_MS + 2 * STAGGER_MS) {
			for (int k = 0; k < 3; k++) {
				out[k] = lerp(SEED[k], CIRCLES[k], inOut((t - k * STAGGER_MS) / INTRO_MS));
				out[k][ROT] += drift;
			}
			return out;
		}
		double u = t - (INTRO_MS + 2 * STAGGER_MS);
		double stepMs = HOLD_MS + MORPH_MS;
		long step = (long) Math.floor(u / stepMs);
		double within = u - step * stepMs;
		double[][] from = LOOP[(int) (step % LOOP.length)], to = LOOP[(int) ((step + 1) % LOOP.length)];
		for (int k = 0; k < 3; k++) {
			double e = inOut((within - HOLD_MS - k * STAGGER_MS) / (MORPH_MS - 2 * STAGGER_MS));
			out[k] = lerp(from[k], to[k], e);
			out[k][ROT] += ((step + e) * SPIN_PER_STEP + drift) % 360.0;
		}
		return out;
	}

	/** The dot's place on the outer stroke (radians): one lap every 1.8 s. */
	private static double dotAngle(double t) {
		return (t / 1800.0 % 1.0) * 2 * Math.PI;
	}

	// ---- Flight ----

	/** First frame of the loading -> title dissolve: the mark sets off from its last loading frame. */
	static void startFlight() {
		if (flightAt < 0 && snapCx >= 0) {
			flightAt = RingField.sceneMs();
		}
	}

	/** True once the startup flight began (the title then leaves its mark to the flight). */
	static boolean flightStarted() {
		return flightAt >= 0;
	}

	/** True while the mark is still on its way. */
	static boolean flying() {
		return flightAt >= 0 && RingField.sceneMs() - flightAt < FLIGHT_MS;
	}

	/** The flying mark, landing on (tx, ty) at {@code tsize}. */
	static void drawFlight(Gfx g, double tx, double ty, double tsize) {
		double t = RingField.sceneMs() - flightAt;
		double[][] s = new double[3][];
		double[] cx = new double[3], cy = new double[3], size = new double[3];
		double dotFade = (1.0 - smooth(t / (FLIGHT_MS * 0.3))) * snapDot;   // gone already if the mark settled
		for (int k = 0; k < 3; k++) {
			double e = inOut((t - k * FLIGHT_STAGGER_MS) / (FLIGHT_MS - 2 * FLIGHT_STAGGER_MS));
			double[] from = snap[k].clone();
			// The mark's ellipses repeat every 180 degrees: take the short way round.
			double d = (((MARK[k][ROT] - from[ROT]) % 180.0) + 180.0) % 180.0;
			from[ROT] = MARK[k][ROT] - (d > 90.0 ? d - 180.0 : d);
			s[k] = lerp(from, MARK[k], e);
			cx[k] = lerp(snapCx, tx, e);
			cy[k] = lerp(snapCy, ty, e);
			size[k] = snapSize * Math.pow(tsize / snapSize, e);   // an even shrink at every size
		}
		Ctx c = begin(g);
		for (int k = 0; k < 3; k++) {
			stroke(c, cx[k], cy[k], size[k], s[k], 1.0);
		}
		if (dotFade > 0.0) {
			dot(c, cx[0], cy[0], size[0], s[0], dotAngle(snapT + t), dotFade);
		}
		end(c);
	}

	// ---- At rest ----

	/** The formed mark centred on (cx, cy), {@code size} wide. */
	static void drawFormed(Gfx g, double cx, double cy, double size, double alpha) {
		draw(g, cx, cy, size, MARK, alpha, Double.NaN, 0.0);
	}

	// ---- Drawing ----

	// Pre-1.20 there is no Gfx batch: every stroke of one draw goes into a
	// single Tesselator POSITION_COLOR quad buffer (the same shader GuiComponent.fill
	// uses) and is drawn at end(). The buffer begins lazily on the first vertex, so a
	// fully faded mark never begins a buffer it would not draw.
	private static final class Ctx {
		final Matrix4f m;
		final double px;
		BufferBuilder bb;

		Ctx(Matrix4f m, double px) {
			this.m = m;
			this.px = px;
		}
	}

	private static Ctx begin(Gfx g) {
		// One real pixel in GUI units: the feathered edge is a pixel wide at any GUI scale.
		double px = 1.0 / Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		return new Ctx(g.pose().last().pose(), px);
	}

	private static void end(Ctx c) {
		if (c.bb == null) {
			return;
		}
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShader(GameRenderer::getPositionColorShader);
		// Alpha lives in the vertex colours; the shader's colour must be neutral when the quads draw.
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		BufferUploader.drawWithShader(c.bb.end());
		c.bb = null;
	}

	private static void draw(Gfx g, double cx, double cy, double size, double[][] s, double alpha,
							 double dot, double dotAlpha) {
		Ctx c = begin(g);
		for (int k = 0; k < 3; k++) {
			stroke(c, cx, cy, size, s[k], alpha);
		}
		if (!Double.isNaN(dot)) {
			dot(c, cx, cy, size, s[0], dot, dotAlpha * alpha);
		}
		end(c);
	}

	/** The stroke's radius at angle th, 1 = its nominal radius. */
	private static double shape(double[] s, double th) {
		double sum = s[W_CIRCLE] + s[W_TRI] + s[W_SQUARE];
		double r = s[W_CIRCLE];
		if (s[W_TRI] > 0) {
			r += s[W_TRI] * polygon(th, 3, 6.0);
		}
		if (s[W_SQUARE] > 0) {
			r += s[W_SQUARE] * polygon(th, 4, 4.5);
		}
		return sum > 0 ? r / sum : 1.0;
	}

	/**
	 * A rounded regular polygon as a closed radius curve: the superformula with
	 * m = sides, n1 = n2 = n3 = n (n = 2 is a circle; larger is sharper), scaled
	 * so its corners reach 1. Corners sit at 180/m degrees (60 for a triangle,
	 * 45 for a square), plus every 360/m from there.
	 */
	private static double polygon(double th, int m, double n) {
		double a = m * th / 4.0;
		double r = Math.pow(Math.pow(Math.abs(Math.cos(a)), n) + Math.pow(Math.abs(Math.sin(a)), n), -1.0 / n);
		return r / (Math.sqrt(2.0) * Math.pow(2.0, -1.0 / n));
	}

	private static void point(double[] s, double cx, double cy, double size, double th, double[] out) {
		double r = s[RADIUS] * size * shape(s, th);
		double x = r * Math.cos(th), y = r * Math.sin(th) * s[ASPECT];
		double rot = Math.toRadians(s[ROT]);
		double cr = Math.cos(rot), sr = Math.sin(rot);
		out[0] = cx + x * cr - y * sr;
		out[1] = cy + x * sr + y * cr;
	}

	private static void stroke(Ctx c, double cx, double cy, double size, double[] s, double alphaMul) {
		double a = clamp01(s[ALPHA] * alphaMul);
		if (a <= 0.004 || s[RADIUS] * size < 0.05) {
			return;
		}
		double[][] p = new double[SAMPLES][2];
		for (int i = 0; i < SAMPLES; i++) {
			point(s, cx, cy, size, i * 2 * Math.PI / SAMPLES, p[i]);
		}
		double[][] nrm = new double[SAMPLES][2];
		for (int i = 0; i < SAMPLES; i++) {
			double[] prev = p[(i + SAMPLES - 1) % SAMPLES], next = p[(i + 1) % SAMPLES];
			double tx = next[0] - prev[0], ty = next[1] - prev[1];
			double len = Math.hypot(tx, ty);
			nrm[i][0] = len > 1e-9 ? -ty / len : 0;
			nrm[i][1] = len > 1e-9 ? tx / len : 0;
		}
		// The brand's 3/200 stroke, never thinner than about 1.5 real pixels.
		double hw = Math.max(size * 0.0075, 0.75 * c.px), f = c.px;
		double glow = Math.max(size * 0.05, 3 * c.px);
		int core = argb(a), clear = argb(0), halo = argb(a * 0.11);
		for (int i = 0; i < SAMPLES; i++) {
			int j = (i + 1) % SAMPLES;
			double[] pi = p[i], pj = p[j], ni = nrm[i], nj = nrm[j];
			band(c, pi, pj, ni, nj, 0, glow, halo, clear);
			band(c, pi, pj, ni, nj, 0, -glow, halo, clear);
			band(c, pi, pj, ni, nj, -hw, hw, core, core);
			band(c, pi, pj, ni, nj, hw, hw + f, core, clear);
			band(c, pi, pj, ni, nj, -hw, -hw - f, core, clear);
		}
	}

	/** The orbiting dot on stroke s at angle th. */
	private static void dot(Ctx c, double cx, double cy, double size, double[] s, double th, double alpha) {
		double a = clamp01(alpha * s[ALPHA]);
		if (a <= 0.004) {
			return;
		}
		double[] o = new double[2];
		point(s, cx, cy, size, th, o);
		double r = Math.max(size * 0.022, 1.5 * c.px);
		double glow = r * 3.0;
		int core = argb(a), clear = argb(0), halo = argb(a * 0.22);
		int seg = 24;
		for (int i = 0; i < seg; i++) {
			double a0 = i * 2 * Math.PI / seg, a1 = (i + 1) * 2 * Math.PI / seg;
			double[] u0 = {Math.cos(a0), Math.sin(a0)}, u1 = {Math.cos(a1), Math.sin(a1)};
			band(c, o, o, u0, u1, 0, r, core, core);
			band(c, o, o, u0, u1, r, r + c.px, core, clear);
			band(c, o, o, u0, u1, r, glow, halo, clear);
		}
	}

	/** The quad between offsets d0 and d1 along the normals of segment pi -> pj, coloured c0 at d0, c1 at d1. */
	private static void band(Ctx c, double[] pi, double[] pj, double[] ni, double[] nj, double d0, double d1, int c0, int c1) {
		double ax = pi[0] + ni[0] * d0, ay = pi[1] + ni[1] * d0;
		double bx = pj[0] + nj[0] * d0, by = pj[1] + nj[1] * d0;
		double cxx = pj[0] + nj[0] * d1, cyy = pj[1] + nj[1] * d1;
		double dx = pi[0] + ni[0] * d1, dy = pi[1] + ni[1] * d1;
		// The GUI may cull back faces: wind every quad like GuiComponent.fill
		// (negative shoelace area with y down), whichever way the curve runs.
		double area = (ax * by - bx * ay) + (bx * cyy - cxx * by) + (cxx * dy - dx * cyy) + (dx * ay - ax * dy);
		if (area <= 0) {
			vertex(c, ax, ay, c0);
			vertex(c, bx, by, c0);
			vertex(c, cxx, cyy, c1);
			vertex(c, dx, dy, c1);
		} else {
			vertex(c, dx, dy, c1);
			vertex(c, cxx, cyy, c1);
			vertex(c, bx, by, c0);
			vertex(c, ax, ay, c0);
		}
	}

	private static void vertex(Ctx c, double x, double y, int argb) {
		if (c.bb == null) {
			c.bb = Tesselator.getInstance().getBuilder();
			c.bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		}
		c.bb.vertex(c.m, (float) x, (float) y, 0f)
				.color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF).endVertex();
	}

	private static int argb(double alpha) {
		return ((int) Math.round(clamp01(alpha) * 255.0) << 24) | RGB;
	}

	// ---- Maths ----

	private static double[] lerp(double[] a, double[] b, double t) {
		double[] o = new double[N];
		for (int i = 0; i < N; i++) {
			o[i] = a[i] + (b[i] - a[i]) * t;
		}
		return o;
	}

	private static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Cubic in-out on 0..1 (clamped): the travel curve (docs/MOTION.md). */
	private static double inOut(double t) {
		t = clamp01(t);
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	private static double smooth(double t) {
		t = clamp01(t);
		return t * t * (3 - 2 * t);
	}

	private static double clamp01(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}
}
