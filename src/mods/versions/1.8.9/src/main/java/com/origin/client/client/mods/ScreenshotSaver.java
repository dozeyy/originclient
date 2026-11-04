package com.origin.client.client.mods;

import com.origin.client.client.util.Reflect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.event.ClickEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.8.9 ONLY: F2 without the hitch. Vanilla 1.8.9's ScreenShotHelper.saveScreenshot reads
 * the frame AND encodes + writes the PNG on the render thread (100-400 ms at 1080p, more
 * at 1440p/4K: a visible freeze mid-fight). 1.14+ (so 1.21.1) only grabs the pixels on the
 * render thread and writes on an IO thread; this does the same (Patcher's async screenshots).
 *
 * <p>Flow: ScreenshotKeyMixin's HEAD hook calls {@link #begin}. The pixels are read here
 * exactly as vanilla reads them (same GL calls, same framebuffer crop), copied into a
 * fresh array, and the encode + write + vanilla's "Saved screenshot as ..." line go to one
 * daemon thread; the line is printed from the client thread once the file exists, through
 * ScreenshotChat.decorate as before (its buttons need the file on disk). saveScreenshot
 * returns {@link #PENDING}, which F2's caller prints and GuiNewChatMixin drops.
 *
 * <p>Steps aside (vanilla's synchronous save runs) for: a named save (only F2 passes no
 * name: other callers may expect the file to exist on return), OptiFine's Screenshot Size
 * above 1x (OptiFine's patch of this method re-renders the frame bigger first), and any
 * error before the takeover. If GuiNewChatMixin ever failed to apply, the placeholder
 * prints as "Saving screenshot..." -- readable, then the real line follows.
 */
public final class ScreenshotSaver {
	private ScreenshotSaver() {
	}

	/** The placeholder saveScreenshot returns on the async path (identity-compared, never shown). */
	public static final IChatComponent PENDING = new ChatComponentText("Saving screenshot...");

	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Origin Screenshot Writer");
		t.setDaemon(true);
		return t;
	});

	/** Vanilla's ScreenShotHelper.dateFormat. */
	private static final SimpleDateFormat NAME = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
	/** File names handed out but not written yet: two F2s in one second must not share one. */
	private static final Set<String> RESERVED = new HashSet<>();

	private static IntBuffer pixelBuffer;
	private static boolean ofSizeLooked, ofPresent;
	private static Field ofSizeField;

	/** ScreenShotHelper.saveScreenshot(File, String, int, int, Framebuffer) HEAD. Null = vanilla saves it. */
	public static IChatComponent begin(File gameDir, String name, int width, int height, Framebuffer buffer) {
		if (name != null || !Minecraft.getMinecraft().isCallingFromMinecraftThread() || optiFineBigShot()) {
			return null;
		}
		try {
			boolean fbo = OpenGlHelper.isFramebufferEnabled() && buffer != null;
			if (fbo) {
				width = buffer.framebufferTextureWidth;
				height = buffer.framebufferTextureHeight;
			}
			int n = width * height;
			if (n <= 0) {
				return null;
			}
			if (pixelBuffer == null || pixelBuffer.capacity() < n) {
				pixelBuffer = BufferUtils.createIntBuffer(n);
			}
			// Vanilla's read, verbatim (ScreenShotHelper.saveScreenshot).
			GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
			pixelBuffer.clear();
			if (fbo) {
				GlStateManager.bindTexture(buffer.framebufferTexture);
				GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
			} else {
				GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
			}
			final int[] pixels = new int[n];
			pixelBuffer.get(pixels, 0, n);

			File dir = new File(gameDir, "screenshots");
			dir.mkdir();
			final File file = reserve(dir);
			final int texW = width, texH = height;
			final int fbW = fbo ? buffer.framebufferWidth : width;
			final int fbH = fbo ? buffer.framebufferHeight : height;
			final boolean crop = fbo;
			IO.execute(() -> write(pixels, texW, texH, fbW, fbH, crop, file));
			return PENDING;
		} catch (Throwable t) {
			return null;   // nothing was handed off: vanilla saves it the old way
		}
	}

	// IO thread: vanilla's flip, crop, encode and write, then the chat line on the client thread.
	private static void write(int[] pixels, int texW, int texH, int fbW, int fbH, boolean crop, File file) {
		IChatComponent line;
		try {
			TextureUtil.processPixelValues(pixels, texW, texH);   // flips rows (pure array work)
			BufferedImage image = new BufferedImage(fbW, fbH, BufferedImage.TYPE_INT_RGB);
			if (crop) {
				// The framebuffer texture can be larger than the frame (power-of-two sizes): the
				// frame sits at the bottom of the flipped texture, as vanilla crops it.
				int skip = texH - fbH;
				for (int y = skip; y < texH; ++y) {
					for (int x = 0; x < fbW; ++x) {
						image.setRGB(x, y - skip, pixels[y * texW + x]);
					}
				}
			} else {
				image.setRGB(0, 0, texW, texH, pixels, 0, texW);
			}
			ImageIO.write(image, "png", file);
			IChatComponent link = new ChatComponentText(file.getName());
			link.getChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()));
			link.getChatStyle().setUnderlined(Boolean.TRUE);
			line = new ChatComponentTranslation("screenshot.success", link);
		} catch (Throwable t) {
			com.origin.client.OriginClient.LOGGER.warn("Couldn't save screenshot", t);
			line = new ChatComponentTranslation("screenshot.failure", String.valueOf(t.getMessage()));
		} finally {
			synchronized (RESERVED) {
				RESERVED.remove(file.getName());
			}
		}
		final IChatComponent result = line;
		final Minecraft mc = Minecraft.getMinecraft();
		Runnable post = () -> {
			if (mc.ingameGUI != null) {
				mc.ingameGUI.getChatGUI().printChatMessage(ScreenshotChat.decorate(result));
			}
		};
		mc.addScheduledTask(post);
	}

	// Vanilla's getTimestampedPNGFileForDirectory, plus the names still being written.
	private static File reserve(File dir) {
		String stamp;
		synchronized (NAME) {
			stamp = NAME.format(new Date());
		}
		synchronized (RESERVED) {
			for (int i = 1; ; ++i) {
				String fileName = stamp + (i == 1 ? "" : "_" + i) + ".png";
				File f = new File(dir, fileName);
				if (!f.exists() && RESERVED.add(fileName)) {
					return f;
				}
			}
		}
	}

	// OptiFine's Screenshot Size (GameSettings.ofScreenshotSize, 1 = off): above 1x OptiFine's own
	// patch of saveScreenshot renders the frame larger first, so it keeps the save. OptiFine
	// present but the field not found (another OptiFine build): unknown, so OptiFine keeps it.
	private static boolean optiFineBigShot() {
		if (!ofSizeLooked) {
			ofSizeLooked = true;
			ofPresent = com.origin.client.OriginClient.isOptiFineLoaded();
			if (ofPresent) {
				ofSizeField = Reflect.field(net.minecraft.client.settings.GameSettings.class, "ofScreenshotSize");
			}
		}
		if (!ofPresent) {
			return false;
		}
		return ofSizeField == null || Reflect.getInt(ofSizeField, Minecraft.getMinecraft().gameSettings, 1) > 1;
	}
}
