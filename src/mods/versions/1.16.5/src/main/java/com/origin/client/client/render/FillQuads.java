package com.origin.client.client.render;

import net.minecraft.client.renderer.RenderType;
import org.lwjgl.opengl.GL11;

/**
 * The quad surface ThickLine draws into.
 *
 * 1.16.5 has no RenderType.debugQuads() (it arrived in 1.19.4) and no shader
 * objects at all (fixed-function era), so this type is built on the public
 * RenderType constructor reusing vanilla's OWN state shards -- NO_TEXTURE
 * (texturing off, colours come from the vertices) + TRANSLUCENT_TRANSPARENCY +
 * NO_CULL. The constructor takes a raw GL mode int on this version (no
 * VertexFormat.Mode until 1.17). Setup/clear delegate to the shards themselves
 * so the GL state is vanilla's, not a re-implementation.
 *
 * Lives in render/ (not inside BlockOverlayRenderer) because HitboxMixin needs
 * the same surface for its thick hitbox edges.
 */
public final class FillQuads extends RenderType {
	public static final RenderType INSTANCE = new FillQuads();

	private FillQuads() {
		super("originclient_fill_quads", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR,
				GL11.GL_QUADS, 131072, false, false,
				() -> {
					NO_TEXTURE.setupRenderState();
					TRANSLUCENT_TRANSPARENCY.setupRenderState();
					NO_CULL.setupRenderState();
				},
				() -> {
					NO_TEXTURE.clearRenderState();
					TRANSLUCENT_TRANSPARENCY.clearRenderState();
					NO_CULL.clearRenderState();
				});
	}
}
