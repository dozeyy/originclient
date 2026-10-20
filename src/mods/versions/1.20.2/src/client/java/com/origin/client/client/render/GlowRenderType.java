package com.origin.client.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla's RenderType.eyes(texture) with ONE change: the blend is
 * SRC_ALPHA, ONE instead of ONE, ONE.
 *
 * Why: eyes' additive blend (ONE, ONE) ignores the texture's alpha, so a soft
 * radial-gradient glow whose colour is white at every texel (the Orbit Halo's
 * centre) adds a solid white disc instead of fading out. Weighting the source by
 * its alpha keeps it see-through; fully opaque texels add exactly as before. Will's
 * playtest 2026-10-20: "cosmetics menu orbit halo shows white" (same fix as 1.8.9).
 *
 * It wraps the vanilla type (same format, mode, shaders, texture, write mask,
 * clear) and only re-sets the blend function after the vanilla set-up ran, so it
 * can never drift from the version's own eyes state. Cached per texture: buffer
 * sources key on the type instance. Render thread only. If it were dropped the
 * glow would simply go back to the white disc.
 */
public final class GlowRenderType extends RenderType {

	private static final Map<ResourceLocation, RenderType> CACHE = new HashMap<>();

	private GlowRenderType(RenderType base, ResourceLocation texture) {
		super("originclient_glow_" + texture, base.format(), base.mode(), base.bufferSize(), false, true,
				() -> {
					base.setupRenderState();
					RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
				},
				base::clearRenderState);
	}

	/** The additive, alpha-weighted glow surface for `texture`. */
	public static RenderType eyes(ResourceLocation texture) {
		RenderType t = CACHE.get(texture);
		if (t == null) {
			t = new GlowRenderType(RenderType.eyes(texture), texture);
			CACHE.put(texture, t);
		}
		return t;
	}
}
