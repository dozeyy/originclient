package com.origin.client.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * The ONE place world overlays that must stay visible through a shader pack's
 * clouds / fog are drawn: waypoint beams + highlight, party ping pillars, the
 * WorldEdit CUI selection and Chunk Borders.
 *
 * THE PROBLEM. With an Iris shader pack on, a draw made at WorldRenderEvents.LAST
 * lands in the pack's gbuffers BEFORE the pack's composite passes, and packs paint
 * their volumetric clouds / fog in composite -- on top of it. Our overlays write no
 * depth, so a pack cannot place them in front of a cloud either.
 *
 * THE FIX. While a pack is in use, the overlays do NOT draw at LAST. This class's
 * own LAST listener only records the frame (matrices, vertex sorting, camera), and
 * GameRendererPostShadersMixin calls run() right after LevelRenderer.renderLevel
 * returns. By then Iris has run composite + final into the main target, its shader
 * overrides are off, and the main target's depth texture (which IS Iris's
 * depthtex0) still holds the scene depth, so terrain keeps hiding the overlay as
 * before while nothing a pack composites can cover it. run() sets the GL state
 * ONCE and calls every registered drawer in registration order.
 *
 * SHADERS OFF: unchanged. deferring(slot) is false, every overlay's own LAST
 * entry draws with its original buffers exactly as it always did; this class
 * only counts frames and does nothing else.
 *
 * FAIL-SOFT. An overlay defers only while ALL of these hold: a pack is in use,
 * the post hook has been seen to run (postHookAlive), the START event fires
 * (otherwise record() sees LAST without START and stays at LAST), and the
 * overlay really got a slot (register() returned >= 0). Any failure leaves the
 * overlays drawing at LAST -- under the pack's clouds, the old behaviour, nothing
 * worse -- and one log line names what is missing.
 *
 * CONTRACT FOR AN OVERLAY.
 *  1. Keep a LAST entry: `if (AfterShadersPass.deferring(slot)) return;` first,
 *     then draw with the original buffers.
 *  2. Keep the int slot that register() returned (-1 = not registered, so the
 *     LAST entry keeps drawing). Registration happens in ONE place, in the draw
 *     order (OriginClientMod); a repeated name is ignored.
 *  3. The Drawer is the SAME body the LAST entry runs (all its mod-off /
 *     null-player / empty-region early-outs live in that one body), fed this
 *     pass's matrices.
 * deferring(slot) is decided once per frame at WorldRenderEvents.START, so every
 * overlay gets the same answer whatever order the LAST listeners run in.
 *
 * No per-frame allocation here (fixed arrays, index loops, reused matrices, one
 * reused PoseStack). The BufferBuilder that BufferSource.getBuffer() makes per
 * batch is vanilla's own cost and the same as the LAST path pays.
 */
public final class AfterShadersPass {
	private AfterShadersPass() {
	}

	/**
	 * One overlay's world draw. {@code pose} holds the frame's recorded matrices,
	 * {@code cam} is the camera position, {@code lines} is this pass's private
	 * immediate buffer for RenderType.lines() (the frame's shared bufferSource is
	 * Iris's fully-buffered one and is never flushed here). Overlays that draw with
	 * Origin's own render types (OriginRenderTypes / private buffers) ignore it.
	 * A drawer should flush its own private buffers; run() still flushes both
	 * shared ones after it, in a finally, so a throw cannot leak vertices.
	 */
	public interface Drawer {
		void draw(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource lines);
	}

	// ---- registry (filled once at client init, read by index) ----
	private static final int MAX_DRAWERS = 8;
	private static final Drawer[] DRAWERS = new Drawer[MAX_DRAWERS];
	private static final String[] NAMES = new String[MAX_DRAWERS];
	private static final boolean[] WARNED = new boolean[MAX_DRAWERS];
	private static int count;
	private static boolean initialized;

	// ---- per-frame state (render thread only) ----
	private static boolean postHookAlive;
	private static boolean deferring;
	private static boolean startSeen;   // START ran since the last LAST
	private static boolean pending;
	private static final Matrix4f PEND_POSE = new Matrix4f();
	private static final Matrix3f PEND_NORMAL = new Matrix3f();
	private static final Matrix4f PEND_VIEW = new Matrix4f();
	private static final Matrix4f PEND_PROJ = new Matrix4f();
	private static VertexSorting pendSorting = VertexSorting.DISTANCE_TO_ORIGIN;
	private static Vec3 pendCam = Vec3.ZERO;

	// The pass's own line buffer. ByteBufferBuilder grows by realloc on demand
	// (javap 1.21.1: ensureCapacity -> resize by max(needed, min(cap, 2 MiB))) and
	// keeps the memory, so 64 KiB is only the starting size: Chunk Borders' rings +
	// lattice are ~6 KiB (140 lines x 2 vertices x 20 B) and a waypoint highlight
	// is 480 B each, so this holds ~100 highlights before its one-time growth.
	// Not final: resetLines() swaps in a fresh pair after a failed flush.
	private static ByteBufferBuilder linesBytes;
	private static MultiBufferSource.BufferSource lines;
	static {
		newLines();
	}
	// Reused every frame; re-seeded from the recording before each drawer.
	private static final PoseStack POSE = new PoseStack();

	// Frames a shader pack has been on while the post hook has never run. Past
	// ~60 the hook almost certainly failed to apply: say so once in the log.
	private static int hookMissingFrames;
	private static boolean warnedHookMissing, warnedNoStart, warnedSetup;
	private static final boolean[] WARNED_FLUSH = new boolean[2];   // [0] lines, [1] overlay quads

	/** Registers the frame listeners. Call once at client init, BEFORE any register(). */
	public static void init() {
		if (initialized) {
			return;   // a second init() must not double the listeners
		}
		initialized = true;
		// START precedes every LAST listener of the same frame, so deferring(slot)
		// is one stable answer for all overlays whatever order they registered in.
		WorldRenderEvents.START.register(ctx -> {
			try {
				beginFrame();
			} catch (Throwable t) {
				deferring = false;   // a failed check means "draw at LAST", never "draw nowhere"
				warnSetupOnce("frame start", t);
			}
		});
		WorldRenderEvents.LAST.register(ctx -> {
			try {
				record(ctx);
			} catch (Throwable t) {
				deferring = false;
				warnSetupOnce("frame record", t);
			}
		});
	}

	/**
	 * Adds an overlay's drawer and returns its slot for deferring(slot). Call
	 * from client init only, in the order the overlays should draw. The name is
	 * for the log only. A repeated name returns the existing slot and adds
	 * nothing. Returns -1 when the table is full: deferring(-1) is always false,
	 * so that overlay keeps drawing at LAST.
	 */
	public static int register(String name, Drawer drawer) {
		for (int i = 0; i < count; i++) {
			if (NAMES[i].equals(name)) {
				return i;
			}
		}
		if (count >= MAX_DRAWERS) {
			OriginClient.LOGGER.warn("Origin: after-shaders pass is full; '{}' keeps drawing at LAST only", name);
			return -1;
		}
		NAMES[count] = name;
		DRAWERS[count] = drawer;
		return count++;
	}

	/**
	 * True when this overlay's frame is drawn by run() instead of at LAST: a
	 * shader pack is in use, the post hook has been seen to run, START fires, and
	 * the overlay holds a real slot. An overlay's LAST entry returns at once when
	 * this is true.
	 */
	public static boolean deferring(int slot) {
		return deferring && slot >= 0;
	}

	/** First failure of the pass itself goes to the log once, with its stack. */
	public static void warnSetupOnce(String where, Throwable t) {
		if (!warnedSetup) {
			warnedSetup = true;
			OriginClient.LOGGER.warn("Origin: after-shaders pass ({}) failed (logged once)", where, t);
		}
	}

	private static void warnDrawerOnce(int i, Throwable t) {
		if (!WARNED[i]) {
			WARNED[i] = true;
			OriginClient.LOGGER.warn("Origin: after-shaders drawer '{}' failed (logged once)", NAMES[i], t);
		}
	}

	private static void beginFrame() {
		startSeen = true;
		// A recording that never reached run() (renderLevel threw after LAST, or the
		// hook was skipped) must not be drawn by a LATER frame -- possibly one with
		// shaders off, which would draw the overlay a second time.
		pending = false;
		// Short-circuit: once the hook is alive this is the single Iris check per frame.
		deferring = postHookAlive && ShaderPack.inUse();
	}

	private static void record(WorldRenderContext ctx) {
		if (!startSeen) {
			// LAST without START: the START event is dead, so deferring is never
			// decided. Stay at LAST (deferring=false) and say so.
			deferring = false;
			if (!warnedNoStart) {
				warnedNoStart = true;
				OriginClient.LOGGER.warn("Origin: WorldRenderEvents.START did not fire before LAST;"
						+ " overlays are drawn at LAST, under a shader pack's clouds");
			}
		}
		startSeen = false;
		// Hook-missing watchdog, here (not in START) so it also runs when START is
		// dead. Only until the hook has been seen once, so it costs nothing after.
		if (!postHookAlive && !warnedHookMissing && ShaderPack.inUse() && ++hookMissingFrames > 60) {
			warnedHookMissing = true;
			OriginClient.LOGGER.warn("Origin: GameRendererPostShadersMixin never ran with a shader pack on;"
					+ " waypoints, pings, WorldEdit CUI and chunk borders are drawn at WorldRenderEvents.LAST,"
					+ " under the pack's clouds");
		}
		if (!deferring) {
			return;
		}
		PoseStack pose = ctx.matrixStack();
		if (pose == null) {
			return;
		}
		// Record exactly what world geometry is transformed by right now: on 1.21.1
		// the camera rotation sits on RenderSystem's model-view stack and the
		// PoseStack is identity, so BOTH are needed to rebuild the same transform.
		PEND_POSE.set(pose.last().pose());
		PEND_NORMAL.set(pose.last().normal());
		PEND_VIEW.set(RenderSystem.getModelViewMatrix());
		PEND_PROJ.set(RenderSystem.getProjectionMatrix());
		pendSorting = RenderSystem.getVertexSorting();
		pendCam = ctx.camera().getPosition();
		pending = true;
	}

	/**
	 * Called from GameRendererPostShadersMixin after LevelRenderer.renderLevel,
	 * every frame. Draws every registered overlay from the frame recorded at LAST
	 * when a shader pack is in use; otherwise only notes that the hook is alive.
	 * A setup failure is restored and rethrown for the mixin to log; a failing
	 * drawer is logged once and skipped without affecting the others.
	 */
	public static void run() {
		postHookAlive = true;
		if (!pending) {
			return;
		}
		pending = false;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		// By reference: setProjectionMatrix stores a fresh copy in RenderSystem's
		// field (javap, 1.21.1), so this object is never written while we hold it.
		Matrix4f oldProj = RenderSystem.getProjectionMatrix();
		VertexSorting oldSorting = RenderSystem.getVertexSorting();
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		try {
			mc.getMainRenderTarget().bindWrite(false);
			RenderSystem.setProjectionMatrix(PEND_PROJ, pendSorting);   // copied inside
			mv.set(PEND_VIEW);
			RenderSystem.applyModelViewMatrix();
			for (int i = 0; i < count; i++) {
				// Copy, don't mulPose: mulPose allocates a Matrix3f for non-translations.
				// Re-seeded per drawer so one that mutated or leaked a push can't skew the next.
				POSE.setIdentity();
				POSE.last().pose().set(PEND_POSE);
				POSE.last().normal().set(PEND_NORMAL);
				try {
					DRAWERS[i].draw(POSE, pendCam, lines);
				} catch (Throwable t) {
					warnDrawerOnce(i, t);
				} finally {
					flush(i);
				}
			}
		} finally {
			mv.popMatrix();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.setProjectionMatrix(oldProj, oldSorting);
		}
	}

	// After every drawer, also when it threw: draw whatever it left in the two
	// shared buffers NOW, so half-built vertices are never carried into the next
	// drawer (or the next frame's matrices). Same two calls, same order, as an
	// overlay's own normal flush: lines first, then Origin's overlay quads.
	//
	// A failure here is NOT the drawer's own (it may just have left an unfinished
	// vertex), so the log names the flush step and the drawer it followed.
	// What 1.21.1 does with a failed build (javap BufferSource / BufferBuilder /
	// ByteBufferBuilder): BufferSource.endBatch(RenderType) REMOVES the builder from
	// startedBuilders before calling build(), so a builder whose build() threw
	// ("Missing elements in vertex") is never retried -- it cannot re-throw on every
	// later flush -- and endLastBatch() nulls lastSharedType only after the call, which
	// is harmless because the next endBatch finds nothing to remove. What it does
	// leave behind is the half-written bytes: ByteBufferBuilder.build() hands out
	// everything since nextResultOffset, and discard() only compacts finished results,
	// so the next mesh would start with that stale tail for one flush. So after a
	// failed flush the buffer pair is swapped for a fresh one (rare path; allocation
	// is fine here), which leaves nothing to poison later drawers or frames.
	private static void flush(int i) {
		try {
			lines.endBatch();
		} catch (Throwable t) {
			warnFlushOnce(0, "line", i, t);
			resetLines();
		}
		try {
			OriginRenderTypes.draw();
		} catch (Throwable t) {
			warnFlushOnce(1, "overlay-quad", i, t);
			OriginRenderTypes.reset();
		}
	}

	private static void warnFlushOnce(int which, String buffer, int afterDrawer, Throwable t) {
		if (!WARNED_FLUSH[which]) {
			WARNED_FLUSH[which] = true;
			OriginClient.LOGGER.warn("Origin: after-shaders flush of the {} buffer failed after drawer '{}';"
					+ " buffer reset (logged once per buffer)", buffer, NAMES[afterDrawer], t);
		}
	}

	private static void newLines() {
		linesBytes = new ByteBufferBuilder(1 << 16);
		lines = MultiBufferSource.immediate(linesBytes);
	}

	private static void resetLines() {
		ByteBufferBuilder old = linesBytes;
		newLines();
		try {
			old.close();   // frees the native memory; no result of it is outstanding
		} catch (Throwable ignored) {
			// leaking one 64 KiB block once beats losing the frame
		}
	}

	/** Is an Iris shader pack rendering the world? Iris is bundled jar-in-jar on
	 *  1.21.1 (build.gradle include), so this is the direct API call. NOT
	 *  OriginRender.shaderPackOn(): that one is private, answers TRUE on any doubt
	 *  (we need false: "doubt" must mean the unchanged LAST path), and living in
	 *  render.terrain it would load a class full of Sodium internals just to ask
	 *  this question. The Iris-loaded check is resolved once; the API call is two
	 *  null checks (javap IrisApiV0Impl.isShaderPackInUse): no allocation. */
	private static final class ShaderPack {
		private static final boolean IRIS =
				net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("iris");

		static boolean inUse() {
			if (!IRIS) {
				return false;
			}
			try {
				return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
			} catch (Throwable t) {
				return false;
			}
		}
	}
}
