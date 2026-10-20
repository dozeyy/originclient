package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mixin.GameRendererBlurAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.PostChain;

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
		// Transparent Menus (Settings > General): every in-world menu gets a clear backdrop,
		// no blur and no tint, whatever the Menu Blur mod itself is set to.
		if (transparent() && screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen)) {
			return true;
		}
		return screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen) && Minecraft.getInstance().level != null
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
	 *  strength. Strength 0 = no blur (the tint still draws). Like vanilla's own
	 *  renderBlurredBackground, the main target is rebound afterwards -- the blur
	 *  leaves its own framebuffer bound, and everything after must draw to main. */
	public static void blur(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		int radius = (int) Math.round(strength());
		if (radius >= 1) {
			PostChain chain = ((GameRendererBlurAccessor) mc.gameRenderer).originclient$blurEffect();
			if (chain != null) {
				chain.setUniform("Radius", radius);
				chain.process(partialTick);
			}
		}
		mc.getMainRenderTarget().bindWrite(false);
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(GuiGraphics g, Screen screen) {
		if (transparent()) {
			return;
		}
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
