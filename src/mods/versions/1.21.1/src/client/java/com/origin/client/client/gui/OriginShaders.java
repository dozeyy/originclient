package com.origin.client.client.gui;

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
 *   * {@code rendertype_origin_round} — a rounded-box SDF for panels/cards;
 *     mathematically perfect curves resolved in screen space.
 *
 * Everything is gated so it can NEVER break the UI (mandate 4): if the shaders
 * fail to compile/load, {@code MSDF}/{@code ROUND} stay null and every call site
 * falls back to the existing software path. A live kill switch
 * ({@code meta "originSdf"}, default on — Settings → Menu) lets Will A/B it
 * in-game without a rebuild, which is exactly the iteration lever the earlier
 * blind font attempts lacked.
 */
public final class OriginShaders {
	public static ShaderInstance MSDF;
	public static ShaderInstance ROUND;

	private static boolean registered = false;

	private OriginShaders() {
	}

	/** Wire the core-shader registration. Call once from client init. */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		CoreShaderRegistrationCallback.EVENT.register(context -> {
			context.register(ResourceLocation.fromNamespaceAndPath("originclient", "rendertype_origin_msdf"),
					DefaultVertexFormat.POSITION_COLOR_TEX, shader -> MSDF = shader);
			context.register(ResourceLocation.fromNamespaceAndPath("originclient", "rendertype_origin_round"),
					DefaultVertexFormat.POSITION_TEX, shader -> ROUND = shader);
		});
	}

	/** Master live toggle (Settings → Menu). Default on. */
	public static boolean enabled() {
		return Mods.metaBool("originSdf", true);
	}

	/** Rounded-box SDF path is usable. Restricted to screens (menus) so the
	 *  immediate-mode draws never touch the in-world HUD render path. */
	public static boolean roundActive() {
		return enabled() && ROUND != null && Minecraft.getInstance().screen != null;
	}
}
