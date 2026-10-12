package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * The render pipelines the Lunar-parity mods need on this era, in one place.
 *
 * 1.21.1 set blend / depth state on RenderSystem around an immediate draw. From
 * 1.21.5 that state lives IN the RenderPipeline, so each distinct state is its
 * own registered pipeline. All are built from vanilla snippets (verified
 * against decompiled 1.21.11 RenderPipelines) and opened to us by Fabric API
 * 0.141.4's transitive access wideners (RenderPipelines.register, the *_SNIPPET
 * fields, RenderType.create are all listed there by their Mojang names).
 *
 *   GUI_ADDITIVE  -- GUI textured quads added as light (SRC_ALPHA, ONE): the
 *                    Saturation overlay's glowing shanks (1.21.1 used exactly
 *                    that blendFunc).
 *   GUI_GLINT     -- GUI textured quads with vanilla's glint blend
 *                    (SRC_COLOR, ONE): the Shinypots shine.
 *   GLINT_BLENDED -- vanilla's world glint pipeline (core/glint, EQUAL depth,
 *                    no depth write) with a normal alpha blend that leaves dst
 *                    alpha alone: Glint Colorizer's "Lunar Blending" (additive
 *                    light can only brighten, so dark colours would vanish).
 *   CUI_HIDDEN    -- WorldEdit CUI's see-through pass: Origin's overlay quads
 *                    with the depth test flipped to GREATER, so they draw ONLY
 *                    where terrain is in front.
 *
 * register() is called once at client init so every pipeline exists before the
 * first resource reload precompiles the registered set.
 */
public final class LunarPipelines {
	private LunarPipelines() {
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("originclient", "pipeline/" + path);
	}

	public static final RenderPipeline GUI_ADDITIVE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(id("gui_textured_additive"))
					.withBlend(BlendFunction.LIGHTNING)
					.build());

	public static final RenderPipeline GUI_GLINT = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(id("gui_glint"))
					.withBlend(BlendFunction.GLINT)
					.build());

	// Vanilla GLINT verbatim except the blend (ENTITY_OUTLINE_BLIT = SRC_ALPHA,
	// ONE_MINUS_SRC_ALPHA for colour, ZERO/ONE for alpha -- 1.21.1's Lunar blend).
	public static final RenderPipeline GLINT_BLENDED = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET, RenderPipelines.FOG_SNIPPET,
							RenderPipelines.GLOBALS_SNIPPET)
					.withLocation(id("glint_blended"))
					.withVertexShader("core/glint")
					.withFragmentShader("core/glint")
					.withSampler("Sampler0")
					.withDepthWrite(false)
					.withCull(false)
					.withDepthTestFunction(DepthTestFunction.EQUAL_DEPTH_TEST)
					.withBlend(BlendFunction.ENTITY_OUTLINE_BLIT)
					.withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
					.build());

	public static final RenderPipeline CUI_HIDDEN_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(id("cui_hidden_quads"))
					.withCull(false)
					.withDepthTestFunction(DepthTestFunction.GREATER_DEPTH_TEST)
					.withDepthWrite(false)
					.build());

	public static final RenderType CUI_HIDDEN = RenderType.create("originclient_cui_hidden_quads",
			RenderSetup.builder(CUI_HIDDEN_PIPELINE).createRenderSetup());

	/** Class-load trigger (the fields above do the work). */
	public static void register() {
	}
}
