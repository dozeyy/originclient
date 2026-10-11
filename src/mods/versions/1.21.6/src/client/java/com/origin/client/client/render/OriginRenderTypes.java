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
 * Origin's own world-overlay quad surface + the private buffer it is drawn
 * through. Used by the block outline/overlay, waypoint beams and chunk-border
 * posts -- anything that paints translucent, depth-tested quads into the world.
 *
 * Why not RenderType.debugQuads() through the frame's shared buffer (what the
 * outline used before): Iris (installed alongside Origin on this version) swaps
 * RenderBuffers' bufferSource for its own fully-buffered one during renderLevel
 * even with shaders OFF, so quads handed to it are drawn LATER, in Iris's flush,
 * under whatever matrices/state are current then. The outline then disagreed
 * with the depth buffer it was meant to be tested against -- worst right after
 * a zoom, when the FOV was changing -- and its hidden back edges showed through
 * the block. So: our own state, and our own buffer that is drawn on the spot
 * (begin() ... draw()), at the moment the world matrices and depth are right.
 *
 * 1.21.6-1.21.8 (RenderPipelines era) shape of 1.21.1's OriginRenderTypes. The
 * GL state lives in a RenderPipeline here, not in RenderStateShards:
 *   DEBUG_FILLED_SNIPPET -- vanilla debugQuads' base: core/position_color
 *     shaders, POSITION_COLOR QUADS, TRANSLUCENT blend,
 *   withCull(false) -- quads wound either way,
 *   LEQUAL depth TEST -- terrain in front hides it,
 *   withDepthWrite(false) -- no depth WRITE, so overlapping translucent faces and
 *     anything drawn after aren't punched out by our depth.
 * The one piece that is still a shard is VIEW_OFFSET_Z_LAYERING -- vanilla's own
 * outline nudge (scales the view a hair toward the camera, exactly what
 * RenderType.lines() uses) so faces lying ON a block surface win the depth tie
 * instead of z-fighting it.
 *
 * Access: RenderType.create, CompositeState(+ builder setters/createCompositeState),
 * RenderStateShard.VIEW_OFFSET_Z_LAYERING, RenderPipelines.DEBUG_FILLED_SNIPPET and
 * RenderPipelines.register are not public in vanilla; Fabric API's
 * fabric-transitive-access-wideners-v1 opens every one of them for dependants
 * (its "Creating custom render layers" block -- the same widener that already
 * lets OriginShaders call RenderPipelines.register with GUI_TEXTURED_SNIPPET).
 * Shapes verified against the decompiled 1.21.6 and 1.21.8 RenderType /
 * RenderPipelines / RenderPipeline (identical on both for everything used here).
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
