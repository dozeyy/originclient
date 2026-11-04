package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Origin's own world-overlay quad surface + the private buffer it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world.
 * (Hitboxes keep FillQuads through the frame's shared buffer.)
 *
 * Why not FillQuads through the frame's shared buffer (what the outline
 * used before): that buffer is not flushed at outline time -- vanilla ends it
 * later in the frame, and Iris's batched entity rendering swaps RenderBuffers'
 * bufferSource for its own fully-buffered one during renderLevel (on 1.21.1
 * even with shaders OFF) -- so the quads
 * were drawn LATER, under whatever matrices/state were current then, and
 * FillQuads sets no depth state of its own. The outline then disagreed
 * with the depth buffer it was meant to be tested against (worst right after
 * a zoom) and its hidden back edges showed through the block. So: our own
 * explicit state, and our own buffer that is drawn on the spot
 * (begin() ... draw()), at the moment the world matrices and depth are right.
 *
 * The state, spelled out (FillQuads's three shards + three more):
 *   POSITION_COLOR shader, TRANSLUCENT blend, NO_CULL (quads wound either way),
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   COLOR_WRITE only -- no depth WRITE, so overlapping translucent faces and
 *     anything drawn after aren't punched out by our depth,
 *   VIEW_OFFSET_Z_LAYERING -- vanilla's own outline nudge (scales the view a
 *     hair toward the camera) so faces lying ON a block surface win the depth
 *     tie instead of z-fighting it.
 *
 * Built on the public RenderType constructor with vanilla's OWN state shards
 * (protected statics, reachable from this subclass) -- RenderType.create is
 * not public, and there is no access widener in this module. Same technique
 * as render/FillQuads.
 *
 * ERA NOTE (1.19.2): pre-1.20.5 buffer API -- the private buffer is a plain
 * BufferBuilder (no ByteBufferBuilder before 1.20.5), and emitters use
 * vertex(m,x,y,z).color(...).endVertex(). Ported from 1.21.1's
 * OriginRenderTypes (2026-10-11 fix round).
 */
public final class OriginRenderTypes extends RenderType {

	/** Translucent, depth-tested, non-depth-writing world quads (see class doc). */
	public static final RenderType OVERLAY_QUADS = new OriginRenderTypes();

	// Private immediate buffer. 64 KiB start; BufferBuilder grows on demand.
	// Never shared with vanilla/Iris, so nothing else ever flushes or defers it.
	private static final MultiBufferSource.BufferSource IMMEDIATE =
			MultiBufferSource.immediate(new BufferBuilder(1 << 16));

	private OriginRenderTypes() {
		super("originclient_overlay_quads", DefaultVertexFormat.POSITION_COLOR,
				VertexFormat.Mode.QUADS, 1 << 16, false, false,
				() -> {
					POSITION_COLOR_SHADER.setupRenderState();
					TRANSLUCENT_TRANSPARENCY.setupRenderState();
					LEQUAL_DEPTH_TEST.setupRenderState();
					NO_CULL.setupRenderState();
					VIEW_OFFSET_Z_LAYERING.setupRenderState();
					COLOR_WRITE.setupRenderState();
				},
				() -> {
					COLOR_WRITE.clearRenderState();
					VIEW_OFFSET_Z_LAYERING.clearRenderState();
					NO_CULL.clearRenderState();
					LEQUAL_DEPTH_TEST.clearRenderState();
					TRANSLUCENT_TRANSPARENCY.clearRenderState();
					POSITION_COLOR_SHADER.clearRenderState();
				});
	}

	// ---- the after-shaders BANK (render/AfterShadersPass) ----
	// While an Iris shader pack is in use, the overlays that must stay above the
	// pack's clouds / fog (waypoints, party pings, WorldEdit CUI, Chunk Borders)
	// are wrapped in AfterShadersPass.bankBegin()/bankEnd(), which sets `banking`:
	// begin() then hands out the bank, the overlays' vanilla RenderType.lines()
	// go to bankLines() instead of the frame's shared buffer, draw() leaves them alone, and
	// AfterShadersPass.run() draws them with drawBank() once Iris has composited.
	// Separate from the immediate buffers above, so an unwrapped caller's draw()
	// (block outline, cosmetics) can never flush banked vertices early. Vertices
	// are posed at emission (the world PoseStack is baked in), so drawBank() only
	// needs RenderSystem's model-view + projection put back. One source per render
	// type: an immediate source with one shared builder draws the previous type's
	// batch the moment another type is requested. Shaders OFF: banking stays false
	// and nothing here is touched. Render thread only.
	static boolean banking;
	private static MultiBufferSource.BufferSource bankQuads, bankLines, bankExtra;
	static {
		newBank();
	}

	private static void newBank() {
		bankQuads = MultiBufferSource.immediate(new BufferBuilder(1 << 16));
		bankLines = MultiBufferSource.immediate(new BufferBuilder(1 << 14));
		bankExtra = MultiBufferSource.immediate(new BufferBuilder(1 << 14));
	}

	/** True while the current overlay's vertices go to the after-shaders bank. */
	public static boolean banking() {
		return banking;
	}

	/** The bank source for an overlay's vanilla RenderType.lines() (chunk-border rings, the waypoint
	 *  highlight). Use only while banking(). */
	public static MultiBufferSource.BufferSource bankLines() {
		return bankLines;
	}

	/** The bank source for a caller's own extra render type (the WorldEdit CUI's
	 *  see-through pass). Use only while banking(). */
	public static MultiBufferSource.BufferSource bankExtra() {
		return bankExtra;
	}

	/** Draws the bank NOW under the current matrices (AfterShadersPass.run()):
	 *  the see-through pass, the quads, then the lines -- lines last, as at LAST,
	 *  where they landed after every quad. */
	static void drawBank() {
		bankExtra.endBatch();
		bankQuads.endBatch();
		bankLines.endBatch();
	}

	/** Drops what the bank holds without drawing it (a frame that never reached
	 *  the post hook, or a failed draw). Rare path: fresh sources, so a builder
	 *  left mid-batch can never leak a half-written vertex into a later frame. */
	static void discardBank() {
		newBank();
	}

	/** The consumer to emit OVERLAY_QUADS vertices into (vertex + color + endVertex). */
	public static VertexConsumer begin() {
		return (banking ? bankQuads : IMMEDIATE).getBuffer(OVERLAY_QUADS);
	}

	/** Draws everything emitted since begin(), NOW, with the current matrices
	 *  and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		if (banking) {
			return;   // banked: AfterShadersPass.run() draws it after the pack's composite
		}
		IMMEDIATE.endBatch();
	}
}
