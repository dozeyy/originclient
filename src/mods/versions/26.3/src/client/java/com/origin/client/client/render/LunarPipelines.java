package com.origin.client.client.render;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.origin.client.client.gui.OriginShaders;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

import java.util.function.Supplier;

/**
 * The Lunar-parity mods' own pipelines: Saturation's additive heart overlay,
 * Shinypots' GUI glint, Glint Colorizer's alpha-blended glint and the WorldEdit
 * CUI's see-through pass.
 *
 * PER-VERSION DELTA (26.x): pipelines are DERIVED from the vanilla ones
 * (OriginShaders.derive -- the 26.x builder has no withBlend/withVertexFormat/
 * withSampler) and built lazily on first use; each getter returns null if its
 * build failed, and every caller then keeps vanilla's look.
 */
public final class LunarPipelines {
	private LunarPipelines() {
	}

	private static final class Lazy<T> {
		private final Supplier<T> make;
		private T value;
		private boolean broken;

		Lazy(Supplier<T> make) {
			this.make = make;
		}

		T get() {
			if (value == null && !broken) {
				try {
					value = make.get();
				} catch (Throwable t) {
					broken = true;
					com.origin.client.OriginClient.LOGGER.warn("Origin: a Lunar-parity pipeline failed to build", t);
				}
			}
			return value;
		}
	}

	private static final Lazy<RenderPipeline> GUI_ADDITIVE = new Lazy<>(() ->
			OriginShaders.derive(RenderPipelines.GUI_TEXTURED, "pipeline/gui_textured_additive", null,
					b -> b.withColorTargetState(new ColorTargetState(BlendFunction.LIGHTNING))));

	private static final Lazy<RenderPipeline> GUI_GLINT = new Lazy<>(() ->
			OriginShaders.derive(RenderPipelines.GUI_TEXTURED, "pipeline/gui_glint", null,
					b -> b.withColorTargetState(new ColorTargetState(BlendFunction.GLINT))));

	// Vanilla GLINT verbatim except the blend (ENTITY_OUTLINE_BLIT = SRC_ALPHA,
	// ONE_MINUS_SRC_ALPHA for colour -- 1.21.1's Lunar blend).
	private static final Lazy<RenderPipeline> GLINT_BLENDED = new Lazy<>(() ->
			OriginShaders.derive(RenderPipelines.GLINT, "pipeline/glint_blended", null,
					b -> b.withColorTargetState(new ColorTargetState(BlendFunction.ENTITY_OUTLINE_BLIT))));

	// Overlay quads with the depth test flipped: fragments draw only where terrain
	// is in front of them (the CUI's see-through pass). 26.3 is reversed-Z (far =
	// 0.0, see OriginRenderTypes), so "terrain in front" is LESS_THAN here.
	private static final Lazy<RenderType> CUI_HIDDEN = new Lazy<>(() -> RenderType.create("originclient_cui_hidden_quads",
			RenderSetup.builder(OriginShaders.derive(RenderPipelines.DEBUG_QUADS, "pipeline/cui_hidden_quads", null,
					b -> b.withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN, false))))
					.createRenderSetup()));

	/** Additive GUI blit (Saturation's overlay), or GUI_TEXTURED if unavailable. */
	public static RenderPipeline guiAdditive() {
		RenderPipeline p = GUI_ADDITIVE.get();
		return p != null ? p : RenderPipelines.GUI_TEXTURED;
	}

	/** Glint-blended GUI pipeline (Shinypots), or null. */
	public static RenderPipeline guiGlint() {
		return GUI_GLINT.get();
	}

	/** Vanilla glint with an alpha blend (Glint Colorizer's Lunar Blending), or null. */
	public static RenderPipeline glintBlended() {
		return GLINT_BLENDED.get();
	}

	/** The WorldEdit CUI's see-through quads, or null. */
	public static RenderType cuiHidden() {
		return CUI_HIDDEN.get();
	}

	/** Kept for the 1.21.x init call shape (everything builds on first use). */
	public static void register() {
	}
}
