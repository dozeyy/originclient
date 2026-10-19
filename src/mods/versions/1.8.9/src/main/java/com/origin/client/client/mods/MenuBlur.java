package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.shaders.IrisBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * Pre-1.20.5 best fit: vanilla has no menu blur -- every in-world screen (pause,
 * options, inventories alike) just darkens the world with a flat gradient. On
 * 1.8.9 that gradient is GuiScreen.drawWorldBackground(int), which
 * GuiScreenBackgroundMixin swaps for blur + tint. The blur is the same
 * box-filter pyramid Origin's menus use for the panorama (OriginScreenRenderer):
 * the frame so far (world + HUD) is halved through LINEAR-filtered targets and
 * stretched back. Strength picks the depth: each halving roughly doubles the
 * radius, so 1 / 2-3 / 4-7 / 8-15 / 16-20 map to 1..5 levels (default 4 = 3).
 *
 * 1.8.9: the pyramid rather than vanilla's shaders/post/blur.json through a
 * ShaderGroup -- a post chain has to live on EntityRenderer, which OptiFine
 * replaces and its shader packs drive, while the pyramid is plain framebuffer
 * draws the GUI pass owns. It reads mc.getFramebuffer(), so it runs only while
 * that framebuffer is the one being drawn to (OptiFine's Fast Render can draw
 * the frame without it), and never while an OptiFine shader pack is active.
 * Any of those -> vanilla's darkened backdrop.
 *
 * Three kinds of screen:
 *   inventory   -- every GuiContainer (inventory, chests, furnaces...)
 *   originMenus -- Origin's own screens (they draw no vanilla backdrop, so
 *                  OriginWindow / the mod menu call renderBehind themselves)
 *   pause       -- everything else in-world (pause menu, in-game options...)
 * A kind whose toggle is off, the main menu (no world) and the mod being off all
 * leave vanilla's behaviour exactly as it was.
 */
public final class MenuBlur {
	private static final String MOD = "menublur";
	private static final Framebuffer[] PYRAMID = new Framebuffer[5];
	private static boolean broken = false;

	private MenuBlur() {
	}

	private static String kind(GuiScreen screen) {
		if (screen instanceof GuiContainer) {
			return "inventory";
		}
		if (screen.getClass().getName().startsWith("com.origin.client.")) {
			return "originMenus";
		}
		return "pause";
	}

	/** Whether Menu Blur (rather than vanilla) draws this screen's backdrop. */
	public static boolean handles(GuiScreen screen) {
		return screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen) && !broken && Minecraft.getMinecraft().theWorld != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen))
				&& OpenGlHelper.isFramebufferEnabled() && !IrisBridge.shadersActive();
	}

	/** Blurs whatever is in the main framebuffer (the world + HUD) at the mod's
	 *  strength. Strength 0 = no blur (the tint still draws). True when the
	 *  backdrop is done (blurred, or nothing to blur); false = leave it to
	 *  vanilla. The frame's own target is bound again afterwards.
	 *  1.8.9: returns a boolean (1.16.5's is void) because the main framebuffer
	 *  may not be the frame's target here (see the class comment). */
	public static boolean blur(float partialTick) {
		Minecraft mc = Minecraft.getMinecraft();
		int radius = (int) Math.round(Mods.num(MOD, "strength"));
		if (radius < 1) {
			return true;
		}
		int levels = Math.min(PYRAMID.length, 1 + (31 - Integer.numberOfLeadingZeros(radius)));
		Framebuffer main = mc.getFramebuffer();
		int previous = GL11.glGetInteger(OriginScreenRenderer.GL_FRAMEBUFFER_BINDING);
		if (main == null || previous != main.framebufferObject) {
			return false;
		}
		int mainFilter = main.framebufferFilter;
		try {
			OriginScreenRenderer.ensurePyramid(PYRAMID, levels,
					Math.max(1, main.framebufferWidth), Math.max(1, main.framebufferHeight));
			main.setFramebufferFilter(GL11.GL_LINEAR);
			PYRAMID[0].bindFramebuffer(true);
			OriginScreenRenderer.drawFullscreenTexture(main);
			for (int i = 1; i < levels; i++) {
				PYRAMID[i].bindFramebuffer(true);
				OriginScreenRenderer.drawFullscreenTexture(PYRAMID[i - 1]);
			}
		} catch (Throwable t) {
			broken = true;   // vanilla's darkened backdrop from now on
			com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur failed; using vanilla's menu backdrop", t);
			return false;
		} finally {
			try {
				main.setFramebufferFilter(mainFilter);
			} catch (Throwable ignored) {
				// filter restore is cosmetic
			}
			OriginScreenRenderer.restoreTarget(previous);
		}
		OriginScreenRenderer.drawFullscreenTexture(PYRAMID[levels - 1]);
		return true;
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(Gfx g, GuiScreen screen) {
		int c = OriginColorPicker.liveColor(MOD, kind(screen) + "Bg");
		if (((c >>> 24) & 0xFF) != 0) {
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), c);
		}
	}

	/** Blur + tint in one go, for screens that draw their own backdrop. */
	public static void renderBehind(Gfx g, GuiScreen screen, float partialTick) {
		if (handles(screen) && blur(partialTick)) {
			tint(g, screen);
		}
	}
}
