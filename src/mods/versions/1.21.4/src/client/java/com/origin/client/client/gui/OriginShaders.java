package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;

/**
 * The custom core shaders behind the Origin menus and the Color Saturation mod.
 *
 *   * {@code rendertype_origin_icon}  -- the high-range SDF for Origin's own icon
 *     atlas (see {@link ModIcons}); crisp at every GUI scale.
 *   * {@code rendertype_origin_grade} -- the fullscreen Saturation / Brightness /
 *     Contrast grade (see {@code render.ColorGrade}).
 *   * {@code rendertype_origin_msdf}  -- MSDF text for {@link OriginSdfFont}
 *     (the 2026-10 mod menu / Right Shift redesign draws its Inter through it,
 *     via {@link OriginDeck}; older Origin screens still use vanilla's font).
 *
 * PER-VERSION DELTA (1.21.4, covers 1.21.2-1.21.4): 1.21.1 registers these through
 * Fabric's CoreShaderRegistrationCallback, which Fabric removed after 1.21.1. This
 * era does not need it: {@link net.minecraft.client.renderer.ShaderManager} compiles
 * ANY {@link ShaderProgram} key on demand -- config id + vertex format + defines --
 * straight from {@code assets/originclient/shaders/core/*.json + .vsh/.fsh}. The key's
 * config id is the json's path under {@code shaders/} ({@code core/<name>}), and on
 * this era the json's "vertex"/"fragment" entries are full source ids too
 * ({@code originclient:core/<name>}, like vanilla's {@code minecraft:core/...}).
 * Blend state is no longer part of the json here: every draw sets it itself.
 *
 * Rounded panels have NO shader on this era (1.21.1's ROUND): GUI draws are batched,
 * so OriginUi bakes coverage masks at physical resolution and blits them in order
 * instead -- the same per-screen-pixel anti-aliasing, without immediate-mode quads.
 * The redesign kit's exact-radius shapes (OriginUi.roundRect) use the same masks.
 * MSDF text and SDF icons DO draw immediately: each flushes GuiGraphics' batch
 * first, so everything drawn earlier in the frame lands underneath them.
 *
 * Fails soft (mandate 4): a program that does not compile comes back null and every
 * caller takes its software path (mask atlas for icons, no grade). The public fields
 * mirror 1.21.1's names; call sites refresh them through {@link #icon()} /
 * {@link #grade()} / {@link #ensureCompiled()} rather than trusting a stale copy.
 */
public final class OriginShaders {
	public static CompiledShaderProgram MSDF;
	/** Higher-range monochrome SDF used by the custom icon atlas. */
	public static CompiledShaderProgram ICON;
	/** Fullscreen scene color-grade (Color Saturation), invoked by ColorGrade with
	 *  our own GL-state discipline instead of a vanilla PostChain. */
	public static CompiledShaderProgram GRADE;

	private static final ShaderProgram MSDF_KEY = key("rendertype_origin_msdf", DefaultVertexFormat.POSITION_TEX_COLOR);
	private static final ShaderProgram ICON_KEY = key("rendertype_origin_icon", DefaultVertexFormat.POSITION_TEX_COLOR);
	private static final ShaderProgram GRADE_KEY = key("rendertype_origin_grade", DefaultVertexFormat.POSITION_TEX);

	private static boolean registered = false;
	private static boolean warnedTextFallback = false;
	private static final java.util.Set<String> REPORTED = new java.util.HashSet<>();

	private OriginShaders() {
	}

	private static ShaderProgram key(String name, VertexFormat format) {
		return new ShaderProgram(ResourceLocation.fromNamespaceAndPath("originclient", "core/" + name),
				format, ShaderDefines.EMPTY);
	}

	/** Call once from client init. The real work happens on the render thread the
	 *  first time a draw path asks for a program -- no shader manager exists yet at
	 *  client-init time. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: core shaders (icon SDF / colour grade / MSDF) compile on first use.");
	}

	/** The icon SDF program, or null (callers take the mask-atlas path). */
	public static CompiledShaderProgram icon() {
		ICON = fetch(ICON_KEY, "icon SDF");
		return ICON;
	}

	/** The fullscreen colour-grade program, or null (Color Saturation no-ops). */
	public static CompiledShaderProgram grade() {
		GRADE = fetch(GRADE_KEY, "fullscreen colour-grade");
		return GRADE;
	}

	/** The MSDF text program, or null (OriginSdfFont reports not-ready). */
	public static CompiledShaderProgram msdf() {
		MSDF = fetch(MSDF_KEY, "MSDF text");
		return MSDF;
	}

	/** Refreshes every field at once. */
	public static void ensureCompiled() {
		msdf();
		icon();
		grade();
	}

	/**
	 * Asks the live ShaderManager for a program. MUST run on the render thread (the
	 * first request compiles, which touches GL). Deliberately NOT cached here: the
	 * manager caches per resource reload itself (a cheap map lookup after the first
	 * call, a failure is remembered as "no program" without retrying), and a reload
	 * (F3+T, a resource-pack change) closes every program it handed out -- a field
	 * cached across that would point at a deleted GL program. Re-asking on use
	 * always yields the live one.
	 */
	private static CompiledShaderProgram fetch(ShaderProgram key, String what) {
		CompiledShaderProgram program = null;
		try {
			// getProgram returns null (not throws) on a compile/link failure -- it
			// catches the compilation error internally and logs it.
			program = Minecraft.getInstance().getShaderManager().getProgram(key);
		} catch (Throwable t) {
			if (REPORTED.add(what + ":error")) {
				OriginClient.LOGGER.warn("Origin: " + what + " shader request failed -- using the fallback path.", t);
			}
			return null;
		}
		if (program == null && REPORTED.add(what + ":missing")) {
			OriginClient.LOGGER.warn("Origin: " + what + " shader did not compile -- using the fallback path.");
		} else if (program != null && REPORTED.add(what + ":ok")) {
			OriginClient.LOGGER.info("Origin: " + what + " shader compiled + loaded OK.");
		}
		return program;
	}

	/** Vector text + curves are ALWAYS on now (Will): the user-facing toggle was
	 *  removed, so this is unconditionally true. Every shader path still fails
	 *  soft on its own if its program doesn't compile. */
	public static boolean enabled() {
		return true;
	}

	/** Called by OriginSdfFont when text falls back to vanilla, so a non-loading
	 *  shader is visible in the log instead of silently reverting. */
	public static void noteTextFallback() {
		if (!warnedTextFallback) {
			warnedTextFallback = true;
			OriginClient.LOGGER.warn("Origin: SDF is ON but the MSDF text shader/atlas did NOT load -- "
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
