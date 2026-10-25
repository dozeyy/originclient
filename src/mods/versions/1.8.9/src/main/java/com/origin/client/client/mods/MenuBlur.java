package com.origin.client.client.mods;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import com.origin.client.client.render.OriginScreenRenderer;
import com.origin.client.client.shaders.IrisBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;

import java.lang.ref.WeakReference;

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
 * Frozen-world reuse (Phase 6 U6.3 pick 2): in single-player a pausing screen
 * (Minecraft.isGamePaused) stops the world, so every frame of the backdrop would
 * be the same image -- the pyramid is built once and only its final level is
 * stretched over the screen after that. The reuse is allowed only while all of
 * these hold, otherwise the old per-frame blur runs (so the cost is never worse
 * than before, and a stale image is never shown):
 *   - the game is paused AND the world and its total time are the ones captured
 *     (multiplayer, LAN-open worlds and inventories are never paused: they keep
 *     moving, and a rate limit would show the lag, so they re-blur every frame);
 *   - the blur was used on the previous frame too (a frame without a blur --
 *     screen closed, other screen, shaders on -- ends the reuse);
 *   - no click, key, wheel or drag happened since (a slider or toggle in a menu
 *     can change the world image; HudHooks calls invalidate());
 *   - same framebuffer size and texture (window resize), GUI scale, blur depth
 *     and resource generation (a resource reload);
 *   - the image is younger than REFRESH_MS. Chunk building and the HUD's live
 *     readouts (clock, FPS) go on while paused; 1.8.9 exposes no cheap "nothing
 *     pending" test (RenderGlobal's queue is private and OptiFine-replaced), so
 *     instead of guessing the image is simply rebuilt every few seconds. A still
 *     world rebuilds to the same picture (invisible); a world that was still
 *     loading catches up within one interval, behind a blur and the fog.
 * The first frame of any pause finds no cache, so it already shows the new image.
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

	// ---- frozen-world reuse state (render thread only) ----
	private static long frame;                 // frames seen by onFrame()
	private static boolean frameHookSeen;      // no onFrame() ever -> never reuse
	private static boolean cacheValid;
	private static long cachedFrame;           // frame of the last build or reuse
	private static int cachedLevels, cachedW, cachedH, cachedTex, cachedGuiScale, cachedReload;
	private static WeakReference<Object> cachedWorld;
	private static long cachedWorldTime;
	private static int reloadGen;
	private static boolean reloadListenerSet;
	private static long cachedAtMs;
	/** How old a reused backdrop may get while the world is frozen. */
	private static final long REFRESH_MS = 3000L;

	private MenuBlur() {
	}

	/** Once per rendered frame (HudHooks, RenderTickEvent START). */
	public static void onFrame() {
		frame++;
		frameHookSeen = true;
	}

	/** Drops the reused backdrop: the next blur rebuilds it. Cheap; call on any input. */
	public static void invalidate() {
		cacheValid = false;
	}

	/** True while the game is paused in a world (single-player pausing screen, not LAN-open). */
	private static boolean frozen(Minecraft mc) {
		try {
			return mc.isGamePaused() && mc.theWorld != null;
		} catch (Throwable t) {
			return false;
		}
	}

	private static boolean canReuse(Minecraft mc, Framebuffer main, int levels) {
		if (!cacheValid || !frameHookSeen || cachedFrame < frame - 1 || PYRAMID[0] == null) {
			return false;
		}
		if (levels != cachedLevels || main.framebufferWidth != cachedW || main.framebufferHeight != cachedH
				|| main.framebufferTexture != cachedTex || mc.gameSettings.guiScale != cachedGuiScale
				|| reloadGen != cachedReload) {
			return false;
		}
		if (Minecraft.getSystemTime() - cachedAtMs >= REFRESH_MS) {
			return false;
		}
		if (!frozen(mc) || cachedWorld == null || cachedWorld.get() != mc.theWorld
				|| cachedWorldTime != mc.theWorld.getTotalWorldTime()) {
			return false;
		}
		return true;
	}

	private static void remember(Minecraft mc, Framebuffer main, int levels) {
		if (!frameHookSeen || !frozen(mc)) {
			cacheValid = false;
			return;
		}
		cacheValid = true;
		cachedFrame = frame;
		cachedAtMs = Minecraft.getSystemTime();
		cachedLevels = levels;
		cachedW = main.framebufferWidth;
		cachedH = main.framebufferHeight;
		cachedTex = main.framebufferTexture;
		cachedGuiScale = mc.gameSettings.guiScale;
		cachedReload = reloadGen;
		cachedWorld = new WeakReference<Object>(mc.theWorld);
		cachedWorldTime = mc.theWorld.getTotalWorldTime();
	}

	/** A resource reload (F3+T, resource packs) changes the world's textures: drop the reused image. */
	private static void ensureReloadListener(Minecraft mc) {
		if (reloadListenerSet) {
			return;
		}
		reloadListenerSet = true;
		try {
			((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(new IResourceManagerReloadListener() {
				@Override
				public void onResourceManagerReload(IResourceManager manager) {
					reloadGen++;
					cacheValid = false;
				}
			});
		} catch (Throwable t) {
			// no listener: input events still drop the image on F3+T / the resource pack screen
		}
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
		// Transparent Menus (Settings > General): every in-world menu gets a clear backdrop,
		// no blur and no tint, whatever the Menu Blur mod itself is set to.
		if (transparent() && screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen)) {
			return true;
		}
		return screen != null && !com.origin.client.client.OriginStyle.vanillaMenusOn(screen) && !broken && Minecraft.getMinecraft().theWorld != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen))
				&& OpenGlHelper.isFramebufferEnabled() && !IrisBridge.shadersActive();
	}

	/** Settings > General > Transparent Menus, while in a world. */
	public static boolean transparent() {
		return Mods.bool(Mods.GENERAL_ID, "transparentMenus") && Minecraft.getMinecraft().theWorld != null;
	}

	/** Blur strength: 0 while Transparent Menus is on. */
	private static double strength() {
		return transparent() ? 0.0 : Mods.num(MOD, "strength");
	}

	/** Blurs whatever is in the main framebuffer (the world + HUD) at the mod's
	 *  strength. Strength 0 = no blur (the tint still draws). True when the
	 *  backdrop is done (blurred, or nothing to blur); false = leave it to
	 *  vanilla. The frame's own target is bound again afterwards.
	 *  1.8.9: returns a boolean (1.16.5's is void) because the main framebuffer
	 *  may not be the frame's target here (see the class comment). */
	public static boolean blur(float partialTick) {
		Minecraft mc = Minecraft.getMinecraft();
		int radius = (int) Math.round(strength());
		if (radius < 1) {
			return true;
		}
		int levels = Math.min(PYRAMID.length, 1 + (31 - Integer.numberOfLeadingZeros(radius)));
		Framebuffer main = mc.getFramebuffer();
		int previous = GL11.glGetInteger(OriginScreenRenderer.GL_FRAMEBUFFER_BINDING);
		if (main == null || previous != main.framebufferObject) {
			return false;
		}
		ensureReloadListener(mc);
		if (canReuse(mc, main, levels)) {
			try {
				cachedFrame = frame;
				OriginScreenRenderer.drawFullscreenTexture(PYRAMID[0]);
				if (com.origin.client.client.BenchProf.ON) {
					com.origin.client.client.BenchProf.countBlur(true);
				}
				return true;
			} catch (Throwable t) {
				broken = true;   // vanilla's darkened backdrop from now on
				com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur failed; using vanilla's menu backdrop", t);
				return false;
			}
		}
		cacheValid = false;
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
			// Climb back up one halving at a time. Stretching the smallest level
			// straight to full size (up to 8x) shows its pixel grid -- the
			// "pixelated Esc menu" of Will's 2026-10-20 playtest; 2x bilinear steps
			// compound into a smooth falloff. The levels are rebuilt on every full
			// blur, so overwriting them here is free; PYRAMID[0] ends up holding the
			// finished backdrop, which is what the frozen-world reuse draws.
			for (int i = levels - 1; i >= 1; i--) {
				PYRAMID[i - 1].bindFramebuffer(true);
				OriginScreenRenderer.drawFullscreenTexture(PYRAMID[i]);
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
		OriginScreenRenderer.drawFullscreenTexture(PYRAMID[0]);
		remember(mc, main, levels);
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countBlur(false);
		}
		return true;
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(Gfx g, GuiScreen screen) {
		if (transparent()) {
			return;
		}
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
