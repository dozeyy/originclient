package com.origin.client.client.gui;

import com.origin.client.OriginClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Origin's dark vanilla widgets on 1.8.9 (Will, 2026-10-26: every vanilla button looks like the
 * "Default Dark Mode" pack's, built into the client, never a resource pack in the list). 1.20.2+
 * ship the same art as sprite overrides; here the button art lives in vanilla's widgets.png, which
 * also holds the hotbar, so it is NEVER replaced. Instead, while a vanilla button draws, its
 * widgets.png binds are pointed at Origin's own strip (tools/dark-widgets/make_widgets.py sheet ->
 * originclient:textures/gui/dark_button.png, 200x60: disabled / normal / highlighted rows).
 *
 * <p>Vanilla's blits assume a 256x256 page (UV = px / 256) with the button rows at v 46 / 66 / 86.
 * {@link Page} lays the strip into a transparent 256x256 page at y = 46, so every vanilla blit --
 * GuiButton's two halves, every slider knob (u 0 / 196, v 66), Forge's GuiUtils box -- lands on the
 * matching dark row with no UV remap at all. Text, colours and hover logic stay vanilla's.
 *
 * <p>Hooks (all zero-allocation; this class is the only logic):
 * <ul>
 *   <li>GuiButtonMixin: {@link #beginButton} on drawButton's bindTexture(widgets) (the box), and
 *       {@link #endButton} on its label draw, which comes after mouseDragged (the knob).</li>
 *   <li>TextureManagerDarkWidgetsMixin: {@link #rebind} for knobs that rebind widgets.png inside
 *       mouseDragged (GuiOptionSlider, OptiFine's sliders) -- only while a vanilla button draws.</li>
 *   <li>GuiButtonExtMixin: {@link #forgeButton} for Forge's GuiButtonExt / GuiSlider box.</li>
 *   <li>GuiTextFieldMixin: {@link #textField}, 1.21.1's dark field well.</li>
 * </ul>
 * Fail-soft: any error latches the whole feature off for the session (vanilla widgets), logged once.
 * Origin's own screens never reach these hooks: Origin's skin cancels drawButton before vanilla binds.
 */
public final class OriginDarkWidgets {
	/** Vanilla's button page (GuiButton.buttonTextures is protected; equals() matches it). */
	private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
	/** The 200x60 strip make_widgets.py writes (a player's resource pack can still replace it). */
	private static final ResourceLocation SOURCE = new ResourceLocation("originclient", "textures/gui/dark_button.png");
	/** The registered 256x256 page built from SOURCE (a texture id, not a file). */
	private static final ResourceLocation PAGE = new ResourceLocation("originclient", "dark_widgets_page");

	// 1.21.1's text_field sprites: #505055 ring, #0E0E0F well, white ring when focused.
	private static final int FIELD_EDGE = 0xFF505055;
	private static final int FIELD_EDGE_FOCUSED = 0xFFFFFFFF;
	private static final int FIELD_FILL = 0xFF0E0E0F;

	private static volatile boolean broken = false;
	private static boolean registered = false;
	/** True between a vanilla button's box bind and its label: render thread only, read on every bind. */
	private static boolean drawing = false;
	/** The label hook (end marker) has run at least once: proof it applied, so opening a window is safe. */
	private static boolean endSeen = false;
	/** One "hook active" log line per hook (bit per hook), so a CI run proves each one applied. */
	private static int proven = 0;

	private OriginDarkWidgets() {
	}

	/** GuiButton.drawButton's bindTexture(widgets): the dark page instead, and knob rebinds follow it. */
	public static ResourceLocation beginButton(ResourceLocation texture) {
		if (broken || !isWidgets(texture)) {
			return texture;
		}
		try {
			// Self-check of the end marker: an open window here means the label hook did not run, and
			// left open it would turn the next widgets.png bind (the hotbar) dark -> latch off. Until
			// the end marker has run once (the very first vanilla button, one frame) stay vanilla.
			if (drawing) {
				fail(new IllegalStateException("GuiButton label hook did not close the dark window"));
				return texture;
			}
			if (!endSeen || !ensurePage()) {
				return texture;
			}
			drawing = true;
			proven(1, "GuiButton box");
			return PAGE;
		} catch (Throwable t) {
			fail(t);
			return texture;
		}
	}

	/** GuiButton.drawButton's label: the box and the knob are done. */
	public static void endButton() {
		drawing = false;
		endSeen = true;
	}

	/** TextureManager.bindTexture HEAD (every bind in the game: one static read when no button draws). */
	public static ResourceLocation rebind(ResourceLocation texture) {
		if (!drawing || broken || !isWidgets(texture)) {
			return texture;
		}
		proven(2, "slider knob rebind");
		return PAGE;
	}

	/** Forge GuiButtonExt.drawButton's GuiUtils box (its GuiSlider knob draws on whatever is bound). */
	public static ResourceLocation forgeButton(ResourceLocation texture) {
		if (broken || !isWidgets(texture)) {
			return texture;
		}
		try {
			if (!ensurePage()) {
				return texture;
			}
			proven(4, "Forge GuiButtonExt box");
			return PAGE;
		} catch (Throwable t) {
			fail(t);
			return texture;
		}
	}

	/**
	 * The dark field well, in place of vanilla's grey frame + black box. Same rects vanilla draws
	 * (frame x-1..x+w+1, well x..x+w). True when drawn; the mixin then skips vanilla's background.
	 */
	public static boolean textField(int x, int y, int w, int h, boolean focused) {
		if (broken) {
			return false;
		}
		try {
			Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, focused ? FIELD_EDGE_FOCUSED : FIELD_EDGE);
			Gui.drawRect(x, y, x + w, y + h, FIELD_FILL);
			proven(8, "text field");
			return true;
		} catch (Throwable t) {
			fail(t);
			return false;
		}
	}

	private static boolean isWidgets(ResourceLocation texture) {
		return texture == WIDGETS || (texture != null && WIDGETS.equals(texture));
	}

	/** Registers the page once (render thread). A failed load latches the feature off. */
	private static boolean ensurePage() {
		if (!registered) {
			registered = true;
			Minecraft.getMinecraft().getTextureManager().loadTexture(PAGE, new Page());
		}
		return !broken;
	}

	private static void proven(int bit, String hook) {
		if ((proven & bit) == 0) {
			proven |= bit;
			OriginClient.LOGGER.info("Origin dark widgets: {} hook active", hook);
		}
	}

	private static void fail(Throwable t) {
		drawing = false;
		if (!broken) {
			broken = true;
			OriginClient.LOGGER.error("Origin dark widgets failed; vanilla widgets for this session", t);
		}
	}

	/**
	 * The strip laid into a transparent 256x256 page at y = 46 (scaled by k for a k-times pack).
	 * Re-read on every resource reload (TextureManager reloads registered textures), so a resource
	 * pack's dark_button.png applies. Never throws: TextureManager turns a non-IO exception from
	 * loadTexture into a crash report, so every failure latches the feature off instead.
	 */
	private static final class Page extends AbstractTexture {
		@Override
		public void loadTexture(IResourceManager manager) {
			InputStream in = null;
			try {
				deleteGlTexture();
				in = manager.getResource(SOURCE).getInputStream();
				BufferedImage strip = TextureUtil.readBufferedImage(in);
				int w = strip.getWidth();
				int h = strip.getHeight();
				int k = w / 200;
				if (k < 1 || w != 200 * k || h != 60 * k) {
					throw new IOException("dark_button.png must be 200x60 (or a whole multiple), got " + w + "x" + h);
				}
				BufferedImage page = new BufferedImage(256 * k, 256 * k, BufferedImage.TYPE_INT_ARGB);
				page.setRGB(0, 46 * k, w, h, strip.getRGB(0, 0, w, h, null, 0, w), 0, w);
				TextureUtil.uploadTextureImageAllocate(getGlTextureId(), page, false, false);
			} catch (Throwable t) {
				fail(t);
			} finally {
				if (in != null) {
					try {
						in.close();
					} catch (IOException ignored) {
						// already closed by readBufferedImage
					}
				}
			}
		}
	}
}
