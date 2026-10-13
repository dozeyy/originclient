package com.origin.client.client.render;

import com.origin.client.client.gui.Gfx;
import net.minecraft.client.LoadingScreenRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * 1.8.9 ONLY: the singleplayer "Loading world / Building terrain 27%" and
 * "Saving world" screens. Unlike later versions (LevelLoadingScreen /
 * ProgressScreen, ordinary Screens a mixin can take over), 1.8.9 draws these
 * from Minecraft.loadingScreen -- a LoadingScreenRenderer, NOT a GuiScreen --
 * which paints and swaps buffers itself from inside the world-load call, outside
 * the render loop. So the Origin scene replaces the renderer object: MenuHooks
 * swaps this into mc.loadingScreen (a public field) on the client tick and
 * re-claims it if OptiFine or anything else re-creates the vanilla one (the
 * boot-tested approach of the pre-port 1.8.9 module).
 *
 * Every public entry vanilla calls is overridden, so vanilla's private draw path
 * is never reached. Fail-soft: while OriginScreenRenderer is broken every call
 * delegates to the vanilla implementation instead.
 */
public final class OriginLoadingRenderer extends LoadingScreenRenderer {

	private final Minecraft mc;
	private String title = "";
	private String stage = "";
	private int progress = -1;
	private long lastDraw;

	public OriginLoadingRenderer(Minecraft mc) {
		super(mc);
		this.mc = mc;
	}

	@Override
	public void resetProgressAndMessage(String message) {
		if (!OriginScreenRenderer.isActive()) {
			super.resetProgressAndMessage(message);
			return;
		}
		title = message != null ? message : "";
		stage = "";
		progress = -1;
		draw(true);
	}

	@Override
	public void displaySavingString(String message) {
		if (!OriginScreenRenderer.isActive()) {
			super.displaySavingString(message);
			return;
		}
		title = message != null ? message : "";
		stage = "";
		progress = -1;
		draw(true);
	}

	@Override
	public void displayLoadingString(String message) {
		if (!OriginScreenRenderer.isActive()) {
			super.displayLoadingString(message);
			return;
		}
		stage = message != null ? message : "";
		progress = -1;
		draw(true);
	}

	@Override
	public void setLoadingProgress(int p) {
		if (!OriginScreenRenderer.isActive()) {
			super.setLoadingProgress(p);
			return;
		}
		progress = p;
		draw(false);
	}

	@Override
	public void setDoneWorking() {
		if (!OriginScreenRenderer.isActive()) {
			super.setDoneWorking();
			return;
		}
		progress = -1;
		stage = "";
	}

	private void draw(boolean force) {
		long now = System.currentTimeMillis();
		// Vanilla throttles to ~10 fps so loading isn't slowed by drawing; 30 fps
		// keeps the bar smooth at negligible cost.
		if (!force && now - lastDraw < 33) {
			return;
		}
		lastDraw = now;
		try {
			if (!Display.isCreated()) {
				return;
			}
			// Straight to the window's back buffer: these calls arrive from inside
			// the client tick (world load / save), between frames, and vanilla's own
			// path ends on the default framebuffer too.
			if (OpenGlHelper.isFramebufferEnabled()) {
				OpenGlHelper.glBindFramebuffer(OpenGlHelper.GL_FRAMEBUFFER, 0);
			}
			GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
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

			if (!OriginScreenRenderer.renderLoadingScene(new Gfx(), title, stage, progress)) {
				return;
			}
			mc.updateDisplay();
		} catch (Throwable t) {
			// renderLoadingScene latches the renderer itself; anything else here
			// (GL state, display) just skips this frame -- the next call retries.
			com.origin.client.OriginClient.LOGGER.debug("Origin loading frame skipped", t);
		}
	}
}
