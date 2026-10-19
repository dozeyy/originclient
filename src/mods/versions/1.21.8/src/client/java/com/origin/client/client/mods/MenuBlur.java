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
 * PER-VERSION (1.21.6-1.21.8, verified against the decompiled 1.21.6 and 1.21.8
 * GameRenderer / GuiRenderer / GuiRenderState / Screen): the GUI is deferred and
 * the blur is a marker, not a draw -- GuiGraphics.blurBeforeThisStratum() makes
 * GuiRenderer run GameRenderer.processBlurEffect() between the strata, at most
 * ONCE per frame (a second request throws). Its radius is not an argument: the
 * "blur" post chain reads it from the frame's Globals uniform, which
 * GameRenderer.render fills at the top of the frame from the Menu Background
 * Blurriness option. So the strength is applied there (MenuBlurRadiusMixin asks
 * radiusFor(vanilla) with the screen about to render), and blur() only asks for
 * the frame's one blur.
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

	/** From MenuBlurRadiusMixin, once per frame: the radius the frame's blur
	 *  uses -- Origin's strength while it draws the current screen's backdrop,
	 *  vanilla's option everywhere else. */
	public static int radiusFor(int vanilla) {
		Screen screen = Minecraft.getInstance().screen;
		if (!handles(screen)) {
			return vanilla;
		}
		return Math.max(0, (int) Math.round(Mods.num(MOD, "strength")));
	}

	/** Asks for the frame's blur behind everything drawn so far (the world +
	 *  HUD), at the mod's strength (radiusFor). Strength 0 = no blur (the tint
	 *  still draws). This era blurs at most once a frame: a second request is
	 *  dropped, never thrown into the screen. */
	public static void blur(GuiGraphics g) {
		if (g == null || (int) Math.round(Mods.num(MOD, "strength")) < 1) {
			return;
		}
		try {
			g.blurBeforeThisStratum();
		} catch (IllegalStateException alreadyBlurred) {
			// the frame's one blur is already placed
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
			blur(g);
			tint(g, screen);
		}
	}
}
