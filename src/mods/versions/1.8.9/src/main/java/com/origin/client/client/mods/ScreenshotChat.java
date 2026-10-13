package com.origin.client.client.mods;

import com.origin.client.client.util.KeyCodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Screenshot (Lunar parity, Will's no-upload design, moved into chat 2026-10-12):
// after F2 saves a screenshot, Minecraft's own "Saved screenshot as ..." chat
// line gets clickable [Copy Image] [Copy File] [Open Folder] buttons on the end.
// No pop-up -- it fades with the rest of chat and is only clickable with chat
// open (T), like any chat link. Nothing is uploaded; there's no account.
//
// Flow on 1.8.9: F2 runs ScreenShotHelper.saveScreenshot(...) synchronously and
// prints the IChatComponent it RETURNS ("screenshot.success", whose file-name
// argument carries an OPEN_FILE click event holding the absolute path). The
// ScreenshotKeyMixin RETURN hook hands that result to decorate(), which appends
// the buttons; vanilla prints the decorated line as usual. (Forge 1.8.9 has no
// ScreenshotEvent -- that is 1.11+.)
//
// Clicking: each button's style carries an ACTION_PREFIX insertion naming its
// action, and its click event holds the file's path. ServerHooks reads the chat
// component under the mouse on GuiChat's mouse input (Forge MouseInputEvent.Pre)
// and runs the action first. If that hook ever failed, vanilla would just handle
// the click event itself: 1.8.9's ClickEvent has no COPY_TO_CLIPBOARD, so every
// button falls back to OPEN_FILE (the picture, or the folder).
public final class ScreenshotChat {
	private ScreenshotChat() {
	}

	private static final String ID = "screenshot";
	private static final String ACTION_PREFIX = "originclient:screenshot:";

	private static volatile File last = null;
	private static boolean copyKeyWasDown = false;

	// The clipboard tools block for up to a few seconds: one daemon worker keeps
	// them off the game thread (1.8.9 has no Util.ioPool).
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Origin Screenshot");
		t.setDaemon(true);
		return t;
	});

	/** ScreenShotHelper.saveScreenshot's result: the same chat line, buttons appended. */
	public static IChatComponent decorate(IChatComponent msg) {
		if (msg == null) {
			return null;
		}
		try {
			return onResult(msg);
		} catch (Throwable t) {
			return msg;   // the screenshot is saved either way; the line just has no buttons
		}
	}

	// Main thread (1.8.9 writes the PNG synchronously). Returns the chat line to post.
	private static IChatComponent onResult(IChatComponent msg) {
		if (!Mods.on(ID)) {
			return msg;
		}
		File file = savedFile(msg);
		if (file == null) {
			return msg;   // a failure message: vanilla's text as-is
		}
		last = file;
		if (Mods.bool(ID, "copyAutomatically")) {
			copyImage(file);
		}
		IChatComponent line = msg.createCopy();
		if (Mods.bool(ID, "copyImageButton")) {
			line.appendSibling(button("Copy Image", "image", file.getAbsolutePath(),
					"Copy the picture -- paste it straight into Discord"));
		}
		if (Mods.bool(ID, "copyFileButton")) {
			line.appendSibling(button("Copy File", "file", file.getAbsolutePath(),
					"Copy the file, like Ctrl+C in File Explorer"));
		}
		if (Mods.bool(ID, "openFolderButton") && file.getParentFile() != null) {
			line.appendSibling(button("Open Folder", "folder", file.getParentFile().getAbsolutePath(),
					"Open your screenshots folder"));
		}
		return line;
	}

	private static IChatComponent button(String label, String action, String path, String hover) {
		ChatStyle style = new ChatStyle()
				.setColor(EnumChatFormatting.WHITE)
				.setUnderlined(Boolean.TRUE)
				.setInsertion(ACTION_PREFIX + action)
				.setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path))
				.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(hover)));
		IChatComponent out = new ChatComponentText(" ");
		out.appendSibling(new ChatComponentText("[").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY)));
		out.appendSibling(new ChatComponentText(label).setChatStyle(style));
		out.appendSibling(new ChatComponentText("]").setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY)));
		return out;
	}

	private static File savedFile(IChatComponent msg) {
		if (!(msg instanceof ChatComponentTranslation)
				|| !"screenshot.success".equals(((ChatComponentTranslation) msg).getKey())) {
			return null;
		}
		Object[] args = ((ChatComponentTranslation) msg).getFormatArgs();
		if (args.length == 0 || !(args[0] instanceof IChatComponent)) {
			return null;
		}
		ClickEvent click = ((IChatComponent) args[0]).getChatStyle().getChatClickEvent();
		if (click == null || click.getAction() != ClickEvent.Action.OPEN_FILE) {
			return null;
		}
		File f = new File(click.getValue());
		return f.isFile() ? f : null;
	}

	/** Nothing to register on 1.8.9: ServerHooks forwards GuiChat's mouse input to {@link #onChatClick}. */
	public static void register() {
	}

	/**
	 * GuiChat mouse input (Forge GuiScreenEvent.MouseInputEvent.Pre): runs the
	 * button under the mouse if it's one of ours. True = handled (cancel vanilla's
	 * own click). Mouse.getX/getY are window pixels, what GuiNewChat.getChatComponent
	 * takes on 1.8.9 (GuiChat passes them the same way).
	 */
	public static boolean onChatClick(Minecraft mc) {
		if (Mouse.getEventButton() != 0 || !Mouse.getEventButtonState() || mc.ingameGUI == null) {
			return false;
		}
		IChatComponent c = mc.ingameGUI.getChatGUI().getChatComponent(Mouse.getX(), Mouse.getY());
		if (c == null) {
			return false;
		}
		ChatStyle style = c.getChatStyle();
		String insertion = style.getInsertion();
		if (insertion == null || !insertion.startsWith(ACTION_PREFIX) || style.getChatClickEvent() == null) {
			return false;
		}
		File target = new File(style.getChatClickEvent().getValue());
		switch (insertion.substring(ACTION_PREFIX.length())) {
			case "image":
				copyImage(target);
				break;
			case "file":
				copyFile(target);
				break;
			case "folder":
				openFolder(target);
				break;
			default: {
				return false;
			}
		}
		return true;
	}

	/** "Copy Last Screenshot" key, edge-triggered with no screen open. */
	public static void tick(Minecraft mc) {
		boolean down = Mods.on(ID) && mc.currentScreen == null && KeyCodes.isDown(Mods.keyCode(ID, "copyKey"));
		if (down && !copyKeyWasDown && last != null) {
			copyImage(last);
		}
		copyKeyWasDown = down;
	}

	// ---- actions (each reports back above the hotbar, out of chat's way) ----

	// 1.8.9: the action bar is GuiIngame.setRecordPlaying(component, false), on the main thread.
	private static void status(final String text) {
		final Minecraft mc = Minecraft.getMinecraft();
		Runnable show = () -> {
			if (mc.ingameGUI != null) {
				mc.ingameGUI.setRecordPlaying(new ChatComponentText(text), false);
			}
		};
		mc.addScheduledTask(show);
	}

	public static void copyImage(File f) {
		if (!f.isFile()) {
			status("That screenshot is gone");
			return;
		}
		IO.execute(() -> status(ShotClipboard.copyImage(f) ? "Screenshot copied" : "Couldn't copy the screenshot"));
	}

	public static void copyFile(File f) {
		if (!f.isFile()) {
			status("That screenshot is gone");
			return;
		}
		IO.execute(() -> {
			if (ShotClipboard.copyFile(f)) {
				status("Screenshot file copied");
			} else {
				// No file clipboard here: hand over the path instead.
				Minecraft mc = Minecraft.getMinecraft();
				Runnable copyPath = () -> GuiScreen.setClipboardString(f.getAbsolutePath());
				mc.addScheduledTask(copyPath);
				status("Screenshot file path copied");
			}
		});
	}

	// 1.8.9 has no Util.getPlatform().openFile: the OS's own folder opener, as
	// vanilla 1.8.9's "Open resource pack folder" button does per OS.
	private static void openFolder(File dir) {
		IO.execute(() -> {
			try {
				String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
				String path = dir.getAbsolutePath();
				ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("explorer.exe", path)
						: os.contains("mac") ? new ProcessBuilder("open", path)
						: new ProcessBuilder("xdg-open", path);
				pb.start();
			} catch (Throwable t) {
				status("Couldn't open the screenshots folder");
			}
		});
	}
}
