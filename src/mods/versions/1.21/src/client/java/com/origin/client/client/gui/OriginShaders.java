package com.origin.client.client.gui;

import com.origin.client.OriginClient;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/**
 * Core-shader loader for this module. Registered through Fabric's
 * CoreShaderRegistrationCallback (the supported hook; no resource-pack
 * injection). Each shader fails soft on its own — if it doesn't compile, its
 * field stays null and every call site falls back to the software path
 * (Color Saturation no-ops; menu text uses the vanilla-font path via
 * OriginText/OriginSdfFont.ready()).
 *
 * MSDF text ported from the 1.21.1 baseline 2026-08-02 (Will: menu text needs
 * to match 1.21.1's smoothness). The rounded-box SDF panel shader is still not
 * carried over — OriginUi's baked-texture panel() already renders rounded
 * corners without it.
 */
public final class OriginShaders {
	private OriginShaders() {
	}

	public static ShaderInstance GRADE;
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
				context.register(ResourceLocation.fromNamespaceAndPath("originclient", "rendertype_origin_grade"),
						com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_TEX,
						shader -> {
							GRADE = shader;
							OriginClient.LOGGER.info("Origin: colour-grade shader compiled + loaded OK.");
						});
			} catch (Throwable t) {
				OriginClient.LOGGER.warn("Origin: colour-grade shader failed to register", t);
			}
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
