package com.origin.client.client.gui;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

import java.util.function.UnaryOperator;

/**
 * Origin's custom render pipelines for the menus (MSDF text, SDF icon glyphs)
 * and the Color Saturation grade.
 *
 * <p>The draw model is 1.21.11's: a draw is a
 * {@link net.minecraft.client.renderer.state.gui.GuiElementRenderState} naming
 * one of these pipelines, submitted into {@code g.guiRenderState} and drawn
 * later by the GUI renderer (see {@link OriginSdfFont}, {@link ModIcons},
 * {@link OriginUi}, {@code render/ColorGrade}).
 *
 * <p>PER-VERSION DELTA (26.x): RenderPipeline lost {@code withVertexFormat},
 * {@code withBlend} and {@code withSampler} -- pipelines now carry bind-group
 * layouts, colour-target states and a primitive topology. So every pipeline here
 * is DERIVED from a vanilla one: its whole configuration is read back through
 * the public getters and copied into a fresh builder, then only the location,
 * fragment shader and (for GRADE) the colour target change. Copying the
 * bind-group layouts is the critical part: they declare the DynamicTransforms
 * UBO and Sampler0 the vertex shader needs (an empty builder omits them and the
 * driver reports "unknown and unsupported uniform DynamicTransforms" -- found by
 * the 26.2 staged port, 2026-07-30).
 *
 * <p>Built LAZILY on first use, never at class-load: this class is first touched
 * from mod init, before the vanilla pipelines it copies are guaranteed to exist.
 * A build failure latches that pipeline off (null) for the session and every
 * caller falls back (vanilla font, mask icons, no grade) -- never a crash.
 * No RenderPipelines.register: the GPU device compiles a pipeline on its first
 * draw (proven by the staged module's GRADE/MSDF boots).
 */
public final class OriginShaders {
	private static RenderPipeline msdf, icon, grade;
	private static boolean msdfBroken, iconBroken, gradeBroken;
	private static boolean registered = false;

	private OriginShaders() {
	}

	/** MSDF text pipeline (Inter menu font), or null -> vanilla font. */
	public static RenderPipeline msdf() {
		if (msdf == null && !msdfBroken && RenderPipelines.GUI_TEXTURED != null) {
			try {
				msdf = derive(RenderPipelines.GUI_TEXTURED, "pipeline/origin_msdf", "core/rendertype_origin_msdf", null);
			} catch (Throwable t) {
				msdfBroken = true;
				OriginClient.LOGGER.warn("Origin: MSDF text pipeline build failed; menus use the vanilla font", t);
			}
		}
		return msdf;
	}

	/** Single-channel SDF icon pipeline (mod icons, close/chevron/edit glyphs), or null -> mask atlas. */
	public static RenderPipeline icon() {
		if (icon == null && !iconBroken && RenderPipelines.GUI_TEXTURED != null) {
			try {
				icon = derive(RenderPipelines.GUI_TEXTURED, "pipeline/origin_icon", "core/rendertype_origin_icon", null);
			} catch (Throwable t) {
				iconBroken = true;
				OriginClient.LOGGER.warn("Origin: icon SDF pipeline build failed; icons use the mask atlas", t);
			}
		}
		return icon;
	}

	/**
	 * Full-screen colour grade (Color Saturation). NO BLEND: the grade REPLACES
	 * the pixels it covers (it draws back a graded copy of the same image), so a
	 * blending colour target would double-expose the frame.
	 */
	public static RenderPipeline grade() {
		if (grade == null && !gradeBroken && RenderPipelines.GUI_TEXTURED != null) {
			try {
				grade = derive(RenderPipelines.GUI_TEXTURED, "pipeline/origin_grade", "core/rendertype_origin_grade",
						b -> b.withColorTargetState(ColorTargetState.DEFAULT));
			} catch (Throwable t) {
				gradeBroken = true;
				OriginClient.LOGGER.warn("Origin: colour-grade pipeline build failed; Color Saturation disabled this session", t);
			}
		}
		return grade;
	}

	/**
	 * Copies {@code base}'s whole configuration into a new pipeline at
	 * {@code originclient:<location>}, with Origin's fragment shader when one is
	 * given (null keeps the base's), then lets {@code tweak} override anything.
	 * Shared with render/LunarPipelines and render/OriginRenderTypes.
	 */
	public static RenderPipeline derive(RenderPipeline base, String location, String fragmentShader,
			UnaryOperator<RenderPipeline.Builder> tweak) {
		// 26.3: shaders are a ShaderType -> Identifier map, the colour targets and
		// vertex bindings are lists, and pipelines carry a push-constant size.
		RenderPipeline.Builder b = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("originclient", location))
				.withVertexShader(base.getShaders().get(ShaderType.VERTEX))
				.withFragmentShader(fragmentShader == null ? base.getShaders().get(ShaderType.FRAGMENT)
						: Identifier.fromNamespaceAndPath("originclient", fragmentShader))
				.withPrimitiveTopology(base.getPrimitiveTopology())
				.withPolygonMode(base.getPolygonMode())
				// GUI pipelines have NO depth-stencil state (null); the non-Optional
				// overload NPEs on null (Optional.of), so route through the Optional one.
				.withDepthStencilState(java.util.Optional.ofNullable(base.getDepthStencilState()))
				.withCull(base.isCull())
				.withPushConstantSize(base.pushConstantSize());
		java.util.List<ColorTargetState> targets = base.getColorTargetStates();
		for (int i = 0; i < targets.size(); i++) {
			if (targets.get(i) != null) {
				b.withColorTargetState(i, targets.get(i));
			} else {
				b.withUnusedColorTargetState(i);
			}
		}
		java.util.List<VertexFormat> bindings = base.getVertexFormatBindings();
		for (int i = 0; i < bindings.size(); i++) {
			if (bindings.get(i) != null) {
				b.withVertexBinding(i, bindings.get(i));
			}
		}
		for (BindGroupLayout layout : base.getBindGroupLayouts()) {
			b.withBindGroupLayout(layout);
		}
		var defines = base.getShaderDefines();
		if (defines != null) {
			for (var e : defines.values().entrySet()) {
				try {
					b.withShaderDefine(e.getKey(), Integer.parseInt(e.getValue()));
				} catch (NumberFormatException notInt) {
					b.withShaderDefine(e.getKey(), Float.parseFloat(e.getValue()));
				}
			}
			for (String flag : defines.flags()) {
				b.withShaderDefine(flag);
			}
		}
		if (tweak != null) {
			b = tweak.apply(b);
		}
		return b.build();
	}

	/** Kept for the 1.21.x init call shape; the pipelines build on first use. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: MSDF text, icon SDF and colour-grade pipelines build on first use (26.2).");
	}

	/** Vector text is always on (the user-facing toggle was removed on 1.21.1). */
	public static boolean enabled() {
		return true;
	}

	/** Rounded-box SDF panel path: never on in the deferred-GUI eras (OriginUi's
	 *  software per-pixel path draws the rounded boxes). */
	public static boolean roundActive() {
		return false;
	}
}
