package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;

/**
 * The scalable (SDF/MSDF) rendering backend for the Origin menus.
 *
 * Three custom core shaders replace the fixed-resolution bitmap text +
 * CPU-coverage rounded rects + no color grade:
 *
 *   * {@code rendertype_origin_msdf} — multi-channel signed-distance-field text
 *     (see {@link OriginSdfFont}); crisp at any scale like web vector text.
 *   * {@code rendertype_origin_round} — a rounded-box SDF for panels/cards.
 *   * {@code rendertype_origin_grade} — a fullscreen scene color-grade.
 *
 * PER-VERSION DELTA (1.21.4): unlike 1.21.1 (which registered these through
 * Fabric's {@code CoreShaderRegistrationCallback}), 1.21.4 has no such Fabric
 * hook — but it does NOT need one. Its {@link net.minecraft.client.renderer.ShaderManager}
 * compiles ANY {@link ShaderProgram} on demand: hand it a program key (config id
 * + vertex format + defines) and {@code getProgram} loads/links/caches the GLSL
 * straight from our {@code shaders/core/*.json + *.vsh/*.fsh}. So we skip the
 * (missing) registration callback entirely and just pull the compiled programs
 * off the live shader manager the first time we draw ({@link #ensureCompiled()}).
 *
 * Everything is gated so it can NEVER break the UI (mandate 4): if the shaders
 * fail to compile/load, {@code MSDF}/{@code ROUND}/{@code GRADE} stay null and
 * every call site falls back to the software path.
 *
 * State discipline: the immediate-mode draws these shaders back MUST call
 * {@link #restoreState()} afterwards, or the last GUI frame's blend/shader-color
 * leaks into world rendering (that was the broken-sky bug).
 */
public final class OriginShaders {
	public static CompiledShaderProgram MSDF;
	public static CompiledShaderProgram ROUND;
	/** Fullscreen scene color-grade (Color Saturation), invoked by ColorGrade with
	 *  our own GL-state discipline instead of a vanilla PostChain. */
	public static CompiledShaderProgram GRADE;

	// The three program keys. A ShaderProgram is (config ResourceLocation, vertex
	// format, defines). The config id is the shaders/core/*.json path minus the
	// "shaders/" prefix and ".json" suffix — i.e. "core/rendertype_origin_<name>".
	private static final ShaderProgram MSDF_KEY = new ShaderProgram(
			ResourceLocation.fromNamespaceAndPath("originclient", "core/rendertype_origin_msdf"),
			DefaultVertexFormat.POSITION_TEX_COLOR, ShaderDefines.EMPTY);
	private static final ShaderProgram ROUND_KEY = new ShaderProgram(
			ResourceLocation.fromNamespaceAndPath("originclient", "core/rendertype_origin_round"),
			DefaultVertexFormat.POSITION_TEX, ShaderDefines.EMPTY);
	private static final ShaderProgram GRADE_KEY = new ShaderProgram(
			ResourceLocation.fromNamespaceAndPath("originclient", "core/rendertype_origin_grade"),
			DefaultVertexFormat.POSITION_TEX, ShaderDefines.EMPTY);

	private static boolean registered = false;
	// One-shot latch for the render-thread compile. `compiled` = attempted;
	// `broken` = the attempt failed and we stay on the software fallback.
	private static boolean compiled = false;
	private static boolean broken = false;
	private static boolean warnedTextFallback = false;
	private static boolean warnedRoundFallback = false;

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
		OriginClient.LOGGER.info("Origin: scalable core shaders will compile on first draw "
				+ "(MSDF text + rounded-box SDF + color-grade, on-demand via ShaderManager).");
	}

	/**
	 * Compile + populate MSDF/ROUND/GRADE the first time a draw path needs them.
	 * MUST run on the render thread — the live {@link net.minecraft.client.renderer.ShaderManager}
	 * only exists once the client is up, and {@code getProgram} touches GL. Fails
	 * soft: any Throwable nulls the fields and latches {@code broken} so we never
	 * retry-thrash, and every call site drops to its software fallback.
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
			ROUND = mgr.getProgram(ROUND_KEY);
			GRADE = mgr.getProgram(GRADE_KEY);
			if (MSDF == null || ROUND == null || GRADE == null) {
				OriginClient.LOGGER.warn("Origin: one or more core shaders did not compile "
						+ "(MSDF={}, ROUND={}, GRADE={}) — those paths use the software fallback.",
						MSDF != null, ROUND != null, GRADE != null);
			} else {
				OriginClient.LOGGER.info("Origin: scalable core shaders compiled + loaded OK "
						+ "(MSDF text + rounded-box SDF + color-grade).");
			}
		} catch (Throwable t) {
			broken = true;
			MSDF = null;
			ROUND = null;
			GRADE = null;
			OriginClient.LOGGER.warn("Origin: core-shader compile failed — using the software "
					+ "text/panel/grade fallbacks.", t);
		}
	}

	/** Vector text + curves are ALWAYS on now (Will): the user-facing toggle was
	 *  removed, so this is unconditionally true. The round/MSDF paths still fail
	 *  soft on their own if a shader doesn't compile (roundActive / ready()). */
	public static boolean enabled() {
		return true;
	}

	/** Rounded-box SDF path is usable. Restricted to screens (menus) so the
	 *  immediate-mode draws never touch the in-world HUD render path. */
	public static boolean roundActive() {
		if (Minecraft.getInstance().screen == null) {
			return false;
		}
		ensureCompiled();
		if (enabled() && ROUND == null && !warnedRoundFallback) {
			warnedRoundFallback = true;
			OriginClient.LOGGER.warn("Origin: SDF is ON but the rounded-box shader did NOT load — "
					+ "using the software rounded-rect fallback. Check the log above for a shader compile error.");
		}
		return enabled() && ROUND != null;
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
	 * once the screen closes, into world/sky rendering (the black-sky bug). The
	 * next GUI element and every world RenderType set their own state before
	 * drawing, so returning to the disabled-blend / neutral-colour default here is
	 * safe and isolates our draws.
	 */
	public static void restoreState() {
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
	}
}
