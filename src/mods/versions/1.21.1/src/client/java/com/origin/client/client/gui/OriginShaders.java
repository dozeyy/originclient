package com.origin.client.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.OriginClient;
import com.origin.client.client.mods.Mods;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

/**
 * The scalable (SDF/MSDF) rendering backend for the Origin menus.
 *
 * Two custom core shaders, registered through Fabric's
 * {@link CoreShaderRegistrationCallback}, replace the fixed-resolution bitmap
 * text + CPU-coverage rounded rects:
 *
 *   * {@code rendertype_origin_msdf} — multi-channel signed-distance-field text
 *     (see {@link OriginSdfFont}); crisp at any scale like web vector text.
 *   * {@code rendertype_origin_round} — a rounded-box SDF for panels/cards.
 *
 * Everything is gated so it can NEVER break the UI (mandate 4): if the shaders
 * fail to compile/load, {@code MSDF}/{@code ROUND} stay null and every call site
 * falls back to the software path. A live kill switch ({@code meta "originSdf"},
 * default ON — Settings → Menu) lets Will A/B it in-game without a rebuild.
 *
 * State discipline: the immediate-mode draws these shaders back MUST call
 * {@link #restoreState()} afterwards, or the last GUI frame's blend/shader-color
 * leaks into world rendering (that was the broken-sky bug).
 */
public final class OriginShaders {
	public static ShaderInstance MSDF;
	public static ShaderInstance ROUND;

	private static boolean registered = false;
	private static boolean warnedTextFallback = false;
	private static boolean warnedRoundFallback = false;

	private OriginShaders() {
	}

	/** Wire the core-shader registration. Call once from client init. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		OriginClient.LOGGER.info("Origin: registering scalable core shaders (MSDF text + rounded-box SDF)…");
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			context.register(ResourceLocation.fromNamespaceAndPath("originclient", "rendertype_origin_msdf"),
					DefaultVertexFormat.POSITION_TEX_COLOR, shader -> {
						MSDF = shader;
						OriginClient.LOGGER.info("Origin: MSDF text shader compiled + loaded OK.");
					});
			context.register(ResourceLocation.fromNamespaceAndPath("originclient", "rendertype_origin_round"),
					DefaultVertexFormat.POSITION_TEX, shader -> {
						ROUND = shader;
						OriginClient.LOGGER.info("Origin: rounded-box SDF shader compiled + loaded OK.");
					});
		});
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
