package com.origin.client.client.render;

import com.origin.client.client.mixin.menus.GuiMainMenuAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * 1.8.9's own title panorama, drawn by vanilla's own renderer, for any screen
 * (the clean look, 2026-10-19: "the version's own vanilla panorama").
 *
 * <p>Later versions call Screen.renderPanorama from any screen. 1.8.9 keeps its
 * panorama inside GuiMainMenu: renderSkybox draws the panorama_0..5 cube into a
 * 256 px viewport, blurs it seven times through a 256x256 DynamicTexture and
 * stretches the result over the screen. This class owns ONE hidden GuiMainMenu
 * that is never displayed and never initialised (initGui also builds buttons and
 * the Realms notice; updateScreen touches that notice, so it is never ticked
 * either), hands it the two textures initGui would have made, and drives its
 * timer from the clock (20 steps a second, vanilla's tick rate) through
 * GuiMainMenuAccessor. The cube keeps turning from screen to screen instead of
 * restarting on every menu.
 *
 * <p>Cost: exactly vanilla's own title backdrop, once per frame. No allocation
 * per frame. Fail-soft: the first throw (accessor not applied, a GL error)
 * latches this off for the session and every caller falls back to its plain
 * dark fill.
 */
public final class MenuPanorama {
	private static GuiMainMenu hidden;
	private static GuiMainMenuAccessor access;
	private static volatile boolean broken = false;
	private static long startMs = -1;

	private MenuPanorama() {
	}

	/** False once the panorama has failed this session. */
	public static boolean available() {
		return !broken;
	}

	/**
	 * Draws the panorama over the whole GUI ({@code guiW} x {@code guiH}, the
	 * current scaled size). False if it could not (the caller fills instead).
	 */
	public static boolean draw(int guiW, int guiH) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		// renderSkybox ends by binding the GAME's framebuffer and drawing its
		// stretched quad there. When something else is the target (a draw straight
		// to the window), the picture would land where nobody sees it: skip, the
		// caller fills. (With framebuffers off -- OptiFine Fast Render, believed -- every
		// bind is a no-op and everything lands in the window, as on vanilla's title.)
		if (OpenGlHelper.isFramebufferEnabled() && mc.getFramebuffer() != null
				&& org.lwjgl.opengl.GL11.glGetInteger(OriginScreenRenderer.GL_FRAMEBUFFER_BINDING)
				!= mc.getFramebuffer().framebufferObject) {
			return false;
		}
		try {
			if (hidden == null) {
				// The constructor only picks a splash line and reads the GL caps.
				GuiMainMenu screen = new GuiMainMenu();
				Object o = screen;
				if (!(o instanceof GuiMainMenuAccessor)) {
					throw new IllegalStateException("GuiMainMenuAccessor did not apply");
				}
				GuiMainMenuAccessor a = (GuiMainMenuAccessor) o;
				// What GuiMainMenu.initGui makes for itself (javap 1.8.9).
				DynamicTexture viewport = new DynamicTexture(256, 256);
				a.originclient$setViewportTexture(viewport);
				a.originclient$setBackgroundTexture(mc.getTextureManager().getDynamicTextureLocation("origin_panorama", viewport));
				hidden = screen;
				access = a;
			}
			// renderSkybox reads mc, width and height (setWorldAndResolution would run initGui).
			hidden.mc = mc;
			hidden.width = guiW;
			hidden.height = guiH;
			long now = Minecraft.getSystemTime();
			if (startMs < 0) {
				startMs = now;
			}
			long elapsed = now - startMs;
			access.originclient$setPanoramaTimer((int) (elapsed / 50L));
			// GuiMainMenu.drawScreen brackets renderSkybox with the alpha test off.
			GlStateManager.disableAlpha();
			access.originclient$renderSkybox(0, 0, (elapsed % 50L) / 50f);
			GlStateManager.enableAlpha();
			GlStateManager.color(1f, 1f, 1f, 1f);
			return true;
		} catch (Throwable t) {
			broken = true;
			// renderSkybox works with the game's framebuffer unbound and a 256 px
			// viewport; put both back the way it would have.
			try {
				if (OpenGlHelper.isFramebufferEnabled()) {
					mc.getFramebuffer().bindFramebuffer(true);
				}
				GlStateManager.viewport(0, 0, mc.displayWidth, mc.displayHeight);
				GlStateManager.enableAlpha();
			} catch (Throwable ignored) {
				// nothing more to restore
			}
			com.origin.client.OriginClient.LOGGER.error("Origin panorama backdrop failed; plain backdrop for this session", t);
			return false;
		}
	}
}
