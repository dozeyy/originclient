package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * Origin's own world-overlay quad surface + the private buffer it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world.
 *
 * Why not RenderType.debugQuads() through the frame's shared buffer (what the
 * outline used before): Iris (installed with Origin on 1.21) swaps RenderBuffers'
 * bufferSource for its own fully-buffered one during renderLevel even with
 * shaders OFF, so quads handed to it are drawn LATER, in Iris's flush, under
 * whatever matrices/state are current then. The outline then disagreed with
 * the depth buffer it was meant to be tested against -- worst right after a
 * zoom, when the FOV was changing -- and its hidden back edges showed through
 * the block. So: our own state, and our own buffer that is drawn on the spot
 * (begin() ... draw()), at the moment the world matrices and depth are right.
 *
 * The state, spelled out instead of inherited from debugQuads' defaults:
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
 * as the pre-1.20 modules' FillQuads.
 *
 * ERA NOTE for porting: this constructor + shard set is the 1.20.5-1.21.1 shape
 * (ByteBufferBuilder/MeshData era). 1.21.2+ changes the RenderType API and
 * 1.21.6+ moves state into RenderPipelines -- build that era's equivalent
 * (LEQUAL depth, no depth write, view/polygon offset, translucent, no cull)
 * rather than copying this file.
 */
public final class OriginRenderTypes extends RenderType {

	/** Translucent, depth-tested, non-depth-writing world quads (see class doc). */
	public static final RenderType OVERLAY_QUADS = new OriginRenderTypes();

	// Private immediate buffer. 64 KiB start; ByteBufferBuilder grows on demand.
	// Never shared with vanilla/Iris, so nothing else ever flushes or defers it.
	private static final MultiBufferSource.BufferSource IMMEDIATE =
			MultiBufferSource.immediate(new ByteBufferBuilder(1 << 16));

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

	/** The consumer to emit OVERLAY_QUADS vertices into (addVertex + setColor). */
	public static VertexConsumer begin() {
		return IMMEDIATE.getBuffer(OVERLAY_QUADS);
	}

	/** Draws everything emitted since begin(), NOW, with the current matrices
	 *  and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		IMMEDIATE.endBatch();
	}
}
