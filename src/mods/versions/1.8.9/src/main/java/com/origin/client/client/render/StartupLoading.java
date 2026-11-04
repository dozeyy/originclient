package com.origin.client.client.render;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.fml.common.ProgressManager;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.util.Iterator;

/**
 * 1.8.9 ONLY: Origin's STARTUP loading screen, from the first frame after the window opens to the
 * title screen -- in place of FML's splash (Mojang logo, Forge anvil, red bars) and vanilla's
 * Mojang screen (Will, 2026-11-04: "remove the forge loading screen ... change it to our custom
 * one"). The picture is LoadingLook's, as on every version (1.21.1's startup overlay: the real
 * logo, the plain capsule bar, "Loading resources NN%" in Minecraft's font on the opaque backdrop).
 *
 * <p>How (Forge 11.15.1.2318 javap, 2026-11-04):
 * <ul>
 *   <li>FML's SplashProgress is switched off: SplashProgressMixin answers its
 *       getBool("enabled") with false (and the launcher writes enabled=false into
 *       config/splash.properties, ForgeSplashOff). Off, FML never starts its splash thread and
 *       the main thread keeps the GL context for the whole load, so this class can draw on it.</li>
 *   <li>First frame: SplashProgress.start's RETURN ({@link #begin}), inside
 *       FMLClientHandler.beginMinecraftLoading, right after Minecraft.createDisplay and before
 *       any mod is constructed.</li>
 *   <li>Every later frame: FMLClientHandler.processWindowMessages ({@link #pump}), which
 *       ProgressManager.push, pop and ProgressBar.step all call -- construction, pre-init, init,
 *       post-init, the texture/model reload. At most ~30 fps; nothing between two steps can
 *       draw (one long step = one still frame, as Forge's model loader already shows).</li>
 *   <li>Vanilla's Mojang screen (Minecraft.drawSplashScreen, which startGame reaches through
 *       SplashProgress.drawVanillaScreen once FML's splash is off) is replaced by one of these
 *       frames (MinecraftSplashScreenMixin).</li>
 *   <li>Last frame: FMLClientHandler.onInitializationComplete ({@link #finish}): the bar full,
 *       "Loading resources 100%" -- exactly the picture the title then holds over its first
 *       frames before it dissolves (OriginTitleScreen, OriginScreenRenderer.renderStartupLoading),
 *       so the hand-off has no seam.</li>
 * </ul>
 *
 * <p>Progress is real: Forge's ProgressManager bar stack (outermost bar's steps, each refined
 * by the bars nested in it), through LoadBar (the same monotonic glide + creep 1.21.1's startup
 * bar uses) so it never jumps backward when one bar ends and the next begins.
 *
 * <p>Drawn straight to the window (framebuffer 0) and shown with Display.update (which also
 * answers Windows, so the window never greys out as "Not Responding" during a long load).
 * Before Minecraft's texture manager and font exist, so LoadingLook's boot path is used for
 * the whole startup: the logo as a raw GL texture, the caption from BootFont. Never drawn
 * inside a texture step (an atlas upload relies on its texture staying bound); the texture
 * binding and framebuffer are put back after every frame anyway.
 *
 * <p>Fail-soft (mandate 4): the first error turns it off for the session (the window then
 * shows the last frame until the title, as vanilla's static screen did); it never draws
 * unless this thread owns the GL context, and never after the startup has finished.
 */
public final class StartupLoading {
	private StartupLoading() {
	}

	private static final String CAPTION = "Loading resources ";
	private static final long FRAME_MS = 33;
	/** LoadBar's glide time constant (ms), as 1.21.1's startup bar. */
	private static final double TAU_MS = 140.0;

	// true from begin() to finish(); off for good after finish() or the first failure.
	private static volatile boolean active, over;
	private static long lastDraw;
	private static final LoadBar BAR = new LoadBar();
	// The one "reload" the bar follows: the whole startup is one load to the player.
	private static final Object LOAD = new Object();
	private static int pctShown = -1;
	private static String caption = CAPTION + "0%";

	/** True while Origin's startup screen owns the window (vanilla's Mojang screen is skipped). */
	public static boolean showing() {
		return active;
	}

	/** SplashProgress.start RETURN: FML's splash is off, the window is open -- the first frame. */
	public static void begin() {
		if (over || active) {
			return;
		}
		active = true;
		draw(true, false);
		if (active) {
			com.origin.client.OriginClient.LOGGER.info("Origin startup loading screen is up (FML splash off)");
		}
	}

	/** FMLClientHandler.processWindowMessages: a loading step started or ended -- redraw (rate-limited). */
	public static void pump() {
		if (active && !ReloadOverlay.inTextureStep()) {
			draw(false, false);
		}
	}

	/** Minecraft.drawSplashScreen (vanilla's Mojang screen): one of Origin's frames instead. True = drawn. */
	public static boolean replaceMojangScreen() {
		if (!active) {
			return false;
		}
		draw(true, false);
		return active;
	}

	/** FMLClientHandler.onInitializationComplete: the bar full, then the title takes over. */
	public static void finish() {
		if (!active) {
			over = true;
			return;
		}
		draw(true, true);
		active = false;
		over = true;
		try {
			OriginScreenRenderer.releaseBootLogo();
			BootFont.release();
		} catch (Throwable ignored) {
			// two small textures left behind at worst
		}
	}

	/** The startup's real progress, 0..1, from Forge's bar stack (outermost first). */
	private static double rawProgress() {
		// Walk outermost -> innermost, remembering each level's share; then fold innermost -> outermost.
		double[] done = FOLD_DONE;
		int[] steps = FOLD_STEPS;
		int n = 0;
		Iterator<ProgressManager.ProgressBar> it = ProgressManager.barIterator();
		while (it.hasNext() && n < done.length) {
			ProgressManager.ProgressBar b = it.next();
			int total = Math.max(1, b.getSteps());
			// step() counts a step when it STARTS, so step - 1 are finished.
			done[n] = Math.max(0, Math.min(total, b.getStep() - 1));
			steps[n] = total;
			n++;
		}
		double f = 0;
		for (int i = n - 1; i >= 0; i--) {
			f = Math.min(1.0, (done[i] + f) / steps[i]);
		}
		return f;
	}

	// rawProgress's scratch (render thread only; no allocation per frame).
	private static final double[] FOLD_DONE = new double[16];
	private static final int[] FOLD_STEPS = new int[16];

	private static void draw(boolean force, boolean complete) {
		long now = System.currentTimeMillis();
		if (!force && now - lastDraw < FRAME_MS) {
			return;
		}
		lastDraw = now;
		Minecraft mc = Minecraft.getMinecraft();
		int boundTexture = -1, boundFbo = -1;
		boolean fbo = false;
		try {
			// FML's splash thread holds the context when it runs (SplashProgressMixin failed to
			// apply): never draw then -- its screen stays, as before this change.
			if (!Display.isCreated() || !Display.isCurrent() || !OriginScreenRenderer.isActive()) {
				return;
			}
			double fill = complete ? 1.0 : BAR.update(LOAD, false, rawProgress(), SceneClock.sceneMs(), TAU_MS);
			int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, fill)) * 100);
			if (pct != pctShown) {
				pctShown = pct;
				caption = CAPTION + pct + "%";
			}

			boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			fbo = OpenGlHelper.isFramebufferEnabled();
			if (fbo) {
				// Straight to the window: Minecraft's own framebuffer is not drawn to the screen until
				// the game loop runs. The frame's binding is put back afterwards.
				boundFbo = GL11.glGetInteger(OriginScreenRenderer.GL_FRAMEBUFFER_BINDING);
				OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
			}
			int w = Math.max(1, mc.displayWidth), h = Math.max(1, mc.displayHeight);
			int gs = OriginScreenRenderer.scale();
			GlStateManager.viewport(0, 0, w, h);
			GlStateManager.clearColor(0f, 0f, 0f, 1f);
			GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			GlStateManager.matrixMode(GL11.GL_PROJECTION);
			GlStateManager.loadIdentity();
			// The GUI's own projection (ScaledResolution's size: window / scale, rounded up), as vanilla's splash.
			GlStateManager.ortho(0.0, (w + gs - 1) / gs, (h + gs - 1) / gs, 0.0, 1000.0, 3000.0);
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			GlStateManager.loadIdentity();
			GlStateManager.translate(0f, 0f, -2000f);
			GlStateManager.disableLighting();
			GlStateManager.disableFog();
			GlStateManager.disableDepth();
			GlStateManager.disableCull();
			GlStateManager.enableTexture2D();

			LoadingLook.draw(new Gfx(), 1f, fill, caption, true);

			// Leave the state the way vanilla's splash leaves it for the rest of startGame.
			GlStateManager.disableBlend();
			GlStateManager.enableAlpha();
			GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1f);
			GlStateManager.enableDepth();
			GlStateManager.enableCull();
			GlStateManager.color(1f, 1f, 1f, 1f);
			Display.update();
		} catch (Throwable t) {
			active = false;
			over = true;
			com.origin.client.OriginClient.LOGGER.warn("Origin startup loading screen failed; the load continues without it", t);
		} finally {
			try {
				if (fbo && boundFbo >= 0) {
					OriginScreenRenderer.restoreTarget(boundFbo);
				}
				if (boundTexture >= 0) {
					GlStateManager.bindTexture(boundTexture);
				}
			} catch (Throwable ignored) {
				// nothing more to put back
			}
		}
	}
}
