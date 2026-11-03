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
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.lang.ref.WeakReference;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * Pre-1.20.5 best fit: vanilla has no menu blur -- every in-world screen (pause,
 * options, inventories alike) just darkens the world with a flat gradient. On
 * 1.8.9 that gradient is GuiScreen.drawWorldBackground(int), which
 * GuiScreenBackgroundMixin swaps for blur + tint. The blur is the one every
 * version from 1.16.5 up runs: Origin's own post chain
 * (shaders/post/origin_menu_blur.json) built only from vanilla's "blur" program
 * (shaders/program/blur.json, shipped in 1.8.9 too): three horizontal + vertical
 * box-blur rounds at radius R, R/2 and R/4, all at FULL resolution -- stacked
 * box blurs converge on 1.21.1's soft gaussian. Strength is R (default 4).
 * The chain writes its own "result" target, never the main framebuffer, so the
 * finished backdrop is drawn back 1:1 and kept for the frozen-world reuse.
 *
 * Why not the old halving pyramid: it shrank the frame to 1/8 size and
 * bilinear-stretched it back. A bilinear stretch of a 1/8 image is piecewise
 * linear between samples 8 screen px apart -- climbing back in 2x steps
 * composes to the same thing -- so every edge in the world showed an 8 px
 * grid: the "pixelated Esc menu" (Will, 2026-10-20 and again 2026-11-02).
 *
 * The chain is a standalone ShaderGroup, never EntityRenderer's single effect
 * slot (OptiFine replaces EntityRenderer and its shader packs drive that slot);
 * Color Saturation's ColorGrade runs the same way. It reads mc.getFramebuffer(),
 * so it runs only while that framebuffer is the one being drawn to and post
 * chains are usable (WorldHooks.postChainsUsable: no OptiFine shader pack, no
 * Fast Render, framebuffers on). Otherwise -> vanilla's darkened backdrop, which
 * has nothing to pixelate.
 *
 * Frozen-world reuse (Phase 6 U6.3 pick 2): in single-player a pausing screen
 * (Minecraft.isGamePaused) stops the world, so every frame of the backdrop would
 * be the same image -- the chain runs once and its "result" target is drawn
 * over the screen after that. The reuse is allowed only while all of
 * these hold, otherwise the old per-frame blur runs (so the cost is never worse
 * than before, and a stale image is never shown):
 *   - the game is paused AND the world and its total time are the ones captured
 *     (multiplayer, LAN-open worlds and inventories are never paused: they keep
 *     moving, and a rate limit would show the lag, so they re-blur every frame);
 *   - the blur was used on the previous frame too (a frame without a blur --
 *     screen closed, other screen, shaders on -- ends the reuse);
 *   - no click, key, wheel or drag happened since (a slider or toggle in a menu
 *     can change the world image; HudHooks calls invalidate());
 *   - same framebuffer size and texture (window resize), GUI scale, blur radius
 *     and resource generation (a resource reload);
 *   - GRACE_MS have passed since the last input and since the blur started (a
 *     pause just opened): a setting changed in a menu (render distance, graphics,
 *     OptiFine toggles) reloads chunks over many frames, and a world still
 *     loading when the pause opens builds them too, so the blur follows the
 *     live world through that window instead of freezing a half-built one;
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
	private static final ResourceLocation CHAIN = new ResourceLocation("shaders/post/origin_menu_blur.json");
	// Radius multiplier per pass, in origin_menu_blur.json's pass order (= 1.21.1's
	// blur.json RadiusMultiplier values, and 1.18.2-1.20.4's PASS_SCALE).
	private static final float[] PASS_SCALE = {1f, 1f, 0.5f, 0.5f, 0.25f, 0.25f};
	private static ShaderGroup chain;
	private static Framebuffer result;         // the chain's last target: the finished backdrop
	private static int chainW = -1, chainH = -1;
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
	private static long quietUntilMs;          // no reuse before this (see GRACE_MS)
	private static long lastBlurFrame = -10;   // frame of the previous blur() call
	/** After an input or the start of a blur sequence the world may still be changing. */
	private static final long GRACE_MS = 1500L;
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
		quietUntilMs = Minecraft.getSystemTime() + GRACE_MS;
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
		if (!cacheValid || !frameHookSeen || cachedFrame < frame - 1 || result == null) {
			return false;
		}
		if (levels != cachedLevels || main.framebufferWidth != cachedW || main.framebufferHeight != cachedH
				|| main.framebufferTexture != cachedTex || mc.gameSettings.guiScale != cachedGuiScale
				|| reloadGen != cachedReload) {
			return false;
		}
		long now = Minecraft.getSystemTime();
		if (now < quietUntilMs || now - cachedAtMs >= REFRESH_MS) {
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
				&& !(com.origin.client.client.BenchSwitches.INV_BLUR_OFF && screen instanceof GuiContainer)
				&& OpenGlHelper.isFramebufferEnabled() && !IrisBridge.shadersActive()
				&& com.origin.client.client.hooks.WorldHooks.postChainsUsable();
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
		int levels = radius;   // the reuse key: a different radius is a different image
		Framebuffer main = mc.getFramebuffer();
		int previous = GL11.glGetInteger(OriginScreenRenderer.GL_FRAMEBUFFER_BINDING);
		if (main == null || previous != main.framebufferObject) {
			return false;
		}
		ensureReloadListener(mc);
		if (lastBlurFrame < frame - 1) {
			quietUntilMs = Minecraft.getSystemTime() + GRACE_MS;   // a new sequence: the pause just opened
		}
		lastBlurFrame = frame;
		if (canReuse(mc, main, levels)) {
			try {
				cachedFrame = frame;
				OriginScreenRenderer.drawFullscreenTexture(result);
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
			// createBindFramebuffers rebuilds the targets: look "result" up every time.
			result = chain.getFramebufferRaw("result");
			if (result == null) {
				throw new IllegalStateException("menu blur chain has no result target");
			}
			java.util.List<Shader> passes = ((com.origin.client.client.mixin.world.ShaderGroupAccessor) chain).origin$listShaders();
			for (int i = 0; i < passes.size(); i++) {
				float scale = PASS_SCALE[Math.min(i, PASS_SCALE.length - 1)];
				ShaderUniform u = passes.get(i).getShaderManager().getShaderUniform("Radius");
				if (u != null) {
					u.set((float) Math.max(1, Math.round(radius * scale)));
				}
			}
			// EntityRenderer.updateCameraAndRender's own post-effect sequence (as ColorGrade).
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
				GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			}
		} catch (Throwable t) {
			broken = true;   // vanilla's darkened backdrop from now on
			result = null;
			try {
				if (chain != null) {
					chain.deleteShaderGroup();   // free its two full-window targets
				}
			} catch (Throwable ignored) {
			}
			chain = null;
			com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur failed; using vanilla's menu backdrop", t);
			OriginScreenRenderer.restoreTarget(previous);
			restoreGuiState();
			return false;
		}
		OriginScreenRenderer.restoreTarget(previous);
		OriginScreenRenderer.drawFullscreenTexture(result);   // same size as main: one texel per pixel
		restoreGuiState();
		remember(mc, main, levels);
		if (com.origin.client.client.BenchProf.ON) {
			com.origin.client.client.BenchProf.countBlur(false);
		}
		return true;
	}

	// The state the GUI pass draws under, after the chain's shader passes (they
	// turn off blend, depth, alpha, fog and lighting, and leave texture 0 bound).
	private static void restoreGuiState() {
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
