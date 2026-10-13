package com.origin.client.client.render;

import com.origin.client.client.hooks.WorldHooks;
import com.origin.client.client.mixin.world.ShaderGroupAccessor;
import com.origin.client.client.mods.Mods;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The Color Saturation mod's renderer on 1.8.9: a full-screen grade over the
 * rendered WORLD (Saturation / Brightness / Contrast). Driven per-frame from
 * WorldHooks on RenderGameOverlayEvent.Pre(ALL) -- fired by GuiIngameForge at the
 * very start of the HUD pass, after the world (and EntityRenderer's own post
 * effect) is fully drawn but BEFORE the HUD/menu -- so only the game world is
 * graded, never the HUD, the menu, or the title screen. Same placement and maths
 * as every other version (1.16.5 hooks the head of Gui.render).
 *
 * <p>WHY A SHADERGROUP: 1.8.9 has no core shaders, but it DOES have vanilla's
 * post-effect pipeline -- the mechanism behind the spectator creeper/spider/
 * enderman views. This is one of those chains (shaders/post/origin_color_grade.json:
 * main -> grade -> swap -> blit -> main) with a GLSL 110 program like vanilla's
 * own, run the way EntityRenderer runs its own post effect (texture matrix reset
 * around loadShaderGroup, then the main framebuffer rebound). Afterwards the
 * state the HUD draws under is put back. Kept separate from EntityRenderer's
 * single effect slot (Motion Blur's and the spectator views'), so they never
 * fight over it.
 *
 * <p>Never with OptiFine shaders (a shader pack owns the whole pipeline and the
 * framebuffer the grade would read) nor with OptiFine's Fast Render or the
 * vanilla FBO option off (no framebuffer to read): the grade steps aside then
 * (WorldHooks.postChainsUsable()).
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

	private static ShaderGroup chain;
	private static int chainW = -1, chainH = -1;
	private static boolean broken = false;

	public static void process(float partialTick) {
		if (broken || !Mods.on("colorsaturation")) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		// Only in a world (skip title screen etc.). Runs pre-HUD, so only world pixels
		// are graded; stays live even behind the open mod menu (world renders behind it).
		if (mc.theWorld == null || !WorldHooks.postChainsUsable()) {
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
		Framebuffer main = mc.getFramebuffer();
		if (main == null) {
			return;   // framebuffers unsupported/disabled: nothing to grade
		}
		try {
			if (chain == null) {
				chain = new ShaderGroup(mc.getTextureManager(), mc.getResourceManager(), main, CHAIN);
				chainW = chainH = -1;
			}
			if (chainW != main.framebufferWidth || chainH != main.framebufferHeight) {
				chain.createBindFramebuffers(main.framebufferWidth, main.framebufferHeight);
				chainW = main.framebufferWidth;
				chainH = main.framebufferHeight;
			}
			if (chain instanceof ShaderGroupAccessor) {
				for (Shader pass : ((ShaderGroupAccessor) chain).origin$listShaders()) {
					set(pass, "Saturation", sat);
					set(pass, "Brightness", bri);
					set(pass, "Contrast", con);
				}
			}

			// EntityRenderer.updateCameraAndRender's own post-effect sequence.
			GlStateManager.disableBlend();
			GlStateManager.disableDepth();
			GlStateManager.disableAlpha();
			GlStateManager.enableTexture2D();
			GlStateManager.matrixMode(GL11.GL_TEXTURE);
			GlStateManager.pushMatrix();
			GlStateManager.loadIdentity();
			try {
				chain.loadShaderGroup(partialTick);
			} finally {
				GlStateManager.matrixMode(GL11.GL_TEXTURE);
				GlStateManager.popMatrix();
				restore(main);
			}
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Color Saturation grade failed; disabling for this session", t);
			try {
				restore(main);
				if (chain != null) {
					chain.deleteShaderGroup();
				}
			} catch (Throwable ignored) {
			}
			chain = null;
		}
	}

	private static void set(Shader pass, String name, float v) {
		ShaderUniform u = pass.getShaderManager().getShaderUniform(name);
		if (u != null) {
			u.set(v);
		}
	}

	// Main is the target again and the HUD's state is back: the GUI's MODELVIEW
	// matrix mode (the GUI ortho set-up already ran this frame), depth + alpha
	// test on, no blend, a writable depth buffer.
	private static void restore(Framebuffer main) {
		main.bindFramebuffer(true);
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		GlStateManager.enableTexture2D();
		GlStateManager.enableDepth();
		GlStateManager.depthFunc(GL11.GL_LEQUAL);
		GlStateManager.depthMask(true);
		GlStateManager.enableAlpha();
		GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
		GlStateManager.disableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		GlStateManager.color(1f, 1f, 1f, 1f);
	}

	// Half the distance from neutral (1.0): value v -> 1 + (v - 1) * 0.5.
	private static double half(double v) {
		return 1.0 + (v - 1.0) * 0.5;
	}

	private static boolean near1(double v) {
		return Math.abs(v - 1.0) < 0.01;
	}
}
