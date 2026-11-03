package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mixin.PostChainAccessor;
import net.minecraft.client.Minecraft;
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
 * 1.16.5 best fit: vanilla has no menu blur before 1.20.5 -- every in-world
 * screen (pause, options, inventories alike) just darkens the world with a flat
 * gradient in Screen.renderBackground(PoseStack, int). So MenuBlurMixin swaps
 * that gradient for blur + tint. The blur is Origin's own post chain,
 * originclient:shaders/post/menu_blur.json, built only from vanilla's "blur"
 * program (shaders/program/blur.json, shipped in this version too): three
 * horizontal + vertical box-blur rounds at radius R, R/2 and R/4, all at FULL
 * resolution -- stacked box blurs converge on 1.21.1's soft gaussian, the same
 * chain 1.18.2-1.20.4 run. Strength is R (default 4). Each pass's "Radius" is
 * set through PostChainAccessor (MotionBlur's technique here).
 *
 * Why not the old halving pyramid: it shrank the frame to 1/8 size and
 * bilinear-stretched it straight back, so every edge in the world showed an
 * 8 px grid -- the "pixelated Esc menu" (Will, 2026-11-02, found on 1.8.9).
 * Three kinds of screen:
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
		// Transparent Menus (Settings > General): every in-world menu gets a clear backdrop,
		// no blur and no tint, whatever the Menu Blur mod itself is set to.
		if (transparent() && screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen)) {
			return true;
		}
		return screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen) && !broken && Minecraft.getInstance().level != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen));
	}

	/** Settings > General > Transparent Menus, while in a world. */
	public static boolean transparent() {
		return Mods.bool(Mods.GENERAL_ID, "transparentMenus") && Minecraft.getInstance().level != null;
	}

	/** Blur strength: 0 while Transparent Menus is on. */
	private static double strength() {
		return transparent() ? 0.0 : Mods.num(MOD, "strength");
	}

	/** Blurs whatever is in the main framebuffer (the world + HUD) at the mod's
	 *  strength. Strength 0 = no blur (the tint still draws). The main target is
	 *  bound again afterwards, so everything after draws to the screen. */
	public static void blur(float partialTick) {
		if (broken) {
			return;
		}
		blurRadius((int) Math.round(strength()), partialTick);
	}

	/**
	 * Blurs the main framebuffer at {@code radius} (the same two-round chain), for
	 * Origin's out-of-world menu backdrop: this era has no vanilla menu blur, so the
	 * panorama behind Minecraft's menus is blurred here at vanilla 1.21.1's default
	 * Menu Background Blurriness (5). False if the chain failed (then latched off:
	 * the caller keeps the panorama unblurred, nothing crashes).
	 */
	public static boolean blurBackdrop(int radius, float partialTick) {
		if (broken) {
			return false;
		}
		blurRadius(radius, partialTick);
		return !broken;
	}

	private static void blurRadius(int radius, float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (radius < 1) {
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
			// GameRenderer.render's own post-effect sequence (as ColorGrade here).
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
				RenderSystem.matrixMode(GL11.GL_MODELVIEW);
			}
		} catch (Throwable t) {
			broken = true;   // vanilla's darkened backdrop from the next frame on
			com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur failed; using vanilla's menu backdrop", t);
		} finally {
			// The chain's passes leave their own target bound and the GUI state off.
			main.bindWrite(true);
			RenderSystem.enableTexture();
			RenderSystem.enableDepthTest();
			RenderSystem.depthFunc(GL11.GL_LEQUAL);
			RenderSystem.depthMask(true);
			RenderSystem.enableAlphaTest();
			RenderSystem.defaultAlphaFunc();
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.color4f(1f, 1f, 1f, 1f);
		}
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(Gfx g, Screen screen) {
		if (transparent()) {
			return;
		}
		int c = OriginColorPicker.liveColor(MOD, kind(screen) + "Bg");
		if (((c >>> 24) & 0xFF) != 0) {
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), c);
		}
	}

	/** Blur + tint in one go, for screens that draw their own backdrop. */
	public static void renderBehind(Gfx g, Screen screen, float partialTick) {
		if (handles(screen)) {
			blur(partialTick);
			tint(g, screen);
		}
	}
}
