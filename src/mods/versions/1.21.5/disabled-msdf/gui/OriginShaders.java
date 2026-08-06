package com.origin.client.client.gui;

import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Core-shader loader for this module. Only the MSDF text shader is registered
 * — this module has no Color Saturation feature, so GRADE isn't needed.
 * Registered through Fabric's CoreShaderRegistrationCallback (the supported
 * hook; no resource-pack injection). Fails soft: if it doesn't compile, MSDF
 * stays null and every caller falls back to the vanilla-font path via
 * OriginSdfFont.ready(). Ported from the 1.21.1 baseline.
 */
public final class OriginShaders {
	private OriginShaders() {
	}

	public static ShaderInstance MSDF;

	private static boolean registered = false;
	private static boolean warnedTextFallback = false;

	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			try {
				context.register(ResourceLocation.fromNamespaceAndPath("originclient", "rendertype_origin_msdf"),
						com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX_COLOR,
						shader -> {
							MSDF = shader;
							OriginClient.LOGGER.info("Origin: MSDF text shader compiled + loaded OK.");
						});
			} catch (Throwable t) {
				OriginClient.LOGGER.warn("Origin: MSDF text shader failed to register", t);
			}
		});
	}

	/**
	 * MSDF vector text is OFF on 1.21.5 — the port is unfinished, not broken.
	 *
	 * <p>The Aug 2 pass brought the 1.21.1 implementation across verbatim, but
	 * 1.21.5 deleted the immediate-mode submission it relies on
	 * ({@code RenderSystem.setShader}, {@code enableBlend}/{@code defaultBlendFunc}),
	 * so that module simply did not compile. 1.21.6's replacement doesn't port
	 * back either: 1.21.5 has {@code RenderPipeline} but not the
	 * {@code RenderPipelines} registry that arrives with it.
	 *
	 * <p>Returning false here keeps the module compiling and shipping with the
	 * vanilla font — exactly what the released 1.21.5 build does today, so this is
	 * a held position rather than a regression. Panels, buttons and icons are all
	 * anti-aliased on 1.21.5 regardless; those don't go through a shader.
	 * Flip this back to true once {@link OriginSdfFont#draw} has a real 1.21.5
	 * submission path (see the note at its call site).
	 */
	public static boolean enabled() {
		return false;
	}

	/** Called by OriginSdfFont when text falls back to vanilla, so a non-loading
	 *  shader is visible in the log instead of silently reverting. */
	public static void noteTextFallback() {
		if (!warnedTextFallback) {
			warnedTextFallback = true;
			OriginClient.LOGGER.warn("Origin: SDF is ON but the MSDF text shader/atlas did NOT load — "
					+ "using the vanilla-font fallback. Check the log above for a shader compile or resource error.");
		}
	}

	/**
	 * Restore GL state after an immediate-mode custom-shader draw. Without this,
	 * blend + shader colour left set by the GUI draw leak into the next draw and,
	 * once the screen closes, into world/sky rendering (the black-sky bug).
	 */
	public static void restoreState() {
		// 1.21.5 removed RenderSystem.disableBlend/defaultBlendFunc — blend state
		// belongs to the RenderPipeline in this era. Only the shader colour is
		// still global, so that is all there is to reset.
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
