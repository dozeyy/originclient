package com.origin.client.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.util.glu.Project;

/**
 * 1.8.9's title panorama (panorama_0..5), drawn the way 1.21.1 draws its own:
 * one sharp pass at full window resolution, a slow yaw spin and a FIXED small
 * downward pitch. Used behind Origin's title and every out-of-world menu.
 *
 * <p>Why not vanilla's own renderer (GuiMainMenu.renderSkybox, the method this
 * class used to drive until 2026-10-27; Will: "way to blurry ... you look at the
 * floor when turning around"). javap of 1.8.9 GuiMainMenu (MCP stable_22):
 * <ul>
 *   <li>renderSkybox sets a 256x256 viewport, draws drawPanorama into it, runs
 *       rotateAndBlurSkybox SEVEN times (glCopyTexSubImage2D into a 256 px
 *       texture + jittered redraws), then stretches that 256 px image over the
 *       whole screen: the blur and the low resolution.</li>
 *   <li>drawPanorama pitches the camera by
 *       {@code sin((panoramaTimer + pt) / 400) * 25 + 20} degrees, i.e. swinging
 *       between -5 and 45 degrees down: the "looking at the floor".</li>
 * </ul>
 * 1.21.1 (javap PanoramaRenderer / CubeMap, Mojang names): pitch a constant 10
 * degrees, spin {@code += 0.1} degree per tick (= 2 degrees a second, the same
 * yaw speed as 1.8.9's {@code -panoramaTimer * 0.1}), perspective 85 degrees
 * vertical at the window's aspect, near 0.05, far 10. 1.8.9's own crop of its
 * 120-degree square render also works out to about 85 degrees vertical, so the
 * framing stays what 1.8.9 players know. No blur: DESIGN-SYSTEM.md "In-game
 * title": blurred on 1.20.5+ only, dim only before.
 *
 * <p>Face order and orientation are vanilla 1.8.9's (drawPanorama: face 1 yaw
 * +90, 2 yaw 180, 3 yaw -90, 4 pitch +90, 5 pitch -90, each a quad at z = 1),
 * minus its projection-space 90-degree roll, which only existed to undo the
 * transposed UVs of renderSkybox's final stretch. So the standard
 * panorama_0..5 files (0 front, 1 right, 2 back, 3 left, 4 up, 5 down) and
 * OriginDevPanorama's shots map exactly as before.
 *
 * <p>Plain GL through GlStateManager into whatever target is bound (the game's
 * framebuffer, or the window with framebuffers off), no viewport change, no
 * mixin. Cost: 6 textured quads a frame, no allocation (the ResourceLocations
 * are built once). Fail-soft: the first throw latches this off for the session,
 * logs once, and every caller falls back to its plain dark fill.
 */
public final class MenuPanorama {
	private static final ResourceLocation[] FACES = new ResourceLocation[6];

	static {
		for (int i = 0; i < 6; i++) {
			FACES[i] = new ResourceLocation("textures/gui/title/background/panorama_" + i + ".png");
		}
	}

	/** 1.21.1 PanoramaRenderer: cubeMap.render(mc, 10.0F, -spin, alpha). */
	private static final float PITCH = 10f;
	/** 0.1 degree per tick at 20 ticks a second = 0.002 degree per ms; 180 s per turn. */
	private static final float DEG_PER_MS = 0.002f;
	private static final long TURN_MS = 180_000L;
	/** 1.21.1 CubeMap: Matrix4f.setPerspective(1.4835298f (85 deg), aspect, 0.05f, 10.0f). */
	private static final float FOV = 85f;

	private static volatile boolean broken = false;
	private static long startMs = -1;

	private MenuPanorama() {
	}

	/** False once the panorama has failed this session. */
	public static boolean available() {
		return !broken;
	}

	/**
	 * Draws the panorama over the whole window. The GUI size arguments are kept
	 * for the callers; the cube fills the current viewport whatever its size.
	 * False if it could not (the caller fills instead).
	 */
	public static boolean draw(int guiW, int guiH) {
		if (broken) {
			return false;
		}
		Minecraft mc = Minecraft.getMinecraft();
		boolean projPushed = false;
		int viewPushes = 0;
		try {
			long now = Minecraft.getSystemTime();
			if (startMs < 0) {
				startMs = now;
			}
			float yaw = ((now - startMs) % TURN_MS) * DEG_PER_MS;
			float aspect = mc.displayHeight > 0 ? mc.displayWidth / (float) mc.displayHeight : 16f / 9f;

			// Put back exactly what the GUI had (the recipe trap: vanilla's
			// drawPanorama left depth and cull ON and broke later GUI draws).
			boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
			boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
			boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
			boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);

			GlStateManager.matrixMode(GL11.GL_PROJECTION);
			GlStateManager.pushMatrix();
			projPushed = true;
			GlStateManager.loadIdentity();
			Project.gluPerspective(FOV, aspect, 0.05f, 10f);
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);
			GlStateManager.pushMatrix();
			viewPushes = 1;
			GlStateManager.loadIdentity();
			// Same chain as 1.21.1's CubeMap: flip, fixed pitch, spin.
			GlStateManager.rotate(180f, 1f, 0f, 0f);
			GlStateManager.rotate(PITCH, 1f, 0f, 0f);
			GlStateManager.rotate(-yaw, 0f, 1f, 0f);

			GlStateManager.disableDepth();
			GlStateManager.depthMask(false);
			GlStateManager.disableCull();
			GlStateManager.disableBlend();
			GlStateManager.disableAlpha();
			GlStateManager.disableLighting();
			GlStateManager.disableFog();
			GlStateManager.enableTexture2D();
			GlStateManager.color(1f, 1f, 1f, 1f);

			Tessellator tess = Tessellator.getInstance();
			WorldRenderer wr = tess.getWorldRenderer();
			for (int face = 0; face < 6; face++) {
				GlStateManager.pushMatrix();
				viewPushes = 2;
				switch (face) {
					case 1: GlStateManager.rotate(90f, 0f, 1f, 0f); break;
					case 2: GlStateManager.rotate(180f, 0f, 1f, 0f); break;
					case 3: GlStateManager.rotate(-90f, 0f, 1f, 0f); break;
					case 4: GlStateManager.rotate(90f, 1f, 0f, 0f); break;
					case 5: GlStateManager.rotate(-90f, 1f, 0f, 0f); break;
					default:
				}
				mc.getTextureManager().bindTexture(FACES[face]);
				// Smooth (not vanilla's NEAREST) so a 768 px face scaled to the
				// window stays clean, and clamped so linear filtering never pulls
				// the opposite edge into the cube's seams.
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
				GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
				// drawPanorama's quad (javap): (-1,-1,1)(0,0) (1,-1,1)(1,0) (1,1,1)(1,1) (-1,1,1)(0,1).
				wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
				wr.pos(-1.0, -1.0, 1.0).tex(0.0, 0.0).endVertex();
				wr.pos(1.0, -1.0, 1.0).tex(1.0, 0.0).endVertex();
				wr.pos(1.0, 1.0, 1.0).tex(1.0, 1.0).endVertex();
				wr.pos(-1.0, 1.0, 1.0).tex(0.0, 1.0).endVertex();
				tess.draw();
				GlStateManager.popMatrix();
				viewPushes = 1;
			}

			GlStateManager.popMatrix();
			viewPushes = 0;
			GlStateManager.matrixMode(GL11.GL_PROJECTION);
			GlStateManager.popMatrix();
			projPushed = false;
			GlStateManager.matrixMode(GL11.GL_MODELVIEW);

			GlStateManager.depthMask(true);
			if (depth) {
				GlStateManager.enableDepth();
			}
			if (cull) {
				GlStateManager.enableCull();
			}
			if (blend) {
				GlStateManager.enableBlend();
			}
			if (alpha) {
				GlStateManager.enableAlpha();
			}
			GlStateManager.color(1f, 1f, 1f, 1f);
			return true;
		} catch (Throwable t) {
			broken = true;
			try {
				GlStateManager.matrixMode(GL11.GL_MODELVIEW);
				for (; viewPushes > 0; viewPushes--) {
					GlStateManager.popMatrix();
				}
				if (projPushed) {
					GlStateManager.matrixMode(GL11.GL_PROJECTION);
					GlStateManager.popMatrix();
				}
				GlStateManager.matrixMode(GL11.GL_MODELVIEW);
				GlStateManager.depthMask(true);
				GlStateManager.enableAlpha();
				GlStateManager.color(1f, 1f, 1f, 1f);
			} catch (Throwable ignored) {
				// nothing more to restore
			}
			com.origin.client.OriginClient.LOGGER.error("Origin panorama backdrop failed; plain backdrop for this session", t);
			return false;
		}
	}
}
