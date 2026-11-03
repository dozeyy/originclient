package com.origin.client.client.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Draws Origin's world overlays that must stay visible through a shader pack's
 * clouds / fog AFTER the pack has finished: waypoint beams + highlight, party
 * ping pillars, the WorldEdit CUI selection and Chunk Borders. The 1.21.11
 * counterpart of 1.21.1's AfterShadersPass (same job, this era's method).
 *
 * THE PROBLEM. With an Iris shader pack on, a draw made at WorldRenderEvents.END_MAIN
 * lands in the pack's gbuffers, inside the level pass. Iris runs the pack's
 * composite + final passes later (iris$endLevelRender, at LevelRenderer.renderLevel's
 * final Matrix4fStack.popMatrix -- javap of iris-fabric-1.10.8+mc1.21.11), and packs
 * paint volumetric clouds / fog there, over the overlays.
 *
 * THE METHOD (1.21.10 / 1.21.11: why not 1.21.1's re-run-the-drawers). Every one
 * of these overlays already draws through OriginRenderTypes' own immediate
 * buffers, with camera-relative vertices and the camera rotation taken from
 * RenderSystem's model-view at draw time. So instead of calling the overlays a
 * second time, the overlay calls are wrapped in bankBegin()/bankEnd(): while a
 * pack is in use, OriginRenderTypes hands them a second pair of buffers (the
 * "bank") and does not draw them. END_MAIN records the frame's model-view and
 * projection; GameRendererPostShadersMixin calls run() right after
 * LevelRenderer.renderLevel returns, when Iris has composited into the main
 * target and turned its overrides off; run() puts the recorded matrices back and
 * draws the bank. The main target's depth still holds the scene depth (Iris's
 * depthtex0 is that texture), so terrain keeps hiding the beams as before.
 * Nothing else that uses OriginRenderTypes (block outline, cosmetics) is wrapped,
 * so it still draws in the level pass, in the pack's fog.
 *
 * SHADERS OFF: unchanged. deferring is false, bankBegin() returns false, every
 * overlay draws at END_MAIN with the immediate buffers exactly as before.
 *
 * FAIL-SOFT. Banking happens only while ALL of these hold: an Iris pack is in
 * use (IrisState; Iris absent or its API changed = "no pack"), the post hook
 * has been seen to run (postHookAlive) -- the same mixin
 * starts the frame (beginFrame) and draws the bank (run), so if it does not apply
 * nothing is ever banked. Any failure leaves the overlays at END_MAIN -- under the
 * pack's clouds, the old behaviour, nothing worse -- and one log line says why.
 * A bank that was filled but never drawn (renderLevel threw after END_MAIN) is
 * thrown away at the next frame start, never drawn under a later frame's matrices.
 *
 * No per-frame allocation: fixed matrices, static flags.
 */
public final class AfterShadersPass {
	private AfterShadersPass() {
	}

	private static boolean initialized;

	// ---- per-frame state (render thread only) ----
	private static boolean postHookAlive;   // GameRendererPostShadersMixin has run at least once
	private static boolean deferring;       // decided once per frame in beginFrame()
	private static boolean banked;          // something went into the bank this frame
	private static boolean recorded;        // END_MAIN recorded this frame's matrices
	private static final Matrix4f REC_VIEW = new Matrix4f();
	private static GpuBufferSlice recProj;
	private static ProjectionType recProjType;

	private static int hookMissingFrames;
	private static boolean warnedHookMissing, warnedSetup, warnedDraw, loggedFirst;

	/** Registers the END_MAIN recorder. Call once at client init. */
	public static void init() {
		if (initialized) {
			return;
		}
		initialized = true;
		WorldRenderEvents.END_MAIN.register(ctx -> {
			try {
				record();
			} catch (Throwable t) {
				deferring = false;
				recorded = false;
				warnSetupOnce("frame record", t);
			}
		});
	}

	/**
	 * Wrap one overlay's END_MAIN draw: {@code boolean b = bankBegin(); try { draw } finally { bankEnd(b); }}.
	 * Returns true when this frame's overlays go to the bank (drawn by run()).
	 */
	public static boolean bankBegin() {
		if (!deferring) {
			return false;
		}
		OriginRenderTypes.banking = true;
		banked = true;
		return true;
	}

	public static void bankEnd(boolean began) {
		if (began) {
			OriginRenderTypes.banking = false;
		}
	}

	/** First failure of the pass itself goes to the log once, with its stack. */
	public static void warnSetupOnce(String where, Throwable t) {
		if (!warnedSetup) {
			warnedSetup = true;
			OriginClient.LOGGER.warn("Origin: after-shaders pass ({}) failed (logged once)", where, t);
		}
	}

	/**
	 * Called from GameRendererPostShadersMixin right BEFORE the level render call,
	 * every frame: decides once whether this frame's overlays are banked, so every
	 * overlay listener sees the same answer whatever order it registered in.
	 */
	public static void beginFrame() {
		OriginRenderTypes.banking = false;
		if (banked) {
			// Filled but never drawn: the frame died between END_MAIN and the hook.
			OriginRenderTypes.discardBank();
			banked = false;
		}
		recorded = false;
		// Short-circuit: once the hook is alive this is the single Iris check per frame.
		deferring = postHookAlive && packInUse();
	}

	private static void record() {
		// Hook-missing watchdog: only until the hook has been seen once.
		if (!postHookAlive && !warnedHookMissing && packInUse() && ++hookMissingFrames > 60) {
			warnedHookMissing = true;
			OriginClient.LOGGER.warn("Origin: GameRendererPostShadersMixin never ran with a shader pack on;"
					+ " waypoints, pings, WorldEdit CUI and chunk borders are drawn at END_MAIN,"
					+ " under the pack's clouds");
		}
		if (!deferring) {
			return;
		}
		// Inside the main pass the camera ROTATION sits on RenderSystem's model-view
		// stack (OriginRenderTypes' matrix contract) and the projection is the
		// level's. renderLevel pops the rotation before returning, so both are copied.
		REC_VIEW.set(RenderSystem.getModelViewMatrix());
		recProj = RenderSystem.getProjectionMatrixBuffer();
		recProjType = RenderSystem.getProjectionType();
		recorded = recProj != null && recProjType != null;
	}

	/**
	 * Called from GameRendererPostShadersMixin right after LevelRenderer.renderLevel,
	 * every frame. Draws the bank under the recorded matrices when this frame
	 * banked anything; otherwise only notes that the hook is alive.
	 */
	public static void run() {
		postHookAlive = true;
		if (!banked) {
			return;
		}
		banked = false;
		OriginRenderTypes.banking = false;
		if (!recorded) {
			OriginRenderTypes.discardBank();   // no matrices to draw it with
			return;
		}
		recorded = false;
		GpuBufferSlice oldProj = RenderSystem.getProjectionMatrixBuffer();
		ProjectionType oldType = RenderSystem.getProjectionType();
		Matrix4fStack mv = RenderSystem.getModelViewStack();
		mv.pushMatrix();
		try {
			mv.set(REC_VIEW);
			RenderSystem.setProjectionMatrix(recProj, recProjType);
			OriginRenderTypes.drawBank();
			if (!loggedFirst) {
				loggedFirst = true;   // one breadcrumb for CI logs: the deferred path really drew
				OriginClient.LOGGER.info("Origin: overlays drawn after the shader pack (after-shaders pass active)");
			}
		} catch (Throwable t) {
			OriginRenderTypes.discardBank();
			if (!warnedDraw) {
				warnedDraw = true;
				OriginClient.LOGGER.warn("Origin: after-shaders overlay draw failed; bank reset (logged once)", t);
			}
		} finally {
			mv.popMatrix();
			if (oldProj != null && oldType != null) {
				RenderSystem.setProjectionMatrix(oldProj, oldType);
			}
		}
	}

	// The deferral relies on WHERE Iris composites (end of LevelRenderer.renderLevel)
	// and on the main depth texture still holding the scene afterwards: proven for
	// the Iris line this module's compat-gates "iris" group names. Any other Iris =
	// off = the unchanged END_MAIN path (DECISIONS 2026-10-26).
	private static final boolean IRIS_KNOWN = com.origin.client.client.compat.CompatGates.on("iris");

	/** Is an Iris shader pack rendering the world? Iris is never bundled on 1.21.11
	 *  (the launcher installs it), so this goes through IrisState's reflective handle:
	 *  Iris missing, unknown, or its v0 API changed = false = the unchanged END_MAIN path. */
	private static boolean packInUse() {
		if (!IRIS_KNOWN || IrisState.PACK_IN_USE == null) {
			return false;
		}
		try {
			return (boolean) IrisState.PACK_IN_USE.invokeExact();
		} catch (Throwable t) {
			return false;
		}
	}
}
