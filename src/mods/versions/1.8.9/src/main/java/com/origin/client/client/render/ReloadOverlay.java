package com.origin.client.client.render;

import com.origin.client.client.gui.Gfx;
import com.origin.client.client.hud.FrameStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraftforge.fml.common.ProgressManager;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import java.util.Iterator;

/**
 * 1.8.9 ONLY: the resource RELOAD screen (packs applied, language, mipmaps, the Pack Hub,
 * OptiFine options that reload, F3+T) -- 1.21.1's reload overlay, which 1.8.9 never had.
 *
 * <p>Why: vanilla 1.8.9 reloads on the render thread with no frame drawn and no window
 * messages pumped until it ends, so the picture froze on its last frame (a half-pressed
 * button) for seconds, and past ~5 s Windows greyed the window as "Not Responding".
 * Now the same loading picture as every other Origin loading screen
 * (OriginScreenRenderer.renderLoadingScene: the real logo, the plain bar, 1.21.1's
 * "Loading resources N%" caption) is drawn and shown -- which also pumps the window's
 * messages -- at each reload step.
 *
 * <p>Hooks (ReloadProgressMixin): SimpleReloadableResourceManager.notifyReloadListeners
 * HEAD/RETURN ({@link #begin} / {@link #end}) and before each listener ({@link #step}: the
 * bar is listeners done / total); Forge's ProgressManager.ProgressBar.step inside the long
 * listeners ({@link #pump}: model loading) only redraws, at most ~30 fps.
 *
 * <p>Separate from the STARTUP loading screen on purpose (another piece of work owns that):
 * nothing draws until the game loop has rendered a frame (FrameStats), so the startup
 * reload -- which runs while Forge's splash owns the GL context on its own thread -- is
 * never touched. Never drawn inside a texture step (Forge's "Texture ..." progress bars:
 * the atlas being uploaded relies on its texture staying bound); the texture binding is
 * put back after every draw anyway. Fail-soft: the first error turns it off for the
 * session (the reload then freezes the picture as vanilla always did).
 */
public final class ReloadOverlay {
	private ReloadOverlay() {
	}

	private static final String CAPTION = "Loading resources ";
	private static final long FRAME_MS = 33;

	private static boolean broken;
	private static boolean active;
	private static int done, total;
	private static long lastDraw;
	// The caption is rebuilt only when the percentage changes (no per-draw garbage).
	private static int pctShown = -1;
	private static String caption = CAPTION + "0%";

	/** notifyReloadListeners HEAD: a reload of {@code listeners} steps starts. */
	public static void begin(int listeners) {
		active = !broken && FrameStats.frames() > 0 && Minecraft.getMinecraft().isCallingFromMinecraftThread();
		done = 0;
		total = Math.max(1, listeners);
		if (active) {
			draw(true);
		}
	}

	/** Before each reload listener runs. */
	public static void step() {
		if (!active) {
			return;
		}
		// Drawn before this listener runs: the bar shows the ones already finished.
		draw(false);
		done = Math.min(done + 1, total);
	}

	/** Forge ProgressBar.step inside a long listener: a redraw only (keeps the window responsive). */
	public static void pump() {
		if (active && !inTextureStep()) {
			draw(false);
		}
	}

	/** notifyReloadListeners RETURN: the game's own frames take over again. */
	public static void end() {
		if (active) {
			done = total;
			draw(true);
		}
		active = false;
	}

	private static boolean inTextureStep() {
		try {
			Iterator<ProgressManager.ProgressBar> it = ProgressManager.barIterator();
			while (it.hasNext()) {
				String title = it.next().getTitle();
				if (title != null && title.startsWith("Texture")) {
					return true;
				}
			}
			return false;
		} catch (Throwable t) {
			return true;   // can't tell: don't draw
		}
	}

	private static void draw(boolean force) {
		long now = System.currentTimeMillis();
		if (!force && now - lastDraw < FRAME_MS) {
			return;
		}
		lastDraw = now;
		if (!OriginScreenRenderer.isActive()) {
			return;   // Origin rendering off or broken: vanilla's frozen frame, as before
		}
		Minecraft mc = Minecraft.getMinecraft();
		int boundTexture = -1;
		try {
			if (!Display.isCreated()) {
				return;
			}
			boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			int pct = (int) (100L * done / total);
			if (pct != pctShown) {
				pctShown = pct;
				caption = CAPTION + pct + "%";
			}
			// The same frame setup as OriginLoadingRenderer (the world-load screen, also drawn
			// between frames from inside a game call): into the game's framebuffer, then shown.
			boolean fbo = OpenGlHelper.isFramebufferEnabled() && mc.getFramebuffer() != null;
			if (fbo) {
				mc.getFramebuffer().bindFramebuffer(true);
			} else {
				GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
			}
			ScaledResolution sr = new ScaledResolution(mc);
			GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			GlStateManager.matrixMode(GL11.GL_PROJECTION);
			GlStateManager.loadIdentity();
			GlStateManager.ortho(0.0, sr.getScaledWidth_double(), sr.getScaledHeight_double(), 0.0, 1000.0, 3000.0);
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			GlStateManager.loadIdentity();
			GlStateManager.translate(0f, 0f, -2000f);
			GlStateManager.disableLighting();
			GlStateManager.disableFog();
			GlStateManager.disableDepth();
			GlStateManager.enableTexture2D();

			boolean drew = OriginScreenRenderer.renderLoadingScene(new Gfx(), caption, null, pct);
			if (fbo) {
				mc.getFramebuffer().unbindFramebuffer();
				if (drew) {
					mc.getFramebuffer().framebufferRender(mc.displayWidth, mc.displayHeight);
				}
			}
			GlStateManager.enableDepth();
			if (drew) {
				mc.updateDisplay();
			}
		} catch (Throwable t) {
			broken = true;
			active = false;
			com.origin.client.OriginClient.LOGGER.warn("Origin reload screen failed; reloads show the frozen frame again", t);
		} finally {
			if (boundTexture >= 0) {
				try {
					GlStateManager.bindTexture(boundTexture);
				} catch (Throwable ignored) {
					// nothing more to put back
				}
			}
		}
	}
}
