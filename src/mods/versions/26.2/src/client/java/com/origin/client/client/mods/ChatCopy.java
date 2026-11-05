package com.origin.client.client.mods;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

// Chat -> "Right-Click to Copy" (Will, 2026-11-03): with chat open, right-clicking a
// chat line puts that message's plain text on the clipboard.
//
// This is the version-independent half. Each module carries two small mixins:
//   ChatCopyMixin        (ChatComponent) implements Lookup: finds the line under the
//                        mouse with that era's own hit-test and hands it to resolve()
//                        or messageText() below.
//   ChatScreenCopyMixin  (ChatScreen) runs at the point vanilla's mouseClicked falls
//                        through to Screen.mouseClicked, i.e. after the command
//                        suggestions and the left-click link handling had their turn,
//                        so links and suggestions behave exactly as before.
// Fail soft: any exception in the lookup means "not handled" and vanilla runs.
public final class ChatCopy {
	private ChatCopy() {
	}

	/** Implemented by each module's ChatCopyMixin on ChatComponent (duck interface: it
	 *  must live outside the mixin package, or loading it throws IllegalClassLoadError). */
	public interface Lookup {
		/** Plain text of the chat message under this screen point, or null for no line. */
		String originclient$copyTextAt(double mouseX, double mouseY);
	}

	// Origin's own additions to a chat line (ChatTimestampMixin in every module):
	//   Timestamps  -> Component.empty().append("[HH:mm] " DARK_GRAY).append(message)
	//   Stack Spam  -> Component.empty().append(message).append(" ").append("(xN)" GRAY)
	private static final Pattern STAMP = Pattern.compile("\\[\\d{2}:\\d{2}\\] ");
	private static final Pattern COUNTER = Pattern.compile("\\(x\\d+\\)");

	// Unlike the Chat card's other options this one works with the card OFF (it is off
	// by default): only its own toggle gates it (Will, 2026-11-03). Same gate on every
	// version (1.8.9 and 1.16.5 - 1.19.4 included).
	public static boolean enabled() {
		return Mods.bool("chat", "copyOnRightClick");
	}

	private static boolean chatOption(String key) {
		return Mods.on("chat") && Mods.bool("chat", key);
	}

	/** The clipboard text for one chat message: its plain text without Origin's timestamp
	 *  prefix or Stack Spam counter. Detected by the exact component shape Origin builds,
	 *  so a server message that merely ends in "(x2)" keeps it, and lines stamped before a
	 *  toggle was switched off still come out clean. */
	public static String messageText(Component message) {
		Component body = message;
		List<Component> parts = message.getSiblings();
		if (parts.size() == 2 && isOriginPart(parts.get(0), ChatFormatting.DARK_GRAY, STAMP)
				&& message.getString().length()
				== parts.get(0).getString().length() + parts.get(1).getString().length()) {
			body = parts.get(1);
		}
		String text = body.getString();
		List<Component> tail = body.getSiblings();
		if (!tail.isEmpty()) {
			Component last = tail.get(tail.size() - 1);
			String counter = " " + last.getString();
			if (isOriginPart(last, ChatFormatting.GRAY, COUNTER) && text.endsWith(counter)) {
				text = text.substring(0, text.length() - counter.length());
			}
		}
		return clean(text);
	}

	private static boolean isOriginPart(Component part, ChatFormatting colour, Pattern shape) {
		return part.getSiblings().isEmpty()
				&& TextColor.fromLegacyFormat(colour).equals(part.getStyle().getColor())
				&& shape.matcher(part.getString()).matches();
	}

	/** For eras whose GuiMessage.Line has no parent link (1.20 - 1.21.11). trimmedMessages
	 *  and allMessages are both newest-first; each message's wrapped lines sit together,
	 *  with its LAST wrapped line (endOfEntry) at the lowest index. So the line's block
	 *  number is the count of endOfEntry lines at or before it, and block k is message k.
	 *  The block's text must match the message's text; if it doesn't (a partly trimmed
	 *  oldest block, another mod reshuffling chat) the visible line is copied instead. */
	public static String resolve(int line, int lineCount, IntPredicate endOfEntry,
								 IntFunction<FormattedCharSequence> lineContent,
								 int messageCount, IntFunction<Component> messageContent) {
		int block = -1;
		for (int i = 0; i <= line; i++) {
			if (endOfEntry.test(i)) {
				block++;
			}
		}
		int first = line;
		while (first > 0 && !endOfEntry.test(first)) {
			first--;
		}
		int end = line + 1;
		while (end < lineCount && !endOfEntry.test(end)) {
			end++;
		}
		if (block >= 0 && block < messageCount) {
			StringBuilder shown = new StringBuilder();
			for (int i = end - 1; i >= first; i--) {   // reading order: oldest wrapped line first
				shown.append(plain(lineContent.apply(i)));
			}
			Component message = messageContent.apply(block);
			if (squash(shown.toString()).equals(squash(message.getString()))) {
				return messageText(message);
			}
		}
		return lineText(lineContent.apply(line));
	}

	/** Fallback: one visible line, with Origin's timestamp / counter text taken off when
	 *  those options are live (a line has no component shape left to detect them by). */
	public static String lineText(FormattedCharSequence seq) {
		String text = plain(seq);
		if (chatOption("timestamps")) {
			text = STAMP_PREFIX.matcher(text).replaceFirst("");
		}
		if (chatOption("stackSpam")) {
			text = COUNTER_SUFFIX.matcher(text).replaceFirst("");
		}
		return clean(text);
	}

	private static final Pattern STAMP_PREFIX = Pattern.compile("^\\[\\d{2}:\\d{2}\\] ");
	private static final Pattern COUNTER_SUFFIX = Pattern.compile(" \\(x\\d+\\)\\s*$");

	/** The plain characters of one rendered line. */
	public static String plain(FormattedCharSequence seq) {
		StringBuilder sb = new StringBuilder();
		seq.accept((index, style, codePoint) -> {
			sb.appendCodePoint(codePoint);
			return true;
		});
		return sb.toString();
	}

	private static String squash(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		s.codePoints().filter(cp -> !Character.isWhitespace(cp)).forEach(sb::appendCodePoint);
		return sb.toString();
	}

	/** Drops legacy section-sign colour codes some servers put in literal text. */
	private static String clean(String text) {
		String stripped = ChatFormatting.stripFormatting(text);
		return stripped == null ? "" : stripped.strip();
	}

	/** Puts text on the system clipboard through Minecraft's own clipboard handler. */
	public static void toClipboard(String text) {
		Minecraft.getInstance().keyboardHandler.setClipboard(text);
	}

	public static final String COPIED = "Copied to clipboard";

	private static boolean warned;

	/** A lookup threw: the click goes to vanilla instead, and the log says why once. */
	public static void fail(Throwable t) {
		if (!warned) {
			warned = true;
			com.origin.client.OriginClient.LOGGER.warn("Chat right-click copy failed; leaving the click to vanilla", t);
		}
	}
}
