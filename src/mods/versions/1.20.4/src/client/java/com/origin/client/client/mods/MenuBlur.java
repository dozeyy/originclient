package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mixin.PostChainAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * Pre-1.20.5 best fit: vanilla has no menu blur yet (1.21.1 reuses vanilla's own
 * GameRenderer.blurEffect chain; this era has none, and PostChain has no
 * setUniform). So Origin runs its own post chain,
 * originclient:shaders/post/menu_blur.json, built only from vanilla's own
 * "blur" program (shaders/program/blur.json, shipped in every 1.20.x): the
 * same three horizontal+vertical box-blur passes 1.21.1's blur.json runs, at
 * radius R, R/2 and R/4 -- three stacked box blurs converge on 1.21.1's soft
 * gaussian look. Each pass's "Radius" uniform is set through PostChainAccessor
 * (the technique MotionBlur uses here). The chain is built on first use and
 * resized with the window; if it ever fails to build, the blur switches itself
 * off for the session and only the tint draws.
 *
 * MenuBlurMixin routes three kinds of screen through here:
 *   inventory   -- every AbstractContainerScreen (inventory, chests, furnaces...)
 *   originMenus -- Origin's own screens (the mod menu draws no vanilla backdrop,
 *                  so OriginModMenuScreen calls renderBehind itself)
 *   pause       -- everything else in-world (pause menu, in-game options...)
 * A kind whose toggle is off, the main menu (no world) and the mod being off all
 * leave vanilla's behaviour exactly as it was.
 */
public final class MenuBlur {
	private static final String MOD = "menublur";
	private static final ResourceLocation CHAIN = new ResourceLocation("originclient", "shaders/post/menu_blur.json");
	// Radius multiplier per pass, in menu_blur.json's pass order (= 1.21.1's
	// blur.json RadiusMultiplier values).
	private static final float[] PASS_SCALE = {1f, 1f, 0.5f, 0.5f, 0.25f, 0.25f};

	private static PostChain chain;
	private static int chainW = -1, chainH = -1;
	private static boolean broken = false;

	private MenuBlur() {
	}

	private static String kind(Screen screen) {
		if (screen instanceof AbstractContainerScreen<?>) {
			return "inventory";
		}
		if (screen.getClass().getName().startsWith("com.origin.client.")) {
			return "originMenus";
		}
		return "pause";
	}

	/** Whether Menu Blur (rather than vanilla) draws this screen's backdrop. */
	public static boolean handles(Screen screen) {
		return screen != null && Minecraft.getInstance().level != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen));
	}

	/** Blurs whatever is in the main framebuffer (the world + HUD) at the mod's
	 *  strength. Strength 0 = no blur (the tint still draws). The main target is
	 *  rebound afterwards (the chain leaves its own target bound) and its depth
	 *  cleared: the chain's full-screen quads wrote depth, and the menu drawn on
	 *  top must start from a clean depth buffer, as the GUI pass always does. */
	public static void blur(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		int radius = (int) Math.round(Mods.num(MOD, "strength"));
		if (radius < 1 || broken) {
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
			List<PostPass> passes = ((PostChainAccessor) chain).originclient$passes();
			for (int i = 0; i < passes.size(); i++) {
				float scale = PASS_SCALE[Math.min(i, PASS_SCALE.length - 1)];
				passes.get(i).getEffect().safeGetUniform("Radius").set((float) Math.max(1, Math.round(radius * scale)));
			}
			RenderSystem.disableBlend();
			RenderSystem.disableDepthTest();
			RenderSystem.resetTextureMatrix();
			chain.process(partialTick);
		} catch (Throwable t) {
			broken = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur's blur chain failed; menus keep just the tint this session", t);
		} finally {
			main.bindWrite(true);
			RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
			RenderSystem.enableDepthTest();
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
		}
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(GuiGraphics g, Screen screen) {
		int c = OriginColorPicker.liveColor(MOD, kind(screen) + "Bg");
		if (((c >>> 24) & 0xFF) != 0) {
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), c);
		}
	}

	/** Blur + tint in one go, for screens that draw their own backdrop. */
	public static void renderBehind(GuiGraphics g, Screen screen, float partialTick) {
		if (handles(screen)) {
			blur(partialTick);
			tint(g, screen);
		}
	}
}
