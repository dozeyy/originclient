package com.origin.client.client.render;

import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;

/**
 * The Color Saturation mod's renderer: a full-screen post-process pass over the
 * rendered WORLD that applies Saturation / Brightness / Contrast (see the
 * origin_color_grade shader). Driven per-frame from GuiHudMixin at the HEAD of
 * Gui.render — i.e. after the world is fully drawn but BEFORE the HUD and any
 * screen/menu paint on top. So the grade affects only the game world (live, even
 * behind an open mod menu), never the HUD, the menu, or the title screen.
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
		Minecraft mc = Minecraft.getInstance();
		// Grade whenever there's a world (in-game) — including behind an OPEN mod menu,
		// so adjusting the sliders is a LIVE change you see immediately. Skipped only
		// outside a world (title screen etc.). Runs pre-HUD (Gui.render HEAD), so only
		// world pixels are graded — never the HUD or menu.
		if (mc.level == null) {
			return;
		}
		// Sliders are multipliers around 1.0 (neutral). Halve the deviation from
		// neutral so the effect is gentler — at either slider extreme it's only half
		// as strong as the raw value would give.
		double sat = half(Mods.num("colorsaturation", "saturation"));
		double bri = half(Mods.num("colorsaturation", "brightness"));
		double con = half(Mods.num("colorsaturation", "contrast"));
		// Neutral (all ~1.0) → skip the pass entirely (zero cost when unchanged).
		if (near1(sat) && near1(bri) && near1(con)) {
			return;
		}
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

	// Half the distance from neutral (1.0): value v → 1 + (v - 1) * 0.5.
	private static double half(double v) {
		return 1.0 + (v - 1.0) * 0.5;
	}

	private static boolean near1(double v) {
		return Math.abs(v - 1.0) < 0.01;
	}
}
