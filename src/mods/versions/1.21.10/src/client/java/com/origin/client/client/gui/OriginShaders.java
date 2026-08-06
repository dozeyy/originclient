package com.origin.client.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom render pipelines for this module: the full-screen colour GRADE
 * (carried over from the 1.21.11 baseline) and the MSDF text pipeline (ported
 * 2026-08-02, Will: menu text needs to match 1.21.1's smoothness). The
 * rounded-box SDF panel pipeline is still not carried over — OriginUi's
 * baked-texture panel() already renders rounded corners without it.
 *
 * Built as a static field: constructing a RenderPipeline is cheap and lazy (the
 * GPU compiles it the first time something draws with it), so there is no
 * class-load cost and nothing to register at init.
 */
public final class OriginShaders {
	private OriginShaders() {
	}

	public static final RenderPipeline GRADE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/origin_grade"))
					.withFragmentShader(ResourceLocation.fromNamespaceAndPath("originclient", "core/rendertype_origin_grade"))
					.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
					.withoutBlend()
					.build());

	/** Custom MSDF-text render pipeline. Vertex format matches the atlas quads
	 *  OriginSdfFont builds: screen position + atlas UV + per-vertex tint. */
	public static final RenderPipeline MSDF = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(ResourceLocation.fromNamespaceAndPath("originclient", "pipeline/origin_msdf"))
					.withFragmentShader(ResourceLocation.fromNamespaceAndPath("originclient", "core/rendertype_origin_msdf"))
					.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
					.build());

	/** Vector text is always attempted; OriginSdfFont.ready() gates whether the
	 *  atlas + pipeline actually loaded, and every caller fails soft to vanilla. */
	public static boolean enabled() {
		return true;
	}
}
