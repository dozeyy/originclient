package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.mixin.PostChainAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * Pre-1.20.5 best fit: vanilla has no menu blur at all -- every in-world screen
 * (pause, options AND containers) just darkens the world with one flat gradient
 * in Screen.renderBackground (MenuBlurMixin swaps that gradient). So Origin
 * runs its own post chain, originclient:shaders/post/menu_blur.json: vanilla's
 * own separable "blur" program (the one behind the old blur.json effect), two
 * rounds -- at the strength radius, then half of it -- the same two-round box
 * blur 1.20.5+'s menu blur does. Strength is that radius, as on 1.21.1.
 * Three kinds of screen:
 *   inventory   -- every AbstractContainerScreen (inventory, chests, furnaces...)
 *   originMenus -- Origin's own screens (the mod menu draws no vanilla backdrop,
 *                  so OriginModMenuScreen calls renderBehind itself)
 *   pause       -- everything else in-world (pause menu, in-game options...)
 * A kind whose toggle is off, the main menu (no world) and the mod being off all
 * leave vanilla's behaviour exactly as it was. If the chain can't load, the mod
 * goes quiet for the session (broken) and vanilla's gradient comes back.
 */
public final class MenuBlur {
	private static final String MOD = "menublur";
	private static final ResourceLocation CHAIN = new ResourceLocation("originclient", "shaders/post/menu_blur.json");

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
	 *  bound again afterwards (with its viewport), so the screen draws on top. */
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
			// Passes 0-1: the full radius; passes 2-3: half of it (1.20.5+'s second round).
			List<PostPass> passes = ((PostChainAccessor) chain).originclient$passes();
			for (int i = 0; i < passes.size(); i++) {
				float r = i < 2 ? radius : Math.max(1, Math.round(radius * 0.5f));
				passes.get(i).getEffect().safeGetUniform("Radius").set(r);
			}
			chain.process(partialTick);
		} catch (Throwable t) {
			broken = true;   // vanilla's darkened backdrop from the next frame on
			com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur failed; using vanilla's menu backdrop", t);
		} finally {
			main.bindWrite(true);
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
