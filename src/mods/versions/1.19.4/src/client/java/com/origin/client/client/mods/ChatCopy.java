package com.origin.client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.regex.Pattern;

// Chat > "Right-Click to Copy" (Will, 2026-11-03): with chat open (T), right-clicking a
// chat line copies that message's plain text to the clipboard and says "Copied to
// clipboard" above the hotbar. Left-click (links, click events) is untouched.
//
// Flow: ChatCopyClickMixin (ChatScreen.mouseClicked, right button only, after the command
// suggestions had their chance) asks copyAt(). The chat component itself, through
// ChatCopyLinesMixin, finds the line under the mouse with vanilla's own hit-test math and
// knows which whole message produced that line, so a wrapped message copies in full.
// Copied text is the message as the server sent it: Origin's own "[HH:mm] " timestamp
// and Stack Spam "(xN)" counter (ChatTimestampMixin) are taken back off.
//
// Fail-soft: anything unexpected returns false and the click goes to vanilla.
public final class ChatCopy {
	private ChatCopy() {
	}

	/** Implemented on ChatComponent by ChatCopyLinesMixin. Lives outside the mixin
	 *  package on purpose: a mixin-package class can't be loaded as a normal class. */
	public interface Lines {
		/** Plain text of the message under the mouse, or null when the mouse isn't on a line. */
		String originclient$copyTextAt(double mouseX, double mouseY);
	}

	private static final Pattern STAMP = Pattern.compile("\\[\\d{2}:\\d{2}\\] ");
	private static final Pattern COUNTER = Pattern.compile("\\(x\\d+\\)");
	private static final Pattern STAMP_PREFIX = Pattern.compile("^\\[\\d{2}:\\d{2}\\] ");
	private static final Pattern COUNTER_SUFFIX = Pattern.compile(" \\(x\\d+\\)$");

	/** Right-click on the open chat. True = copied (the click is used up). */
	public static boolean copyAt(double mouseX, double mouseY) {
		try {
			if (!Mods.on("chat") || !Mods.bool("chat", "copyOnRightClick")) {
				return false;
			}
			Minecraft mc = Minecraft.getInstance();
			Object chat = mc.gui.getChat();
			if (!(chat instanceof Lines)) {
				return false;
			}
			String text = ((Lines) chat).originclient$copyTextAt(mouseX, mouseY);
			if (text == null || text.trim().isEmpty()) {
				return false;
			}
			mc.keyboardHandler.setClipboard(text);
			mc.gui.setOverlayMessage(Component.literal("Copied to clipboard"), false);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/** A whole chat message as plain text, minus Origin's own timestamp and counter. */
	public static String plain(Component message) {
		Component body = message;
		// Timestamps: an empty root holding [dark-grey "[HH:mm] ", the message].
		List<Component> sib = body.getSiblings();
		if (sib.size() == 2 && isOriginPart(sib.get(0), STAMP, ChatFormatting.DARK_GRAY)
				&& body.getString().equals(sib.get(0).getString() + sib.get(1).getString())) {
			body = sib.get(1);
		}
		String text = body.getString();
		// Stack Spam: "message " with one grey "(xN)" sibling.
		sib = body.getSiblings();
		if (sib.size() == 1 && isOriginPart(sib.get(0), COUNTER, ChatFormatting.GRAY)) {
			String counter = " " + sib.get(0).getString();
			if (text.endsWith(counter)) {
				text = text.substring(0, text.length() - counter.length());
			}
		}
		return clean(text);
	}

	/** One visible line as plain text: the fallback when the line's message is unknown. */
	public static String visible(FormattedCharSequence line) {
		final StringBuilder sb = new StringBuilder();
		line.accept((index, style, codePoint) -> {
			sb.appendCodePoint(codePoint);
			return true;
		});
		String text = sb.toString();
		if (Mods.bool("chat", "timestamps")) {
			text = STAMP_PREFIX.matcher(text).replaceFirst("");
		}
		if (Mods.bool("chat", "stackSpam")) {
			text = COUNTER_SUFFIX.matcher(text).replaceFirst("");
		}
		return clean(text);
	}

	private static boolean isOriginPart(Component part, Pattern text, ChatFormatting colour) {
		return part.getSiblings().isEmpty()
				&& text.matcher(part.getString()).matches()
				&& TextColor.fromLegacyFormat(colour).equals(part.getStyle().getColor());
	}

	// Servers still send legacy section-sign colour codes inside plain text.
	private static String clean(String text) {
		String out = ChatFormatting.stripFormatting(text);
		return out == null ? "" : out;
	}
}
