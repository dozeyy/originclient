package com.origin.client.client.mods;

import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * 1.21.11 best fit (Screen / GameRenderer / GuiRenderer source-verified):
 * vanilla's menu blur is a GUI STRATUM marker -- Screen.renderBlurredBackground
 * calls GuiGraphicsExtractor.blurBeforeThisStratum() when the Menu Background
 * Blurriness option is >= 1, and GuiRenderer runs the "blur" post chain over
 * everything drawn before that stratum. The radius is not an argument there: it
 * is the menu-blurriness value GameRenderer.render writes into the global
 * settings uniform each frame. Containers (isInGameUi) skip the blur and get a
 * flat dark gradient (renderTransparentBackground). So Origin:
 *   - asks for the same one-per-frame blur marker itself (MenuBlurMixin), and
 *   - swaps its own strength into that uniform while it owns the open screen
 *     (MenuBlurRadiusMixin) -- vanilla's own shader, no second blur.
 * Three kinds of screen are routed through here:
 *   inventory   -- every AbstractContainerScreen (inventory, chests, furnaces...)
 *   originMenus -- Origin's own screens (the mod menu draws no vanilla backdrop
 *                  in a world, so OriginModMenuScreen calls renderBehind itself)
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
		return screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen) && Minecraft.getInstance().level != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen));
	}

	/** The blur radius to put in the global uniform this frame (MenuBlurRadiusMixin). */
	public static int radius() {
		return (int) Math.round(Math.max(0, Math.min(20, Mods.num(MOD, "strength"))));
	}

	/** Marks the blur for this frame's GUI (everything drawn so far gets blurred).
	 *  Strength 0 = no blur (the tint still draws). GuiRenderState allows ONE blur
	 *  per frame and throws on a second; that is caught, never propagated. */
	public static void blur(GuiGraphicsExtractor g) {
		if (radius() >= 1) {
			try {
				g.blurBeforeThisStratum();
			} catch (IllegalStateException alreadyBlurred) {
				// something already blurred this frame: the world is blurred either way
			}
		}
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(GuiGraphicsExtractor g, Screen screen) {
		int c = OriginColorPicker.liveColor(MOD, kind(screen) + "Bg");
		if (((c >>> 24) & 0xFF) != 0) {
			g.fill(0, 0, g.guiWidth(), g.guiHeight(), c);
		}
	}

	/** Blur + tint in one go, for screens that draw their own backdrop. */
	public static void renderBehind(GuiGraphicsExtractor g, Screen screen, float partialTick) {
		if (handles(screen)) {
			blur(g);
			tint(g, screen);
		}
	}
}
