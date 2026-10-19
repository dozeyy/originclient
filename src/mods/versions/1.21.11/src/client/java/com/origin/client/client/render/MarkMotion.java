package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;

import java.util.Arrays;

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
 *
 * PER-VERSION (1.21.9+ deferred GUI): GuiGraphics only records render states
 * here, so 1.21.1's immediate quads (bufferSource + flush + setShaderColor) are
 * replaced by ONE GuiElementRenderState per draw, the same shape as this
 * module's GuiTris: buildVertices(consumer) with addVertexWith2DPose(pose, x, y),
 * no texture, bounds from the transformed extent clipped to the scissor. It
 * draws through vanilla's RenderPipelines.GUI, which culls back faces, so
 * band() keeps 1.21.1's fill()-order winding (GuiTris uses the same rule).
 * Alpha lives in the vertex colours. Layering follows submission order.
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
	static void drawLoading(GuiGraphics g, double cx, double cy, double size, double t, double alpha) {
		double lt = loopTime(t);
		double[][] s = settleT >= 0 ? loadingState(lt, settledDrift(t)) : loadingState(t, t);
		for (int k = 0; k < 3; k++) {
			System.arraycopy(s[k], 0, snap[k], 0, N);
		}
		snapCx = cx;
		snapCy = cy;
		snapSize = size;
		snapT = lt;
		snapDot = dotAlpha(t);
		draw(g, cx, cy, size, s, alpha, dotAngle(lt), snapDot);
	}

	// ---- Settle: the calm before the menu ----
	// The last stretch of the startup load runs every reload listener's apply step
	// on the render thread (atlas uploads, model bake, shaders, sound engine), and
	// with mods installed single steps take hundreds of ms. A frame that long is
	// invisible only if nothing is moving. So once the load reaches that stretch
	// (OriginScreenRenderer: the first reload work on the render thread), the
	// loop comes to rest fast: holding a shape, it stays; near the end of a morph,
	// the morph lands (its ease-out already ends at rest); otherwise the whole
	// loop decelerates to a stop in ~1/3 s (an exponential ease on its clock, so
	// it loses speed smoothly instead of stopping dead). The slow turn eases out
	// too and the riding dot fades. From then on only the bar moves (a bar that
	// pauses reads as loading), the main menu's settle hold shows this same still
	// picture, and the flight to the title starts from it. Why fast and not
	// "finish the morph": the first heavy steps (fonts, models, Shader Loader;
	// with Will's mods cobblemon:atlases at 4.7 s on CI) start within ~100 ms of
	// the trigger, and a full morph takes up to 720 ms.

	/** Loop time (ms) the settle was asked for; -1 = looping. */
	private static double settleT = -1;
	/** Loop time the strokes come to rest when landing a morph (decel = false). */
	private static double restT;
	/** True: the loop decelerates instead of landing its morph. */
	private static boolean decel;
	/** A morph with at most this much left lands; more than this decelerates. */
	private static final double LAND_MS = 250;
	/** The loop clock's deceleration time constant; at rest after REST_TAUS of them. */
	private static final double DECEL_TAU_MS = 110, REST_TAUS = 4;
	/** How quickly the slow turn eases out after a settle, and how long the dot takes to fade. */
	private static final double DRIFT_TAU_MS = 400, DOT_FADE_MS = 360;
	// The dot's alpha in the last loading frame (the flight's dot starts from it).
	private static double snapDot = 1.0;

	/** Ask the loop to come to rest (idempotent). */
	static void settle(double t) {
		if (settleT >= 0) {
			return;
		}
		settleT = t;
		double intro = INTRO_MS + 2 * STAGGER_MS;
		double land;
		if (t < intro) {
			land = intro;   // the seed blooming into circles
		} else {
			double stepMs = HOLD_MS + MORPH_MS;
			double step = Math.floor((t - intro) / stepMs);
			double within = (t - intro) - step * stepMs;
			// Holding a shape: rest right there. Mid-morph: the step's end (every
			// stroke's morph, stagger included, has landed by then).
			land = within < HOLD_MS ? t : intro + (step + 1) * stepMs;
		}
		decel = land - t > LAND_MS;
		restT = land;
	}

	/** The loop's own clock: real loop time, or after a settle the landing/decelerating one. */
	private static double loopTime(double t) {
		if (settleT < 0 || t <= settleT) {
			return t;
		}
		return decel ? settleT + DECEL_TAU_MS * (1.0 - Math.exp(-(t - settleT) / DECEL_TAU_MS)) : Math.min(t, restT);
	}

	/** True once a settle was asked for (until {@link #unsettle}). */
	static boolean settling() {
		return settleT >= 0;
	}

	/** A new reload began (a resource-pack change after startup): loop again. */
	static void unsettle() {
		settleT = -1;
	}

	/** True once the strokes have landed and the dot is gone: nothing on the loading screen moves but the bar. */
	static boolean atRest(double t) {
		if (settleT < 0) {
			return false;
		}
		double stopped = decel ? settleT + REST_TAUS * DECEL_TAU_MS : restT;
		return t >= Math.max(stopped, settleT + DOT_FADE_MS);
	}

	/** The drift's clock after a settle: it eases out instead of stopping dead. */
	private static double settledDrift(double t) {
		return t <= settleT ? t : settleT + DRIFT_TAU_MS * (1.0 - Math.exp(-(t - settleT) / DRIFT_TAU_MS));
	}

	private static double dotAlpha(double t) {
		return settleT < 0 || t <= settleT ? 1.0 : 1.0 - smooth((t - settleT) / DOT_FADE_MS);
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
	static void drawFlight(GuiGraphics g, double tx, double ty, double tsize) {
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
		end(g, c);
	}

	// ---- At rest ----

	/** The formed mark centred on (cx, cy), {@code size} wide. */
	static void drawFormed(GuiGraphics g, double cx, double cy, double size, double alpha) {
		draw(g, cx, cy, size, MARK, alpha, Double.NaN, 0.0);
	}

	// ---- Drawing ----

	/** One draw's quads (x, y per corner) and corner colours, submitted as one render state. */
	private static final class Ctx {
		final double px;
		float[] xy = new float[2 * 4 * 1024];
		int[] argb = new int[4 * 1024];
		int n = 0;   // corners
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;

		Ctx(double px) {
			this.px = px;
		}

		void put(double x, double y, int c) {
			if (n == argb.length) {
				argb = Arrays.copyOf(argb, n * 2);
				xy = Arrays.copyOf(xy, n * 4);
			}
			float fx = (float) x, fy = (float) y;
			xy[2 * n] = fx;
			xy[2 * n + 1] = fy;
			argb[n++] = c;
			minX = Math.min(minX, fx);
			minY = Math.min(minY, fy);
			maxX = Math.max(maxX, fx);
			maxY = Math.max(maxY, fy);
		}
	}

	private static Ctx begin(GuiGraphics g) {
		// One real pixel in GUI units: the feathered edge is a pixel wide at any GUI scale.
		double px = 1.0 / Math.max(1.0, Minecraft.getInstance().getWindow().getGuiScale());
		return new Ctx(px);
	}

	/** Records the collected quads into the frame, under g's current pose and scissor. */
	private static void end(GuiGraphics g, Ctx c) {
		if (c.n < 4) {
			return;
		}
		Matrix3x2f pose = new Matrix3x2f(g.pose());
		ScreenRectangle scissor = g.scissorStack.peek();
		int x0 = (int) Math.floor(c.minX), y0 = (int) Math.floor(c.minY);
		ScreenRectangle local = new ScreenRectangle(x0, y0,
				Math.max(1, (int) Math.ceil(c.maxX) - x0), Math.max(1, (int) Math.ceil(c.maxY) - y0)).transformMaxBounds(pose);
		ScreenRectangle bounds = scissor != null ? scissor.intersection(local) : local;
		if (bounds == null) {
			return;   // wholly scissored away
		}
		int n = c.n - c.n % 4;
		g.guiRenderState.submitGuiElement(new MarkRenderState(PIPELINE, TextureSetup.noTexture(), pose,
				Arrays.copyOf(c.xy, 2 * n), Arrays.copyOf(c.argb, n), scissor, bounds));
	}

	// Vanilla's GUI pipeline (POSITION_COLOR, QUADS, translucent, culls back faces).
	private static final RenderPipeline PIPELINE = RenderPipelines.GUI;

	private record MarkRenderState(RenderPipeline pipeline, TextureSetup textureSetup, Matrix3x2fc pose,
								   float[] xy, int[] argb, ScreenRectangle scissorArea,
								   ScreenRectangle bounds) implements GuiElementRenderState {
		@Override
		public void buildVertices(VertexConsumer out) {
			for (int k = 0; k < argb.length; k++) {
				out.addVertexWith2DPose(pose, xy[2 * k], xy[2 * k + 1]).setColor(argb[k]);
			}
		}
	}

	private static void draw(GuiGraphics g, double cx, double cy, double size, double[][] s, double alpha,
							 double dot, double dotAlpha) {
		Ctx c = begin(g);
		for (int k = 0; k < 3; k++) {
			stroke(c, cx, cy, size, s[k], alpha);
		}
		if (!Double.isNaN(dot)) {
			dot(c, cx, cy, size, s[0], dot, dotAlpha * alpha);
		}
		end(g, c);
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
		// Wound like GuiGraphics.fill (negative shoelace area with y down),
		// whichever way the curve runs, so a culling GUI pipeline keeps every band.
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
		c.put(x, y, argb);
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
