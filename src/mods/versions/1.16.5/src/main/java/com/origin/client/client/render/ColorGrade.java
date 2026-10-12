package com.origin.client.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.mixin.PostChainAccessor;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The Color Saturation mod's renderer on 1.16.5: a full-screen grade over the
 * rendered WORLD (Saturation / Brightness / Contrast). Driven per-frame from
 * GuiHudMixin at the HEAD of Gui.render -- after the world is fully drawn but
 * BEFORE the HUD/menu -- so only the game world is graded, never the HUD, the
 * menu, or the title screen. Same placement and maths as 1.17.1+.
 *
 * <p>WHY A POSTCHAIN HERE (1.17.1+ use a hand-rolled core-shader blit): 1.16.5
 * has no core shaders, but it DOES have vanilla's post-effect pipeline -- the
 * mechanism behind the spectator creeper/spider/enderman views. This is one of
 * those chains (shaders/post/origin_color_grade.json: main -> grade -> swap ->
 * blit -> main) with a GLSL 110 program like vanilla's own. It is run the way
 * GameRenderer runs its own post effect, at the same point in the frame (just
 * before the GUI): blend / depth / alpha test off, texture matrix reset, then
 * main rebound. Afterwards the state the HUD draws under is put back (matrix
 * mode MODELVIEW, depth + alpha test on) -- vanilla instead re-establishes it
 * by setting the GUI up after its effect, which has already happened here.
 * Kept separate from GameRenderer's single effect slot (Motion Blur's and the
 * spectator views'), so they never fight over it.
 *
 * <p>Fail-soft: if the chain won't load, or any step throws, it latches
 * {@code broken} and never runs again this session -- the game keeps rendering
 * normally, never crashes. No-op when the mod is off or all three values are
 * ~1.0 (neutral).
 */
public final class ColorGrade {
	private ColorGrade() {
	}

	private static final ResourceLocation CHAIN = new ResourceLocation("shaders/post/origin_color_grade.json");

	private static PostChain chain;
	private static int chainW = -1, chainH = -1;
	private static boolean broken = false;

	public static void process(float partialTick) {
		if (broken || !Mods.on("colorsaturation")) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		// Only in a world (skip title screen etc.). Runs pre-HUD, so only world pixels
		// are graded; stays live even behind the open mod menu (world renders behind it).
		if (mc.level == null) {
			return;
		}
		// Sliders are multipliers around 1.0 (neutral). Halve the deviation from
		// neutral so the effect is gentler -- at either extreme it's half as strong.
		float sat = (float) half(Mods.num("colorsaturation", "saturation"));
		float bri = (float) half(Mods.num("colorsaturation", "brightness"));
		float con = (float) half(Mods.num("colorsaturation", "contrast"));
		// Neutral (all ~1.0) -> skip entirely (zero cost when unchanged).
		if (near1(sat) && near1(bri) && near1(con)) {
			return;
		}
		RenderTarget main = mc.getMainRenderTarget();
		try {
			if (chain == null) {
				chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
				chainW = chainH = -1;
			}
			if (chainW != main.width || chainH != main.height) {
				chain.resize(main.width, main.height);
				chainW = main.width;
				chainH = main.height;
			}
			for (PostPass pass : ((PostChainAccessor) chain).originclient$passes()) {
				pass.getEffect().safeGetUniform("Saturation").set(sat);
				pass.getEffect().safeGetUniform("Brightness").set(bri);
				pass.getEffect().safeGetUniform("Contrast").set(con);
			}

			// GameRenderer.render's own post-effect sequence.
			RenderSystem.disableBlend();
			RenderSystem.disableDepthTest();
			RenderSystem.disableAlphaTest();
			RenderSystem.enableTexture();
			RenderSystem.matrixMode(GL11.GL_TEXTURE);
			RenderSystem.pushMatrix();
			RenderSystem.loadIdentity();
			try {
				chain.process(partialTick);
			} finally {
				RenderSystem.matrixMode(GL11.GL_TEXTURE);
				RenderSystem.popMatrix();
				restore(main);
			}
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation grade failed; disabling for this session", t);
			try {
				restore(main);
				if (chain != null) {
					chain.close();
				}
			} catch (Throwable ignored) {
			}
			chain = null;
		}
	}

	// Main is the target again and the HUD's state is back: the GUI's MODELVIEW
	// matrix mode (the GUI ortho set-up already ran this frame), depth + alpha
	// test on, no blend, a writable depth buffer.
	private static void restore(RenderTarget main) {
		main.bindWrite(true);
		RenderSystem.matrixMode(GL11.GL_MODELVIEW);
		RenderSystem.enableTexture();
		RenderSystem.enableDepthTest();
		RenderSystem.depthFunc(GL11.GL_LEQUAL);
		RenderSystem.depthMask(true);
		RenderSystem.enableAlphaTest();
		RenderSystem.defaultAlphaFunc();
		RenderSystem.disableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.color4f(1f, 1f, 1f, 1f);
	}

	// Half the distance from neutral (1.0): value v -> 1 + (v - 1) * 0.5.
	private static double half(double v) {
		return 1.0 + (v - 1.0) * 0.5;
	}

	private static boolean near1(double v) {
		return Math.abs(v - 1.0) < 0.01;
	}
}
