package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * The scalable (MSDF) text rendering backend for the Origin menus.
 *
 * PER-VERSION DELTA (1.17.1): this Fabric API version (0.46.1+1.17) has no
 * {@code CoreShaderRegistrationCallback} — that hook was added later. Vanilla's
 * OWN {@link ShaderInstance} constructor loads + compiles a shader directly (it's
 * how {@code GameRenderer} bootstraps its own core shaders), so we call it
 * ourselves the first time a draw needs the program, on the render thread where
 * a GL context exists. Fails soft: any IOException/Throwable leaves MSDF null
 * and every call site falls back to the vanilla-font path.
 */
public final class OriginShaders {
	public static ShaderInstance MSDF;

	private static boolean registered = false;
	private static boolean compiled = false;
	private static boolean broken = false;
	private static boolean warnedTextFallback = false;

	private OriginShaders() {
	}

	/** Call once from client init. The real work is deferred to
	 *  {@link #ensureCompiled()} on the render thread — no GL context exists
	 *  yet at client-init time. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: MSDF text shader will compile on first draw.");
	}

	/**
	 * Compile + populate MSDF the first time a draw path needs it. MUST run on
	 * the render thread. Fails soft: any Throwable nulls the field and latches
	 * {@code broken} so we never retry-thrash.
	 */
	public static void ensureCompiled() {
		if (compiled || broken) {
			return;
		}
		compiled = true;
		try {
			MSDF = new ShaderInstance(Minecraft.getInstance().getResourceManager(),
					"originclient:rendertype_origin_msdf",
					DefaultVertexFormat.POSITION_TEX_COLOR);
			OriginClient.LOGGER.info("Origin: MSDF text shader compiled + loaded OK.");
		} catch (Throwable t) {
			broken = true;
			MSDF = null;
			OriginClient.LOGGER.warn("Origin: MSDF text shader compile failed — using the vanilla-font fallback.", t);
		}
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
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
