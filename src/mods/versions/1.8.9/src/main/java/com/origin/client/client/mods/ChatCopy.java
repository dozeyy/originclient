package com.origin.client.client.mods;

import com.origin.client.client.mixin.core.GuiNewChatAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;
import org.lwjgl.input.Mouse;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Chat mod, Right-Click to Copy (1.8.9): with the chat open, right-clicking a
 * message copies its text -- formatting codes, Origin's [HH:mm] timestamp and
 * Stack Spam's "(xN)" counter stripped -- and says "Copied to clipboard" on the
 * action bar.
 *
 * The click arrives through ServerHooks.ChatClicks (Forge's
 * GuiScreenEvent.MouseInputEvent.Pre on GuiChat), no mixin on GuiChat. The line
 * under the mouse is found with vanilla's own getChatComponent math (window
 * pixels, chat scale, scroll), and the whole message it belongs to is found by
 * re-wrapping the stored messages exactly as setChatLine does, so a message that
 * wraps onto several lines copies whole. Option off, a click off the chat, a
 * left click (links), or anything unexpected = vanilla.
 */
public final class ChatCopy {
	// Origin's own additions to a line (ChatTimestampMixin): the muted stamp in
	// front and the grey Stack Spam counter at the end.
	private static final Pattern STAMP = Pattern.compile("\\[\\d\\d:\\d\\d\\] ");
	private static final Pattern COUNTER = Pattern.compile(" \\(x\\d+\\)");

	private ChatCopy() {
	}

	/** GuiChat mouse input. True = copied (the caller cancels vanilla's own click handling). */
	public static boolean onChatClick(Minecraft mc) {
		try {
			if (Mouse.getEventButton() != 1 || !Mouse.getEventButtonState() || mc.ingameGUI == null) {
				return false;
			}
			// The option alone decides: the Chat mod card is off by default, and this
			// toggle defaults on (Will: right-click any chat to copy it).
			if (!Mods.bool("chat", "copyOnRightClick")) {
				return false;
			}
			GuiNewChat chat = mc.ingameGUI.getChatGUI();
			if (!((Object) chat instanceof GuiNewChatAccessor)) {
				return false;   // accessor mixin didn't apply: feature off
			}
			String text = textAt(mc, chat, (GuiNewChatAccessor) (Object) chat, Mouse.getEventX(), Mouse.getEventY());
			if (text == null || text.isEmpty()) {
				return false;
			}
			GuiScreen.setClipboardString(text);
			mc.ingameGUI.setRecordPlaying(new ChatComponentText("Copied to clipboard"), false);
			return true;
		} catch (Throwable t) {
			return false;   // vanilla handles the click
		}
	}

	/** The clean text of the message under window pixel (mouseX, mouseY), or null when no line is there. */
	private static String textAt(Minecraft mc, GuiNewChat chat, GuiNewChatAccessor acc, int mouseX, int mouseY) {
		if (!chat.getChatOpen()) {
			return null;
		}
		// Same steps as GuiNewChat.getChatComponent (javap 1.8.9), so the hit area is
		// exactly the one vanilla uses for link clicks.
		int factor = new ScaledResolution(mc).getScaleFactor();
		float scale = chat.getChatScale();
		int x = MathHelper.floor_float((float) (mouseX / factor - 3) / scale);
		int y = MathHelper.floor_float((float) (mouseY / factor - 27) / scale);
		if (x < 0 || y < 0) {
			return null;
		}
		List<ChatLine> drawn = acc.originclient$drawnChatLines();
		int visible = Math.min(chat.getLineCount(), drawn.size());
		int fontHeight = mc.fontRendererObj.FONT_HEIGHT;
		if (x > MathHelper.floor_float((float) chat.getChatWidth() / scale) || y >= fontHeight * visible + visible) {
			return null;
		}
		int index = y / fontHeight + acc.originclient$scrollPos();
		if (index < 0 || index >= drawn.size()) {
			return null;
		}
		IChatComponent message = wholeMessage(mc, chat, acc, drawn, index);
		if (message == null) {
			message = drawn.get(index).getChatComponent();   // couldn't match it to a message: the line itself
		}
		return plainText(message);
	}

	/**
	 * The stored message whose wrapped lines include drawn line `index`, or null.
	 * Both lists are newest first and every message became splitText(...).size()
	 * drawn lines (setChatLine), so walking the messages and summing their line
	 * counts lands on the right one; the counter/id check catches any drift
	 * (a width change not yet re-wrapped) and falls back to the single line.
	 */
	private static IChatComponent wholeMessage(Minecraft mc, GuiNewChat chat, GuiNewChatAccessor acc,
			List<ChatLine> drawn, int index) {
		List<ChatLine> messages = acc.originclient$chatLines();
		int width = MathHelper.floor_float((float) chat.getChatWidth() / chat.getChatScale());
		int start = 0;
		for (ChatLine message : messages) {
			int lines = GuiUtilRenderComponents.splitText(message.getChatComponent(), width, mc.fontRendererObj, false, false).size();
			if (index < start + lines) {
				ChatLine line = drawn.get(index);
				if (line.getUpdatedCounter() != message.getUpdatedCounter() || line.getChatLineID() != message.getChatLineID()) {
					return null;
				}
				return message.getChatComponent();
			}
			start += lines;
		}
		return null;
	}

	/** The message's text with Origin's stamp and spam counter taken off and every formatting code stripped. */
	static String plainText(IChatComponent message) {
		IChatComponent body = message;
		// Timestamps: an unstyled empty root whose siblings are [stamp, message].
		List<IChatComponent> rootSiblings = message.getSiblings();
		if (message instanceof ChatComponentText && message.getUnformattedTextForChat().isEmpty()
				&& rootSiblings.size() == 2 && isOrigin(rootSiblings.get(0), STAMP, EnumChatFormatting.DARK_GRAY)) {
			body = rootSiblings.get(1);
		}
		String text = body.getUnformattedText();
		// Stack Spam: the counter is the message's last sibling.
		List<IChatComponent> siblings = body.getSiblings();
		if (!siblings.isEmpty()) {
			IChatComponent last = siblings.get(siblings.size() - 1);
			if (isOrigin(last, COUNTER, EnumChatFormatting.GRAY)) {
				String counter = last.getUnformattedTextForChat();
				if (text.endsWith(counter)) {
					text = text.substring(0, text.length() - counter.length());
				}
			}
		}
		String clean = EnumChatFormatting.getTextWithoutFormattingCodes(text);
		return clean == null ? null : clean.trim();
	}

	private static boolean isOrigin(IChatComponent c, Pattern text, EnumChatFormatting color) {
		return c instanceof ChatComponentText && c.getSiblings().isEmpty()
				&& c.getChatStyle().getColor() == color
				&& text.matcher(c.getUnformattedTextForChat()).matches();
	}
}
