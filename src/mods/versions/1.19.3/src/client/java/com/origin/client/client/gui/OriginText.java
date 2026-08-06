package com.origin.client.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

public final class OriginText {
	public static final ResourceLocation REGULAR = new ResourceLocation("originclient", "inter");
	public static final ResourceLocation SEMIBOLD = new ResourceLocation("originclient", "inter_semibold");

	private OriginText() {
	}

	public static MutableComponent regular(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(REGULAR));
	}

	public static MutableComponent semibold(String s) {
		return Component.literal(s).withStyle(st -> st.withFont(SEMIBOLD));
	}

	public static int draw(Gfx g, Font font, String s, int x, int y, int color, boolean shadow) {
		if (OriginSdfFont.active()) {
			OriginSdfFont.draw(g, s, x, y, color, shadow, false);
			return x + OriginSdfFont.width(s, false);
		}
		return g.drawString(font, regular(s), x, y, color, shadow);
	}

	public static int drawBold(Gfx g, Font font, String s, int x, int y, int color, boolean shadow) {
		if (OriginSdfFont.active()) {
			OriginSdfFont.draw(g, s, x, y, color, shadow, true);
			return x + OriginSdfFont.width(s, true);
		}
		return g.drawString(font, semibold(s), x, y, color, shadow);
	}

	public static int width(Font font, String s) {
		return OriginSdfFont.active() ? OriginSdfFont.width(s, false) : font.width(regular(s));
	}

	public static int widthBold(Font font, String s) {
		return OriginSdfFont.active() ? OriginSdfFont.width(s, true) : font.width(semibold(s));
	}

	public static String ellipsize(Font font, String s, int maxW) {
		if (width(font, s) <= maxW) {
			return s;
		}
		String ell = "…";
		int ew = width(font, ell);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			if (width(font, sb.toString() + s.charAt(i)) + ew > maxW) {
				break;
			}
			sb.append(s.charAt(i));
		}
		return sb.append(ell).toString();
	}
}
