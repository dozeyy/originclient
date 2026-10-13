package com.origin.client.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Minecraft-native text bridge for every Origin-owned screen. */
public final class OriginText {
	private OriginText() {
	}

	public static int draw(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		g.drawString(font, s, x, y, color, shadow);
		return x + font.width(s);
	}

	public static int drawBold(GuiGraphics g, Font font, String s, int x, int y, int color, boolean shadow) {
		g.drawString(font, s, x, y, color, shadow);
		return x + font.width(s);
	}

	public static int width(Font font, String s) {
		return font.width(s);
	}

	public static int widthBold(Font font, String s) {
		return font.width(s);
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (font.width(s) <= maxW) {
			return s;
		}
		String ellipsis = "…";
		return font.plainSubstrByWidth(s, Math.max(0, maxW - font.width(ellipsis))) + ellipsis;
	}

	/** Fits a widget label into maxW without ever hiding an option's value.
	 *  "Name: Value" labels shorten only the name ("High Contrast Bl…: OFF");
	 *  when even "…: Value" is too wide, or the label has no name/value split,
	 *  the whole string is ellipsized. Allocates nothing extra when it fits. */
	public static String fitLabel(Font font, Component message, int maxW) {
		String s = message.getString();
		if (font.width(s) <= maxW) {
			return s;
		}
		int nameEnd = optionNameEnd(message, s);
		if (nameEnd > 0) {
			String tail = s.substring(nameEnd);
			String ellipsis = "…";
			int room = maxW - font.width(ellipsis) - font.width(tail);
			if (room >= 0) {
				return font.plainSubstrByWidth(s.substring(0, nameEnd), room).stripTrailing() + ellipsis + tail;
			}
		}
		return ellipsize(font, s, maxW);
	}

	/** Where the option NAME ends in label s, or -1. Read from the Component
	 *  when it is vanilla's option label: Options.genericValueLabel /
	 *  CommonComponents.optionNameValue ("options.generic_value"), and
	 *  Options.percentValueLabel / pixelValueLabel ("options.percent_value",
	 *  "options.pixel_value") all translate with args [name, value], so the
	 *  name is args[0] and everything after it (the localized separator +
	 *  value) is kept whole. Any other label: the last ": ". */
	private static int optionNameEnd(Component message, String s) {
		if (message.getContents() instanceof TranslatableContents tc) {
			String key = tc.getKey();
			Object[] args = tc.getArgs();
			if (args.length == 2 && ("options.generic_value".equals(key)
					|| "options.percent_value".equals(key) || "options.pixel_value".equals(key))) {
				Object arg = args[0];
				String name = arg instanceof Component c ? c.getString() : String.valueOf(arg);
				if (!name.isEmpty() && name.length() < s.length() && s.startsWith(name)) {
					return name.length();
				}
			}
		}
		int sep = s.lastIndexOf(": ");
		return sep > 0 ? sep : -1;
	}
}
