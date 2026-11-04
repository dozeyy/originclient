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
 *   <li>Last frame: FMLClientHandler.onInitializationComplete ({@link #finish}): the load is
 *       done, the bar starts its glide to full. The title then holds the same picture over its
 *       first frames (OriginTitleScreen, OriginScreenRenderer.renderStartupLoading) and that hold
 *       reads the bar from here ({@link #holdFill}), so the glide finishes there and the
 *       hand-off has no seam (1.21.1's overlay also finishes its bar over the settling title).</li>
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
	// The startup screen drew at least once: the title's hold continues its bar (holdFill).
	private static boolean ran;
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
		handRealSizeToGame();
		try {
			OriginScreenRenderer.releaseBootLogo();
			BootFont.release();
		} catch (Throwable ignored) {
			// two small textures left behind at worst
		}
	}

	/**
	 * B189PRE-1: a resize or maximise during the startup. Minecraft reads a new window size only
	 * in checkWindowResize, from Display.wasResized(), which is true for ONE Display.update; this
	 * screen's own updates spend it, so the title would keep drawing (and reading the mouse) at
	 * the old size until the next resize. When the window's real size differs, vanilla's own
	 * resize step runs now, before the title's first frame. Windowed only, as checkWindowResize.
	 */
	private static void handRealSizeToGame() {
		try {
			Minecraft mc = Minecraft.getMinecraft();
			int w = Display.getWidth(), h = Display.getHeight();
			if (mc.isFullScreen() || w <= 0 || h <= 0 || (w == mc.displayWidth && h == mc.displayHeight)) {
				return;
			}
			if ((Object) mc instanceof com.origin.client.client.mixin.menus.MinecraftResizeInvoker) {
				((com.origin.client.client.mixin.menus.MinecraftResizeInvoker) (Object) mc).origin$resize(w, h);
			}
		} catch (Throwable t) {
			// the old size until the player's next resize, as before this fix
			com.origin.client.OriginClient.LOGGER.warn("Origin: could not hand the resized window to the title", t);
		}
	}

	/**
	 * The title's settle hold (OriginScreenRenderer.renderSplash): the startup bar finishing its
	 * glide to full; plainly full when the startup screen never drew. Render thread only.
	 */
	static double holdFill() {
		return ran ? BAR.update(LOAD, true, 1.0, nowMs(), TAU_MS) : 1.0;
	}

	/** "Loading resources NN%" for a fill, rebuilt only when the whole percent changes (1.21.1's startup caption). */
	static String captionFor(double fill) {
		int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, fill)) * 100);
		if (pct != pctShown) {
			pctShown = pct;
			caption = CAPTION + pct + "%";
		}
		return caption;
	}

	// Wall time, not SceneClock: the startup draws only between loading steps, seconds apart at
	// times, and the bar's creep and glide must count that time (SceneClock drops long gaps).
	private static double nowMs() {
		return System.nanoTime() / 1_000_000.0;
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
		int boundTexture = -1, boundFbo = -1, savedW = -1, savedH = -1;
		boolean fbo = false;
		try {
			if (!Display.isCreated() || !OriginScreenRenderer.isActive()) {
				return;
			}
			if (!Display.isCurrent()) {
				// FML's splash thread holds the context (SplashProgressMixin's getBool hook did not
				// apply): its screen stays, as before this change, and this one stands down for good.
				active = false;
				over = true;
				return;
			}
			double fill = BAR.update(LOAD, complete, complete ? 1.0 : rawProgress(), nowMs(), TAU_MS);
			String text = captionFor(fill);

			boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			fbo = OpenGlHelper.isFramebufferEnabled();
			if (fbo) {
				// Straight to the window: Minecraft's own framebuffer is not drawn to the screen until
				// the game loop runs. The frame's binding is put back afterwards.
				boundFbo = GL11.glGetInteger(OriginScreenRenderer.GL_FRAMEBUFFER_BINDING);
				OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
			}
			// The window's real size (B189PRE-1): mc.displayWidth/Height only change in
			// checkWindowResize, which does not run during the startup. LoadingLook, scale() and Gfx
			// size themselves from those fields, so they hold the real size for this frame only.
			int w = Math.max(1, Display.getWidth() > 0 ? Display.getWidth() : mc.displayWidth);
			int h = Math.max(1, Display.getHeight() > 0 ? Display.getHeight() : mc.displayHeight);
			savedW = mc.displayWidth;
			savedH = mc.displayHeight;
			mc.displayWidth = w;
			mc.displayHeight = h;
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

			LoadingLook.draw(new Gfx(), 1f, fill, text, true);
			ran = true;

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
			if (savedW >= 0) {
				mc.displayWidth = savedW;   // Minecraft's own resize step owns these (finish)
				mc.displayHeight = savedH;
			}
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
