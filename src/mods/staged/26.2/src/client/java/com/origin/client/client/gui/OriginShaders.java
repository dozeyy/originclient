package com.origin.client.client.gui;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Origin's custom GUI render pipelines for 26.2. Right now this holds only
 * {@link #GRADE} (the Color Saturation full-screen grade); the MSDF text / icon
 * pipelines from the 1.21.x modules are NOT ported yet and are intentionally left
 * out — registering a pipeline whose shader assets don't exist would fail at first
 * use, and the SDF text feature is a separate later port.
 *
 * <p>PER-VERSION DELTA (26.2): the 1.21.x builder path is gone here —
 * {@code RenderPipelines.register} and {@code GUI_TEXTURED_SNIPPET} both went
 * private, and {@code RenderPipeline} has no {@code toBuilder()}. So GRADE is
 * DERIVED from the public {@link RenderPipelines#GUI_TEXTURED} pipeline: every
 * config value is read back off it through its public getters and copied into a
 * fresh builder, overriding ONLY the location and the fragment shader. Copying the
 * bind-group layouts is the critical part — they declare the {@code DynamicTransforms}
 * UBO and {@code Sampler0} the GUI vertex shader needs; an empty builder omits them
 * and the driver reports "unknown and unsupported uniform DynamicTransforms" (caught
 * in a runClient boot 2026-07-30). No central registration is needed — the GPU
 * device compiles the pipeline on demand the first time the grade element is drawn.
 */
public final class OriginShaders {

	// The colour-grade pipeline, built LAZILY on first use. It must NOT be built at
	// class-load: OriginShaders is first touched from onInitializeClient (mod init),
	// and RenderPipelines.GUI_TEXTURED — the base we clone — is still null that early
	// (the vanilla GUI pipelines are populated later in client startup). Referencing
	// it then NPEs the static initializer and aborts the whole mod (caught in a
	// runClient boot 2026-07-30). By the first in-world draw, GUI_TEXTURED is set.
	private static RenderPipeline grade;
	private static boolean gradeBroken = false;

	// MSDF text pipeline (Inter menu font). Same lazy + derive-from-GUI_TEXTURED shape
	// as GRADE — see grade() for why it can't be built at class-load.
	private static RenderPipeline msdf;
	private static boolean msdfBroken = false;

	/** The MSDF text pipeline, or null if it isn't ready (caller falls back to the
	 *  vanilla font). Built once, then cached. */
	public static RenderPipeline msdf() {
		if (msdf == null && !msdfBroken && RenderPipelines.GUI_TEXTURED != null) {
			try {
				msdf = derive(Identifier.fromNamespaceAndPath("originclient", "pipeline/origin_msdf"),
						Identifier.fromNamespaceAndPath("originclient", "core/rendertype_origin_msdf"));
			} catch (Throwable t) {
				msdfBroken = true;
				OriginClient.LOGGER.warn("Origin: MSDF text pipeline build failed; menus use the vanilla font", t);
			}
		}
		return msdf;
	}

	private static boolean registered = false;

	private OriginShaders() {
	}

	/** The grade pipeline, or null if the base GUI pipeline isn't ready yet (the
	 *  caller — ColorGrade — skips grading for that frame). Retries until the base
	 *  is populated, then builds once and caches. In practice the first in-world
	 *  call succeeds, since GUI_TEXTURED is set well before any world renders. */
	public static RenderPipeline grade() {
		if (grade == null && !gradeBroken && RenderPipelines.GUI_TEXTURED != null) {
			try {
				grade = derive(Identifier.fromNamespaceAndPath("originclient", "pipeline/origin_grade"),
						Identifier.fromNamespaceAndPath("originclient", "core/rendertype_origin_grade"));
			} catch (Throwable t) {
				// Fail-soft (mandate: degrade to vanilla, never crash). grade() is
				// called from ColorGrade's guard, OUTSIDE its try/catch, so a build
				// failure here would take the frame down — latch it off instead.
				gradeBroken = true;
				OriginClient.LOGGER.warn("Origin: colour-grade pipeline build failed; Color Saturation disabled this session", t);
			}
		}
		return grade;
	}

	// Clone GUI_TEXTURED's whole configuration, swapping in Origin's location +
	// fragment shader. Reading vertex shader / vertex bindings / topology / blend /
	// depth / cull / bind-group layouts off the base guarantees the quad binds
	// exactly what the vanilla GUI textured path binds. Shared by GRADE and MSDF.
	private static RenderPipeline derive(Identifier location, Identifier fragmentShader) {
		RenderPipeline base = RenderPipelines.GUI_TEXTURED;
		RenderPipeline.Builder b = RenderPipeline.builder()
				.withLocation(location)
				.withVertexShader(base.getVertexShader())
				.withFragmentShader(fragmentShader)
				.withPrimitiveTopology(base.getPrimitiveTopology())
				.withColorTargetState(base.getColorTargetState())
				// GUI_TEXTURED has NO depth-stencil state (GUI doesn't depth-test), so
				// getDepthStencilState() is null; the non-Optional overload NPEs on
				// null (Optional.of), so route through the Optional overload.
				.withDepthStencilState(java.util.Optional.ofNullable(base.getDepthStencilState()))
				.withCull(base.isCull());
		VertexFormat[] bindings = base.getVertexFormatBindings();
		for (int i = 0; i < bindings.length; i++) {
			b.withVertexBinding(i, bindings[i]);
		}
		for (BindGroupLayout layout : base.getBindGroupLayouts()) {
			b.withBindGroupLayout(layout);
		}
		return b.build();
	}

	/** Building the pipeline above is cheap object assembly (not a GL compile), so
	 *  this just logs readiness — kept for call-shape parity with the 1.21.x modules. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: colour-grade render pipeline ready (26.2).");
	}
}
