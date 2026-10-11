package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Origin's own world-overlay quad surface + the private buffers it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world
 * -- plus a private LINES buffer for the chunk-border rings and the waypoint
 * highlight box.
 *
 * Why not RenderType.debugQuads()/lines() through the frame's shared buffer:
 * Iris swaps the level's buffer source for its own fully-buffered one during
 * level rendering even with shaders OFF, so vertices handed to it are drawn
 * LATER, in Iris's flush, under whatever matrices/state are current then. The
 * outline then disagreed with the depth buffer it was meant to be tested
 * against -- worst right after a zoom -- and its hidden back edges showed
 * through the block. So: our own state, and our own buffers that are drawn on
 * the spot (begin()/beginLines() ... draw()), while the world matrices and
 * depth are the ones the frame is using.
 *
 * Matrix contract (verified against decompiled 1.21.10 LevelRenderer +
 * Fabric API 0.138.4 WorldRendererMixin): our callbacks run inside the main
 * frame pass, where RenderSystem's model-view stack holds the camera ROTATION
 * (renderLevel pushes it before the frame graph executes) and the context's
 * PoseStack is a fresh identity stack. Vertices are camera-RELATIVE (world pos
 * minus camera pos, subtracted once by the caller), so the rotation is applied
 * exactly once -- by the model-view the draw reads here.
 *
 * 1.21.10 (RenderPipelines era) -- the same shape as the 1.21.6/1.21.8 modules:
 *   DEBUG_FILLED_SNIPPET -- vanilla debugQuads' base: core/position_color
 *     shaders, POSITION_COLOR QUADS, TRANSLUCENT blend,
 *   withCull(false) -- quads wound either way,
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   withDepthWrite(false) -- no depth WRITE, so overlapping translucent faces and
 *     anything drawn after aren't punched out by our depth,
 *   VIEW_OFFSET_Z_LAYERING -- vanilla's own outline nudge (exactly what
 *     RenderType.lines() uses) so faces lying ON a block surface win the depth
 *     tie instead of z-fighting it.
 *
 * Access: RenderType.create(String, int, boolean, boolean, RenderPipeline,
 * CompositeState), CompositeState (+ builder setters/createCompositeState),
 * RenderStateShard.VIEW_OFFSET_Z_LAYERING, RenderPipelines.DEBUG_FILLED_SNIPPET
 * (yarn: POSITION_COLOR_SNIPPET) and RenderPipelines.register are not public in
 * vanilla; Fabric API 0.138.4's fabric-transitive-access-wideners-v1 opens every
 * one of them for dependants -- the identical entries 1.21.8's widener has, so
 * this is a plain direct call (it used to be a reflective lookup by parameter
 * shape, which was unnecessary and could silently fall back to debugQuads).
 */
public final class OriginRenderTypes {
	private OriginRenderTypes() {
	}

	/** Translucent, LEQUAL-tested, non-depth-writing, unculled POSITION_COLOR quads. */
	public static final RenderPipeline OVERLAY_QUADS_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/overlay_quads"))
					.withCull(false)
					.withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
					.withDepthWrite(false)
					.build());

	/** The render type the world overlays draw through (see class doc). */
	public static final RenderType OVERLAY_QUADS = RenderType.create("originclient_overlay_quads",
			1536, false, false, OVERLAY_QUADS_PIPELINE,
			RenderType.CompositeState.builder()
					.setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
					.createCompositeState(false));

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

	/** The consumer to emit OVERLAY_QUADS vertices into (addVertex + setColor). */
	public static VertexConsumer begin() {
		return IMMEDIATE.getBuffer(OVERLAY_QUADS);
	}

	/** The consumer to emit RenderType.lines() vertices into
	 *  (addVertex + setColor + setNormal). Drawn by draw(). */
	public static VertexConsumer beginLines() {
		return LINES_IMMEDIATE.getBuffer(RenderType.lines());
	}

	/** Draws everything emitted since begin()/beginLines(), NOW, with the current
	 *  matrices and depth buffer. Call once at the end of each world-render callback. */
	public static void draw() {
		IMMEDIATE.endBatch();
		LINES_IMMEDIATE.endBatch();
	}
}
