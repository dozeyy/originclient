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
				context.register(new ResourceLocation("originclient", "rendertype_origin_msdf"),
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

	/** Vector text is always attempted; OriginSdfFont.ready() gates whether the
	 *  atlas + shader actually loaded, and every caller fails soft to vanilla. */
	public static boolean enabled() {
		return true;
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
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
		com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
