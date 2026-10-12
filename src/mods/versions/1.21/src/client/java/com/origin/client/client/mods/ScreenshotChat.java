package com.origin.client.client.mods;

import com.origin.client.client.OriginClientMod;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.File;
import java.util.function.Consumer;

// Screenshot (Lunar parity, Will's no-upload design, moved into chat 2026-10-12):
// after F2 saves a screenshot, Minecraft's own "Saved screenshot as ..." chat
// line gets clickable [Copy Image] [Copy File] [Open Folder] buttons on the end.
// No pop-up -- it fades with the rest of chat and is only clickable with chat
// open (T), like any chat link. Nothing is uploaded; there's no account.
//
// Flow: ScreenshotKeyMixin wraps the callback vanilla passes to Screenshot.grab
// on F2. Vanilla calls it on its IO thread once the PNG is written, with
// "screenshot.success" whose file-name argument carries an OPEN_FILE click event
// holding the absolute path. We hand vanilla the same message with the buttons
// appended, and vanilla posts it to chat as usual.
//
// Clicking: each button's style carries an ACTION_PREFIX insertion naming its
// action, and its click event holds the file's path. A ScreenMouseEvents hook on
// the ChatScreen reads the style under the mouse first and runs the action; if
// that hook ever failed, vanilla would just handle the click event itself
// (Copy Image / Copy File copy the path, Open Folder opens the folder).
public final class ScreenshotChat {
	private ScreenshotChat() {
	}

	private static final String ID = "screenshot";
	private static final String ACTION_PREFIX = "originclient:screenshot:";

	private static volatile File last = null;
	private static boolean copyKeyWasDown = false;

	/** Wraps vanilla's F2 result callback: same chat line, buttons appended. */
	public static Consumer<Component> wrap(Consumer<Component> vanilla) {
		return msg -> {
			Component out = msg;
			try {
				out = onResult(msg);
			} catch (Throwable t) {
				// the screenshot is saved either way; the line just has no buttons
			}
			vanilla.accept(out);
		};
	}

	// IO thread. Returns the chat line to post.
	private static Component onResult(Component msg) {
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
		MutableComponent line = msg.copy();
		if (Mods.bool(ID, "copyImageButton")) {
			line.append(button("Copy Image", "image", file.getAbsolutePath(),
					ClickEvent.Action.COPY_TO_CLIPBOARD, "Copy the picture -- paste it straight into Discord"));
		}
		if (Mods.bool(ID, "copyFileButton")) {
			line.append(button("Copy File", "file", file.getAbsolutePath(),
					ClickEvent.Action.COPY_TO_CLIPBOARD, "Copy the file, like Ctrl+C in File Explorer"));
		}
		if (Mods.bool(ID, "openFolderButton") && file.getParentFile() != null) {
			line.append(button("Open Folder", "folder", file.getParentFile().getAbsolutePath(),
					ClickEvent.Action.OPEN_FILE, "Open your screenshots folder"));
		}
		return line;
	}

	private static Component button(String label, String action, String path, ClickEvent.Action fallback, String hover) {
		Style style = Style.EMPTY
				.withColor(ChatFormatting.WHITE)
				.withUnderlined(true)
				.withInsertion(ACTION_PREFIX + action)
				.withClickEvent(new ClickEvent(fallback, path))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover)));
		return Component.literal(" ")
				.append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(label).withStyle(style))
				.append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static File savedFile(Component msg) {
		if (!(msg.getContents() instanceof TranslatableContents tc) || !"screenshot.success".equals(tc.getKey())) {
			return null;
		}
		Object[] args = tc.getArgs();
		if (args.length == 0 || !(args[0] instanceof Component name)) {
			return null;
		}
		ClickEvent click = name.getStyle().getClickEvent();
		if (click == null || click.getAction() != ClickEvent.Action.OPEN_FILE) {
			return null;
		}
		File f = new File(click.getValue());
		return f.isFile() ? f : null;
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, sw, sh) -> {
			if (screen instanceof ChatScreen) {
				ScreenMouseEvents.allowMouseClick(screen).register((s, mx, my, button) -> {
					try {
						return button != 0 || !clicked(client, mx, my);
					} catch (Throwable t) {
						return true;
					}
				});
			}
		});
	}

	// Runs the button under the mouse, if it's one of ours. True = handled.
	private static boolean clicked(Minecraft mc, double mx, double my) {
		Style style = mc.gui.getChat().getClickedComponentStyleAt(mx, my);
		if (style == null || style.getInsertion() == null || !style.getInsertion().startsWith(ACTION_PREFIX)
				|| style.getClickEvent() == null) {
			return false;
		}
		File target = new File(style.getClickEvent().getValue());
		switch (style.getInsertion().substring(ACTION_PREFIX.length())) {
			case "image" -> copyImage(target);
			case "file" -> copyFile(target);
			case "folder" -> Util.getPlatform().openFile(target);
			default -> {
				return false;
			}
		}
		return true;
	}

	/** "Copy Last Screenshot" key, edge-triggered with no screen open. */
	public static void tick(Minecraft mc) {
		boolean down = Mods.on(ID) && mc.screen == null && OriginClientMod.isRawKeyDown(Mods.keyCode(ID, "copyKey"));
		if (down && !copyKeyWasDown && last != null) {
			copyImage(last);
		}
		copyKeyWasDown = down;
	}

	// ---- actions (each reports back above the hotbar, out of chat's way) ----

	private static void status(String text) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> mc.gui.setOverlayMessage(Component.literal(text), false));
	}

	public static void copyImage(File f) {
		if (!f.isFile()) {
			status("That screenshot is gone");
			return;
		}
		Util.ioPool().execute(() -> status(ShotClipboard.copyImage(f) ? "Screenshot copied" : "Couldn't copy the screenshot"));
	}

	public static void copyFile(File f) {
		if (!f.isFile()) {
			status("That screenshot is gone");
			return;
		}
		Util.ioPool().execute(() -> {
			if (ShotClipboard.copyFile(f)) {
				status("Screenshot file copied");
			} else {
				// No file clipboard here: hand over the path instead.
				Minecraft mc = Minecraft.getInstance();
				mc.execute(() -> mc.keyboardHandler.setClipboard(f.getAbsolutePath()));
				status("Screenshot file path copied");
			}
		});
	}
}
