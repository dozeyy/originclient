package com.origin.client.client.render;

import net.minecraft.client.renderer.RenderType;

/**
 * The quad surface ThickLine and the block overlay draw into.
 *
 * 1.19.3 has no RenderType.debugQuads() (it arrived in 1.19.4), and the
 * package-private RenderType.create is unreachable, so this type is built on
 * the public RenderType constructor reusing vanilla's OWN state shards --
 * POSITION_COLOR_SHADER + TRANSLUCENT_TRANSPARENCY + NO_CULL, the exact three
 * debug_quads composes on 1.19.4 (bytecode-verified; buffer size 131072, no
 * crumbling, no sort-on-upload, same as 1.19.4's 5-arg create defaults).
 * Setup/clear delegate to the shards themselves so the GL state is vanilla's,
 * not a re-implementation.
 *
 * Lives in render/ (not inside BlockOverlayRenderer) because HitboxMixin needs
 * the same surface for its thick hitbox edges.
 */
public final class FillQuads extends RenderType {
	public static final RenderType INSTANCE = new FillQuads();

	private FillQuads() {
		super("originclient_fill_quads", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
				com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 131072, false, false,
				() -> {
					POSITION_COLOR_SHADER.setupRenderState();
					TRANSLUCENT_TRANSPARENCY.setupRenderState();
					NO_CULL.setupRenderState();
				},
				() -> {
					POSITION_COLOR_SHADER.clearRenderState();
					TRANSLUCENT_TRANSPARENCY.clearRenderState();
					NO_CULL.clearRenderState();
				});
	}
}
