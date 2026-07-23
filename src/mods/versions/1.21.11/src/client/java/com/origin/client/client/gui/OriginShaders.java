package com.origin.client.client.gui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The scalable (SDF/MSDF) rendering backend for the Origin menus.
 *
 * PER-VERSION DELTA (1.21.11): unlike 1.21.1/1.21.4 (which compile a named
 * {@code ShaderInstance}/{@code CompiledShaderProgram} up front and bind it for
 * an immediate-mode {@code BufferUploader.drawWithShader} call), 1.21.11's GUI
 * renders through a fully DEFERRED model — {@link net.minecraft.client.gui.GuiGraphics}
 * queues {@link net.minecraft.client.gui.render.state.GuiElementRenderState}
 * instances into {@code guiGraphics.guiRenderState}, and the real renderer
 * batches/draws them later. There is no {@code ShaderManager.getProgram(key)}
 * returning a bindable program anymore — custom shaders are declared as a
 * {@link RenderPipeline} (vertex/fragment shader identifiers + vertex format +
 * blend/depth state), and a draw is just "build a {@code GuiElementRenderState}
 * naming this pipeline and submit it" (see {@link OriginSdfFont}). The pipeline
 * itself doesn't get "compiled" synchronously the way the old ShaderInstance did
 * — GLSL compilation happens lazily inside the renderer the first time the
 * pipeline is actually drawn with, so unlike 1.21.1/1.21.4 there is no
 * null-checkable "did it compile" signal here; MSDF is considered available the
 * moment the pipeline object is built without throwing.
 *
 * {@code rendertype_origin_msdf} reuses vanilla's own {@code core/position_tex_color}
 * vertex shader verbatim (identical math: project Position, pass UV0 + Color
 * through) — only the fragment shader (the MSDF median/coverage calculation) is
 * ours.
 *
 * NOT YET PORTED on 1.21.11: the rounded-box SDF panel ({@code rendertype_origin_round})
 * and the fullscreen color-grade ({@code rendertype_origin_grade}). Both need more
 * than a retarget — ROUND's old shader took per-draw uniforms (RectHalf/Radius/
 * Border/FillColor/BorderColor) that have no equivalent hook in the new deferred
 * model (only per-vertex data reaches the renderer), so it needs a custom
 * VertexFormat encoding those params per-vertex instead of uniforms; GRADE needs
 * an actual render-target texture copy, a different problem than drawing a GUI
 * element. Both stay null here — every call site already fails soft to the
 * existing software path (OriginUi's software rounded-panel, no color grade)
 * exactly as it does today. Tracked as follow-up work.
 */
public final class OriginShaders {
	/** Custom MSDF-text render pipeline. Vertex format matches the atlas quads
	 *  OriginSdfFont builds: screen position + atlas UV + per-vertex tint. */
	public static final RenderPipeline MSDF = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath("originclient", "pipeline/origin_msdf"))
					.withFragmentShader(Identifier.fromNamespaceAndPath("originclient", "core/rendertype_origin_msdf"))
					.withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
					.build());

	/** Rounded-box SDF panel pipeline — not yet ported (see class doc). Every
	 *  caller checks {@link #roundActive()} first, which is permanently false
	 *  while this is null, so the software rounded-panel path keeps working. */
	public static final RenderPipeline ROUND = null;

	private static boolean registered = false;
	private static boolean warnedRoundFallback = false;

	private OriginShaders() {
	}

	/** Kept for parity with 1.21.1/1.21.4's init call shape — the pipelines above
	 *  are already built as static fields (constructing a RenderPipeline is cheap
	 *  object assembly, not a GL compile), so this just logs readiness. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: MSDF text render pipeline registered "
				+ "(rounded-box SDF panel + color-grade not yet ported on 1.21.11 — using software fallbacks).");
	}

	/** Vector text is always on (matches 1.21.1/1.21.4 — the user-facing toggle
	 *  was removed there); the round-panel path still fails soft via ROUND==null. */
	public static boolean enabled() {
		return true;
	}

	/** Rounded-box SDF path is usable. Permanently false until ROUND is ported. */
	public static boolean roundActive() {
		if (enabled() && ROUND == null && !warnedRoundFallback) {
			warnedRoundFallback = true;
			OriginClient.LOGGER.info("Origin: rounded-box SDF panel not yet ported on 1.21.11 — "
					+ "using the software rounded-rect fallback.");
		}
		return false;
	}
}
