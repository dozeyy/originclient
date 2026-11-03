package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.LayeringTransform;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Origin's own world-overlay quad surface + the private buffers it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world
 * -- plus a private LINES buffer for the chunk-border rings and the waypoint
 * highlight box.
 *
 * Why not RenderTypes.debugQuads()/lines() through the frame's shared buffer:
 * Iris swaps the level's buffer source for its own fully-buffered one during
 * level rendering even with shaders OFF, so vertices handed to it are drawn
 * LATER, in Iris's flush, under whatever matrices/state are current then. The
 * outline then disagreed with the depth buffer it was meant to be tested
 * against -- worst right after a zoom -- and its hidden back edges showed
 * through the block. So: our own state, and our own buffers that are drawn on
 * the spot (begin()/beginLines() ... draw()), while the world matrices and
 * depth are the ones the frame is using.
 *
 * Matrix contract (verified against decompiled 1.21.11 LevelRenderer +
 * Fabric API 0.141.4 LevelRendererMixin): our callbacks run inside the main
 * frame pass, where RenderSystem's model-view stack holds the camera ROTATION
 * (renderLevel pushes it before the frame graph executes) and the context's
 * PoseStack is a fresh identity stack. Vertices are camera-RELATIVE (world pos
 * minus camera pos, subtracted once by the caller), so the rotation is applied
 * exactly once -- by the model-view the draw reads here.
 *
 * 1.21.11 shape: RenderType moved to ...renderer.rendertype and is built from a
 * RenderSetup instead of a CompositeState.
 *   DEBUG_FILLED_SNIPPET -- vanilla debugQuads' base: core/position_color,
 *     POSITION_COLOR QUADS, TRANSLUCENT blend, no depth WRITE (the snippet
 *     itself sets withDepthWrite(false) on this version; restated below),
 *   withCull(false) -- quads wound either way,
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   LayeringTransform.VIEW_OFFSET_Z_LAYERING -- vanilla's own outline nudge
 *     (exactly what RenderTypes.lines() uses) so faces lying ON a block surface
 *     win the depth tie instead of z-fighting it.
 *
 * Access: RenderType.create(String, RenderSetup) (package-private),
 * RenderPipelines.DEBUG_FILLED_SNIPPET and RenderPipelines.register (private)
 * are opened by Fabric API 0.141.4's fabric-transitive-access-wideners-v1 (all
 * three are listed there by their Mojang names); RenderSetup, its builder and
 * LayeringTransform are public. So this is a plain direct call (it used to be
 * a reflective lookup by parameter shape, which was unnecessary and could
 * silently fall back to debugQuads).
 */
public final class OriginRenderTypes {
	private OriginRenderTypes() {
	}

	/** Translucent, LEQUAL-tested, non-depth-writing, unculled POSITION_COLOR quads. */
	public static final RenderPipeline OVERLAY_QUADS_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath("originclient", "pipeline/overlay_quads"))
					.withCull(false)
					.withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
					.withDepthWrite(false)
					.build());

	/** The render type the world overlays draw through (see class doc). */
	public static final RenderType OVERLAY_QUADS = RenderType.create("originclient_overlay_quads",
			RenderSetup.builder(OVERLAY_QUADS_PIPELINE)
					.setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
					.createRenderSetup());

	// Private immediate buffer. 64 KiB start; ByteBufferBuilder grows on demand.
	// Never shared with vanilla/Iris, so nothing else ever flushes or defers it.
	private static final MultiBufferSource.BufferSource IMMEDIATE =
			MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));

	// Second private immediate buffer for vanilla's LINES type. A separate
	// BufferSource, so lines and quads can be emitted interleaved: one immediate
	// source holding two types would end -- and invalidate -- the first consumer
	// when the second is requested.
	private static final MultiBufferSource.BufferSource LINES_IMMEDIATE =
			MultiBufferSource.immediate(new ByteBufferBuilder(1 << 14));

	// The after-shaders BANK (render/AfterShadersPass). While an Iris shader pack
	// is in use, the overlays that must stay above the pack's clouds (waypoints,
	// party pings, WorldEdit CUI, Chunk Borders) are wrapped in
	// AfterShadersPass.bankBegin()/bankEnd(), which sets `banking`: their vertices
	// go into these three sources instead, draw() leaves them alone, and
	// AfterShadersPass.run() draws them with drawBank() once Iris has composited.
	// Separate from IMMEDIATE / LINES_IMMEDIATE, so an unwrapped caller's draw()
	// (block outline, cosmetics) can never flush banked vertices early.
	// Render thread only.
	static boolean banking;
	private static MultiBufferSource.BufferSource bankQuads, bankLines, bankExtra;
	// The bank's own native buffers: kept so discardBank() can free them (BufferSource has no close).
	private static ByteBufferBuilder bankQuadsBuf, bankLinesBuf, bankExtraBuf;
	static {
		newBank();
	}

	private static void newBank() {
		bankQuadsBuf = new ByteBufferBuilder(1 << 16);
		bankLinesBuf = new ByteBufferBuilder(1 << 14);
		bankExtraBuf = new ByteBufferBuilder(1 << 14);
		bankQuads = MultiBufferSource.immediate(bankQuadsBuf);
		bankLines = MultiBufferSource.immediate(bankLinesBuf);
		bankExtra = MultiBufferSource.immediate(bankExtraBuf);
	}

	/** True while the current overlay's vertices go to the after-shaders bank. */
	public static boolean banking() {
		return banking;
	}

	/** The bank source for a caller's own extra render type (the WorldEdit CUI's
	 *  see-through pass); drawn first by drawBank(). Use only while banking(). */
	public static MultiBufferSource.BufferSource bankExtra() {
		return bankExtra;
	}

	/** Draws the bank NOW under the current matrices (AfterShadersPass.run()). */
	static void drawBank() {
		bankExtra.endBatch();
		bankQuads.endBatch();
		bankLines.endBatch();
	}

	/** Drops what the bank holds without drawing it (a frame that never reached
	 *  the post hook, or a failed draw). Rare path: fresh sources, so a
	 *  half-written vertex can never leak into a later frame. */
	static void discardBank() {
		// Free the old native buffers first (~100 KB off-heap each time otherwise); nothing
		// else holds them once the sources below are replaced.
		ByteBufferBuilder[] old = {bankQuadsBuf, bankLinesBuf, bankExtraBuf};
		newBank();
		for (ByteBufferBuilder b : old) {
			try {
				b.close();
			} catch (Throwable ignored) {
				// already freed or never allocated: nothing to release
			}
		}
	}

	/** The consumer to emit OVERLAY_QUADS vertices into (addVertex + setColor). */
	public static VertexConsumer begin() {
		return (banking ? bankQuads : IMMEDIATE).getBuffer(OVERLAY_QUADS);
	}

	/** The consumer to emit RenderTypes.lines() vertices into (addVertex +
	 *  setColor + setNormal + setLineWidth -- the 1.21.11 lines format REQUIRES
	 *  the per-vertex LineWidth element). Drawn by draw(). */
	public static VertexConsumer beginLines() {
		return (banking ? bankLines : LINES_IMMEDIATE).getBuffer(RenderTypes.lines());
	}

	/** Draws everything emitted since begin()/beginLines(), NOW, with the current
	 *  matrices and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		if (banking) {
			return;   // banked: AfterShadersPass.run() draws it after the pack's composite
		}
		IMMEDIATE.endBatch();
		LINES_IMMEDIATE.endBatch();
	}
}
