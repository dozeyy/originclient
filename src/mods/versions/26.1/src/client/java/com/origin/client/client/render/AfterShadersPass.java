package com.origin.client.client.render;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Draws Origin's world overlays that must stay visible through a shader pack's
 * clouds / fog AFTER the pack has finished: waypoint beams + highlight, party
 * ping pillars, the WorldEdit CUI selection and Chunk Borders. The 26.x
 * counterpart of 1.21.1's AfterShadersPass (same job, this era's method).
 *
 * THE PROBLEM. With an Iris shader pack on, the overlays' submits are drawn inside
 * the level pass, into the pack's gbuffers. Iris runs the pack's composite + final
 * passes later (iris$endLevelRender, at LevelRenderer.renderLevel's final
 * Matrix4fStack.popMatrix -- javap of the Iris this module pins), and packs paint
 * volumetric clouds / fog there, over the overlays.
 *
 * THE METHOD (26.x: everything in the world is a SUBMIT). OriginRenderTypes already
 * records each overlay's vertices before submitting them. The overlay calls are
 * wrapped in bankBegin()/bankEnd(): while a pack is in use, OriginRenderTypes moves
 * those recordings into its bank instead of submitting them to the level.
 * END_MAIN records the frame's model-view (the camera rotation LevelRenderer.renderLevel
 * pushed) and projection; GameRendererPostShadersMixin calls run() right after
 * LevelRenderer.renderLevel returns, when Iris has composited and turned its overrides
 * off. run() puts the recorded matrices back and submits + draws the bank the way
 * vanilla draws the first-person hand right after (GameRenderer.renderItemInHand;
 * see drawBank). The main target's depth still holds the scene, so terrain keeps hiding the beams. Unwrapped OriginRenderTypes callers
 * (block outline, cosmetics) still draw in the level pass, in the pack's fog.
 *
 * SHADERS OFF: unchanged. deferring is false, bankBegin() returns false, every
 * overlay submits to the level at COLLECT_SUBMITS exactly as before.
 *
 * FAIL-SOFT. Banking happens only while ALL of these hold: an Iris pack is in use
 * (IrisState, and Iris inside this module's compat-gates "iris" range), the post
 * hook has been seen to run (postHookAlive) -- the same mixin
 * starts the frame (beginFrame) and draws the bank (run), so if it does not apply
 * nothing is ever banked. Any failure leaves the overlays in the
 * level pass -- under the pack's clouds, the old behaviour -- and one log line says
 * why. A bank that was filled but never drawn is emptied at the next frame start.
 * If END_MAIN fails to record, or the late draw throws, the pass gives up for the
 * session (gaveUp): one frame without the overlays, then the old path.
 */
public final class AfterShadersPass {
	private AfterShadersPass() {
	}

	private static boolean initialized;

	// ---- per-frame state (render thread only) ----
	private static boolean gaveUp;          // record or draw failed once: stop deferring for the session
	private static boolean postHookAlive;
	private static boolean deferring;
	private static boolean banked;
	private static boolean recorded;
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
		LevelRenderEvents.END_MAIN.register(ctx -> {
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
	 * Wrap one overlay's draw: {@code boolean b = bankBegin(); try { draw } finally { bankEnd(b); }}.
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
			OriginRenderTypes.discardBank();   // filled but never drawn: the frame died before the hook
			banked = false;
		}
		recorded = false;
		deferring = postHookAlive && !gaveUp && packInUse();
	}

	private static void record() {
		if (!postHookAlive && !warnedHookMissing && packInUse() && ++hookMissingFrames > 60) {
			warnedHookMissing = true;
			OriginClient.LOGGER.warn("Origin: GameRendererPostShadersMixin never ran with a shader pack on;"
					+ " waypoints, pings, WorldEdit CUI and chunk borders stay in the level pass,"
					+ " under the pack's clouds");
		}
		if (!deferring) {
			return;
		}
		// Inside the main pass the camera rotation sits on RenderSystem's model-view
		// stack (LevelRenderer.renderLevel pushes viewRotationMatrix before the frame graph
		// executes and pops it before returning), so both matrices are copied here.
		REC_VIEW.set(RenderSystem.getModelViewStack());
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
			// Banked but END_MAIN never recorded the matrices: nothing to draw the bank
			// with. Give up deferring for the session (back to the in-pass draw, under
			// the pack's clouds) rather than lose the overlays every frame.
			OriginRenderTypes.discardBank();
			if (!gaveUp) {
				gaveUp = true;
				OriginClient.LOGGER.warn("Origin: END_MAIN did not record the frame; overlays go back to the"
						+ " level pass, under a shader pack's clouds");
			}
			return;
		}
		if (!OriginRenderTypes.bankHasVertices()) {
			OriginRenderTypes.discardBank();
			recorded = false;
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
			drawBank();
			if (!loggedFirst) {
				loggedFirst = true;   // one breadcrumb for CI logs: the deferred path really drew
				OriginClient.LOGGER.info("Origin: overlays drawn after the shader pack (after-shaders pass active)");
			}
		} catch (Throwable t) {
			OriginRenderTypes.discardBank();
			gaveUp = true;   // a draw that fails once will fail again: stop banking
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

	// 26.1: the hand's own sequence in GameRenderer.renderItemInHand (javap 26.1):
	// the dispatcher owns ONE storage (GameRenderer.getSubmitNodeStorage());
	// renderAllFeatures() draws and clears it, then the shared buffer source is
	// ended. The hand runs right after this hook and starts with the same two calls,
	// so the storage is empty here in a normal frame.
	private static void drawBank() {
		Minecraft mc = Minecraft.getInstance();
		OriginRenderTypes.submitBank(mc.gameRenderer.getSubmitNodeStorage());
		mc.gameRenderer.getFeatureRenderDispatcher().renderAllFeatures();
		mc.renderBuffers().bufferSource().endBatch();
	}

	// The deferral relies on WHERE Iris composites (end of LevelRenderer.renderLevel) and
	// on the main depth texture still holding the scene afterwards: proven for the
	// Iris line this module's compat-gates "iris" group names. Any other Iris = off =
	// the unchanged level-pass path (DECISIONS 2026-10-26).
	private static final boolean IRIS_KNOWN = com.origin.client.client.compat.CompatGates.on("iris");

	/** Is an Iris shader pack rendering the world? Through IrisState's reflective
	 *  handle: Iris missing, unknown, or its v0 API changed = false. */
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
