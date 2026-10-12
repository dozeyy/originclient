package com.origin.client.client.mods;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.origin.client.client.gui.Gfx;
import com.origin.client.client.gui.OriginColorPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.lwjgl.opengl.GL11;

/**
 * Menu Blur (Lunar parity): blurs the world behind in-game menus at the mod's
 * own strength, with a tint per kind of menu.
 *
 * 1.16.5 best fit: vanilla has no menu blur before 1.20.5 -- every in-world
 * screen (pause, options, inventories alike) just darkens the world with a flat
 * gradient in Screen.renderBackground(PoseStack, int). So MenuBlurMixin swaps
 * that gradient for blur + tint, and the blur is the same box-filter pyramid
 * Origin's menus already use for the panorama (OriginScreenRenderer): the frame
 * so far (world + HUD) is halved through LINEAR-filtered targets and stretched
 * back (1.16.5: fixed-function GL, RenderTarget is the concrete off-screen
 * target -- no TextureTarget -- and sources are bound with bindRead()).
 * Strength picks the depth: each halving roughly doubles the radius, so
 * 1 / 2-3 / 4-7 / 8-15 / 16-20 map to 1..5 levels (default 4 = 3 levels).
 * Three kinds of screen:
 *   inventory   -- every AbstractContainerScreen (inventory, chests, furnaces...)
 *   originMenus -- Origin's own screens (the mod menu draws no vanilla backdrop,
 *                  so OriginModMenuScreen calls renderBehind itself)
 *   pause       -- everything else in-world (pause menu, in-game options...)
 * A kind whose toggle is off, the main menu (no world) and the mod being off all
 * leave vanilla's behaviour exactly as it was.
 */
public final class MenuBlur {
	private static final String MOD = "menublur";
	private static final RenderTarget[] PYRAMID = new RenderTarget[5];
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
		return screen != null && !broken && Minecraft.getInstance().level != null
				&& Mods.on(MOD) && Mods.bool(MOD, kind(screen));
	}

	/** Blurs whatever is in the main framebuffer (the world + HUD) at the mod's
	 *  strength. Strength 0 = no blur (the tint still draws). The main target is
	 *  bound again afterwards, so everything after draws to the screen. */
	public static void blur(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		int radius = (int) Math.round(Mods.num(MOD, "strength"));
		if (radius < 1) {
			return;
		}
		int levels = Math.min(PYRAMID.length, 1 + (31 - Integer.numberOfLeadingZeros(radius)));
		RenderTarget main = mc.getMainRenderTarget();
		try {
			int fullW = Math.max(1, main.width), fullH = Math.max(1, main.height);
			for (int i = 0; i < levels; i++) {
				int pw = Math.max(1, fullW >> (i + 1)), ph = Math.max(1, fullH >> (i + 1));
				RenderTarget t = PYRAMID[i];
				if (t == null || t.width != pw || t.height != ph) {
					if (t != null) {
						t.destroyBuffers();
					}
					t = new RenderTarget(pw, ph, false, Minecraft.ON_OSX);
					t.setFilterMode(GL11.GL_LINEAR);
					PYRAMID[i] = t;
				}
			}
			main.setFilterMode(GL11.GL_LINEAR);
			PYRAMID[0].bindWrite(true);
			drawFullscreenTexture(main);
			for (int i = 1; i < levels; i++) {
				PYRAMID[i].bindWrite(true);
				drawFullscreenTexture(PYRAMID[i - 1]);
			}
			main.bindWrite(true);
			drawFullscreenTexture(PYRAMID[levels - 1]);
		} catch (Throwable t) {
			broken = true;   // vanilla's darkened backdrop from the next frame on
			com.origin.client.OriginClient.LOGGER.warn("Origin: Menu Blur failed; using vanilla's menu backdrop", t);
		} finally {
			main.setFilterMode(GL11.GL_NEAREST);
			main.bindWrite(true);
		}
	}

	/** One quad over the whole bound target, in GUI space under the GUI's own
	 *  orthographic projection (OriginScreenRenderer's pyramid helper, same maths:
	 *  render-target textures are bottom-up, hence v = 1 at the top edge).
	 *  Fixed-function: texture via bindRead(), colour via color4f, no shaders. */
	private static void drawFullscreenTexture(RenderTarget source) {
		RenderSystem.disableBlend();
		RenderSystem.disableDepthTest();
		RenderSystem.disableCull();
		RenderSystem.disableAlphaTest();
		RenderSystem.depthMask(false);
		RenderSystem.enableTexture();
		source.bindRead();
		RenderSystem.color4f(1f, 1f, 1f, 1f);
		com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
		double gw = window.getWidth() / window.getGuiScale();
		double gh = window.getHeight() / window.getGuiScale();
		BufferBuilder bb = Tesselator.getInstance().getBuilder();
		bb.begin(GL11.GL_QUADS, DefaultVertexFormat.POSITION_TEX);
		bb.vertex(0.0, gh, 0.0).uv(0f, 0f).endVertex();
		bb.vertex(gw, gh, 0.0).uv(1f, 0f).endVertex();
		bb.vertex(gw, 0.0, 0.0).uv(1f, 1f).endVertex();
		bb.vertex(0.0, 0.0, 0.0).uv(0f, 1f).endVertex();
		Tesselator.getInstance().end();
		source.unbindRead();
		RenderSystem.depthMask(true);
		RenderSystem.enableAlphaTest();
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
	}

	/** The kind's tint over the whole screen (transparent = none). */
	public static void tint(Gfx g, Screen screen) {
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
