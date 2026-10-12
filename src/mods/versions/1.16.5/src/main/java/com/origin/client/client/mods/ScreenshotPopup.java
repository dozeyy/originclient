package com.origin.client.client.mods;

import com.mojang.blaze3d.platform.NativeImage;
import com.origin.client.client.OriginClientMod;
import com.origin.client.client.gui.ScreenshotToast;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.function.Consumer;

// Screenshot (Lunar parity, Will's no-upload design): after F2 saves a
// screenshot, an Origin toast slides in with the picture and local actions --
// Copy image, Copy file, Open folder (and an optional Delete). Nothing is
// uploaded; there's no account.
//
// Flow: KeyboardHandlerScreenshotMixin wraps the callback vanilla passes to
// Screenshot.grab on F2. Vanilla calls it on its IO thread once the PNG is
// written, with "screenshot.success" whose file-name argument carries an
// OPEN_FILE click event holding the absolute path -- that's where the file is
// read from. The thumbnail is shrunk on the IO thread, then uploaded and the
// toast shown on the render thread.
//
// Clicking: the mouse is captured in game, so the toast's buttons work while
// chat is open (it says so). ScreenMouseEvents on the ChatScreen routes clicks
// inside the toast to it before chat sees them.
public final class ScreenshotPopup {
	private ScreenshotPopup() {
	}

	private static final String ID = "screenshot";
	public static final ResourceLocation THUMB = new ResourceLocation("originclient", "screenshot_preview");
	private static final int THUMB_W = 192;

	private static volatile File last = null;
	private static boolean copyKeyWasDown = false;

	/** Wraps vanilla's F2 result callback; vanilla's chat line still runs unless turned off. */
	public static Consumer<Component> wrap(Consumer<Component> vanilla) {
		return msg -> {
			boolean keepChat = true;
			try {
				keepChat = onResult(msg);
			} catch (Throwable t) {
				// the screenshot is saved either way; the pop-up just doesn't show
			}
			if (keepChat) {
				vanilla.accept(msg);
			}
		};
	}

	// IO thread. Returns whether vanilla's chat line should still be posted.
	private static boolean onResult(Component msg) {
		if (!Mods.on(ID)) {
			return true;
		}
		File file = savedFile(msg);
		if (file == null) {
			return true;   // a failure message: always let vanilla report it
		}
		last = file;
		NativeImage thumb = null;
		try (InputStream in = Files.newInputStream(file.toPath())) {
			try (NativeImage full = NativeImage.read(in)) {
				thumb = shrink(full);
			}
		} catch (Throwable t) {
			thumb = null;   // the toast shows without a picture
		}
		NativeImage finalThumb = thumb;
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> show(mc, file, finalThumb));
		if (Mods.bool(ID, "copyAutomatically")) {
			copyImage(file);
		}
		return Mods.bool(ID, "chatMessage");
	}

	private static File savedFile(Component msg) {
		// 1.16.5: the message itself is the TranslatableComponent (no contents split before 1.19).
		if (!(msg instanceof TranslatableComponent) || !"screenshot.success".equals(((TranslatableComponent) msg).getKey())) {
			return null;
		}
		Object[] args = ((TranslatableComponent) msg).getArgs();
		if (args.length == 0 || !(args[0] instanceof Component)) {
			return null;
		}
		Component name = (Component) args[0];
		ClickEvent click = name.getStyle().getClickEvent();
		if (click == null || click.getAction() != ClickEvent.Action.OPEN_FILE) {
			return null;
		}
		File f = new File(click.getValue());
		return f.isFile() ? f : null;
	}

	// Box-filtered downscale to THUMB_W wide: each thumbnail pixel averages a
	// 3x3 sample of its source block, so the small preview isn't aliased.
	private static NativeImage shrink(NativeImage full) {
		int sw = full.getWidth(), sh = full.getHeight();
		int tw = Math.min(THUMB_W, sw);
		int th = Math.max(1, Math.round(tw * sh / (float) sw));
		NativeImage out = new NativeImage(tw, th, false);
		for (int y = 0; y < th; y++) {
			for (int x = 0; x < tw; x++) {
				long a = 0, b = 0, g = 0, r = 0;
				int n = 0;
				for (int sy = 0; sy < 3; sy++) {
					for (int sx = 0; sx < 3; sx++) {
						int px = Math.min(sw - 1, (int) ((x + (sx + 0.5) / 3.0) * sw / tw));
						int py = Math.min(sh - 1, (int) ((y + (sy + 0.5) / 3.0) * sh / th));
						int c = full.getPixelRGBA(px, py);
						a += (c >>> 24) & 0xFF;
						b += (c >>> 16) & 0xFF;
						g += (c >>> 8) & 0xFF;
						r += c & 0xFF;
						n++;
					}
				}
				out.setPixelRGBA(x, y, (int) ((a / n) << 24 | (b / n) << 16 | (g / n) << 8 | (r / n)));
			}
		}
		return out;
	}

	// Render thread: upload the thumbnail and show (or re-use) the toast.
	private static void show(Minecraft mc, File file, NativeImage thumb) {
		int tw = 0, th = 0;
		if (thumb != null) {
			try {
				DynamicTexture tex = new DynamicTexture(thumb);
				tex.setFilter(true, false);
				mc.getTextureManager().register(THUMB, tex);   // replaces (and frees) the previous one
				tw = thumb.getWidth();
				th = thumb.getHeight();
			} catch (Throwable t) {
				thumb.close();
			}
		}
		ScreenshotToast toast = mc.getToasts().getToast(ScreenshotToast.class, ScreenshotToast.TOKEN);
		if (toast != null) {
			toast.retarget(file, tw, th);
		} else {
			mc.getToasts().addToast(new ScreenshotToast(file, tw, th));
		}
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof ChatScreen) {
				ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
					try {
						return !ScreenshotToast.mouseClicked(mx, my, button);
					} catch (Throwable t) {
						return true;
					}
				});
			}
		});
	}

	/** "Copy Last Screenshot" key, edge-triggered with no screen open. */
	public static void tick(Minecraft mc) {
		boolean down = Mods.on(ID) && mc.screen == null && OriginClientMod.isRawKeyDown(Mods.keyCode(ID, "copyKey"));
		if (down && !copyKeyWasDown && last != null) {
			copyImage(last);
		}
		copyKeyWasDown = down;
	}

	// ---- actions (each reports back through the toast's status line) ----

	public static void copyImage(File f) {
		ScreenshotToast.status("Copying...");
		Util.ioPool().execute(() -> ScreenshotToast.status(
				ShotClipboard.copyImage(f) ? "Image copied" : "Couldn't copy the image"));
	}

	public static void copyFile(File f) {
		ScreenshotToast.status("Copying...");
		Util.ioPool().execute(() -> {
			if (ShotClipboard.copyFile(f)) {
				ScreenshotToast.status("File copied");
			} else {
				// No file clipboard here: hand over the path instead.
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.keyboardHandler.setClipboard(f.getAbsolutePath()));
				ScreenshotToast.status("File path copied");
			}
		});
	}

	public static void openFolder(File f) {
		File dir = f.getParentFile();
		if (dir != null) {
			Util.getPlatform().openFile(dir);
		}
	}

	public static void delete(File f) {
		try {
			Files.deleteIfExists(f.toPath());
			if (f.equals(last)) {
				last = null;
			}
			ScreenshotToast.status("Deleted");
		} catch (Exception e) {
			ScreenshotToast.status("Couldn't delete it");
		}
	}
}
