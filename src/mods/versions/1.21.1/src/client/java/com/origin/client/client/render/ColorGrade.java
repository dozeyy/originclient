package com.origin.client.client.render;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

/**
 * The Color Saturation mod's renderer: a full-screen post-process pass over the
 * finished frame that applies Saturation / Brightness / Contrast (see the
 * origin_color_grade shader). Driven per-frame from GameRendererMixin at the tail
 * of the render, so it grades the whole frame (world + HUD).
 *
 * Fail-soft: if the post effect can't load or process (missing shader, GL issue,
 * or an Iris pipeline that owns the main target), it latches `broken` and never
 * runs again this session — the game keeps rendering normally, never crashes.
 * No-op when the mod is off or all three values are ~1.0 (neutral).
 */
public final class ColorGrade {
	private ColorGrade() {
	}

	// PostChain takes the FULL resource path (vanilla passes e.g.
	// "shaders/post/creeper.json"), NOT the short effect id.
	private static final ResourceLocation EFFECT =
			ResourceLocation.fromNamespaceAndPath("minecraft", "shaders/post/origin_color_grade.json");

	private static PostChain chain;
	private static int lastW = -1, lastH = -1;
	private static boolean broken = false;

	public static void process(float partialTick) {
		if (broken || !Mods.on("colorsaturation")) {
			return;
		}
		double sat = Mods.num("colorsaturation", "saturation");
		double bri = Mods.num("colorsaturation", "brightness");
		double con = Mods.num("colorsaturation", "contrast");
		// Neutral (all ~1.0) → skip the pass entirely (zero cost when unchanged).
		if (near1(sat) && near1(bri) && near1(con)) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		var main = mc.getMainRenderTarget();
		try {
			if (chain == null) {
				chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, EFFECT);
				chain.resize(main.width, main.height);
				lastW = main.width;
				lastH = main.height;
			} else if (main.width != lastW || main.height != lastH) {
				chain.resize(main.width, main.height);
				lastW = main.width;
				lastH = main.height;
			}
			chain.setUniform("Saturation", (float) sat);
			chain.setUniform("Brightness", (float) bri);
			chain.setUniform("Contrast", (float) con);
			chain.process(partialTick);
			main.bindWrite(false);
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation post-effect failed; disabling for this session", t);
		}
	}

	private static boolean near1(double v) {
		return Math.abs(v - 1.0) < 0.01;
	}
}
