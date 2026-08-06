package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;

/**
 * The scalable (MSDF) text rendering backend for the Origin menus.
 *
 * PER-VERSION DELTA (1.21.4): unlike 1.21.1 (which registers through Fabric's
 * CoreShaderRegistrationCallback), 1.21.4 has no such Fabric hook — but it does
 * NOT need one. Its {@link net.minecraft.client.renderer.ShaderManager} compiles
 * ANY {@link ShaderProgram} on demand: hand it a program key (config id + vertex
 * format + defines) and {@code getProgram} loads/links/caches the GLSL straight
 * from our {@code shaders/core/*.json + *.vsh/*.fsh}. So registration is skipped
 * entirely and the compiled program is pulled off the live shader manager the
 * first time we draw ({@link #ensureCompiled()}). Confirmed working pattern from
 * an earlier 1.21.4 SDF port (commit 9fefe47).
 *
 * Fails soft: if the shader doesn't compile, MSDF stays null and every call site
 * falls back to the vanilla-font path.
 */
public final class OriginShaders {
	public static CompiledShaderProgram MSDF;

	private static final ShaderProgram MSDF_KEY = new ShaderProgram(
			ResourceLocation.fromNamespaceAndPath("originclient", "core/rendertype_origin_msdf"),
			DefaultVertexFormat.POSITION_TEX_COLOR, ShaderDefines.EMPTY);

	private static boolean registered = false;
	private static boolean compiled = false;
	private static boolean broken = false;
	private static boolean warnedTextFallback = false;

	private OriginShaders() {
	}

	/** Call once from client init. The real work is deferred to
	 *  {@link #ensureCompiled()} on the render thread — no shader manager exists
	 *  yet at client-init time. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: MSDF text shader will compile on first draw (on-demand via ShaderManager).");
	}

	/**
	 * Compile + populate MSDF the first time a draw path needs it. MUST run on
	 * the render thread — the live ShaderManager only exists once the client is
	 * up, and getProgram touches GL. Fails soft: any Throwable nulls the field
	 * and latches {@code broken} so we never retry-thrash.
	 */
	public static void ensureCompiled() {
		if (compiled || broken) {
			return;
		}
		compiled = true;
		try {
			var mgr = Minecraft.getInstance().getShaderManager();
			// getProgram returns null (not throws) on a compile/link failure — it
			// catches CompilationException internally and logs it.
			MSDF = mgr.getProgram(MSDF_KEY);
			if (MSDF == null) {
				OriginClient.LOGGER.warn("Origin: MSDF text shader did not compile — using the vanilla-font fallback.");
			} else {
				OriginClient.LOGGER.info("Origin: MSDF text shader compiled + loaded OK.");
			}
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
