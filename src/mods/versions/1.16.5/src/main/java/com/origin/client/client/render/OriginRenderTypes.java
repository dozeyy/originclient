package com.origin.client.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

/**
 * Origin's own world-overlay quad surface + the private buffer it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world.
 * (Hitboxes keep FillQuads through the entity buffer, as on 1.21.1.)
 *
 * Why not FillQuads through the frame's shared buffer (what the outline used
 * before): quads handed to the frame's buffer are drawn LATER, whenever that
 * buffer is flushed (and with Iris loaded, in Iris's own deferred flush), under
 * whatever matrices/state are current then. The outline could then disagree
 * with the depth buffer it was meant to be tested against -- worst right after
 * a zoom -- and its hidden back edges showed through the block. So: our own
 * state, and our own buffer that is drawn on the spot (begin() ... draw()), at
 * the moment the world matrices and depth are right.
 *
 * The state, spelled out instead of inherited from FillQuads' defaults:
 *   NO_TEXTURE (fixed function: texturing off, colour from the vertices),
 *   TRANSLUCENT blend, NO_CULL (quads wound either way),
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   COLOR_WRITE only -- no depth WRITE, so overlapping translucent faces and
 *     anything drawn after aren't punched out by our depth,
 *   VIEW_OFFSET_Z_LAYERING -- vanilla's own outline nudge (1.16: pushes the GL
 *     model-view and scales it a hair toward the camera) so faces lying ON a
 *     block surface win the depth tie instead of z-fighting it.
 *
 * 1.16.5 shape: fixed-function era, no shader shards at all -- same public
 * RenderType constructor as the module's FillQuads (raw GL mode int, no
 * VertexFormat.Mode until 1.17) with vanilla's own shards (protected statics,
 * reachable from this subclass; all six exist on 1.16.5 -- LEQUAL_DEPTH_TEST,
 * COLOR_WRITE and VIEW_OFFSET_Z_LAYERING are the yarn LEQUAL_DEPTH_TEST /
 * COLOR_MASK / VIEW_OFFSET_Z_LAYERING fields of RenderPhase). The buffer is the
 * pre-1.20.5 MultiBufferSource.immediate(new BufferBuilder(n)).
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
				GL11.GL_QUADS, 1 << 16, false, false,
				() -> {
					NO_TEXTURE.setupRenderState();
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
					NO_TEXTURE.clearRenderState();
				});
	}

	/** The consumer to emit OVERLAY_QUADS vertices into (vertex + color + endVertex). */
	public static VertexConsumer begin() {
		return IMMEDIATE.getBuffer(OVERLAY_QUADS);
	}

	/** Draws everything emitted since begin(), NOW, with the current matrices
	 *  and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		IMMEDIATE.endBatch();
	}
}
