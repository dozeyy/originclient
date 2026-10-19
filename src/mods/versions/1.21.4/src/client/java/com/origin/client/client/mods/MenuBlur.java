package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * 1.21.1 best fit: vanilla already owns a menu blur -- a post chain behind every
 * in-game Screen (renderBlurredBackground), radius = the Menu Background
 * Blurriness option -- but skips containers, which get a flat dark gradient
 * (renderTransparentBackground). So Origin reuses vanilla's own blur chain with
 * its own radius (no second shader), and MenuBlurMixin routes three kinds of
 * screen through here:
 *   inventory   -- every AbstractContainerScreen (inventory, chests, furnaces...)
 *   originMenus -- Origin's own screens (the mod menu draws no vanilla backdrop,
 *                  so OriginModMenuScreen calls renderBehind itself)
 *   pause       -- everything else in-world (pause menu, in-game options...)
 * A kind whose toggle is off, the main menu (no world) and the mod being off all
 * leave vanilla's behaviour exactly as it was.
 *
 * PER-VERSION (1.21.2-1.21.4): the blur chain lives in the ShaderManager and
 * GameRenderer.processBlurEffect() reads its radius from the option itself, so
 * blur() runs vanilla's own processBlurEffect with radiusOverride set for that
 * one call (MenuBlurRadiusMixin swaps the radius in). The GUI is batched here:
 * blur() flushes what the screen already submitted so it lands under the blur.
 */
public final class MenuBlur {
	private static final String MOD = "menublur";

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
		return screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen) && Minecraft.getInstance().level != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen));
	}

	// Origin's radius while blur() is running vanilla's processBlurEffect, else -1.
	private static float override = -1f;

	/** From MenuBlurRadiusMixin: the radius processBlurEffect should use. */
	public static float radiusOverride(float vanilla) {
		return override >= 0f ? override : vanilla;
	}

	/** Blurs whatever is in the main framebuffer (the world + HUD) at the mod's
	 *  strength. Strength 0 = no blur (the tint still draws). Like vanilla's own
	 *  renderBlurredBackground, the main target is rebound afterwards -- the blur
	 *  leaves its own framebuffer bound, and everything after must draw to main.
	 *  `g` (may be null) is flushed first so its batch lands under the blur. */
	public static void blur(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		int radius = (int) Math.round(Mods.num(MOD, "strength"));
		if (radius >= 1) {
			if (g != null) {
				g.flush();
			}
			override = radius;
			try {
				mc.gameRenderer.processBlurEffect();
			} finally {
				override = -1f;
			}
		}
		mc.getMainRenderTarget().bindWrite(false);
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
			blur(g);
			tint(g, screen);
		}
	}
}
